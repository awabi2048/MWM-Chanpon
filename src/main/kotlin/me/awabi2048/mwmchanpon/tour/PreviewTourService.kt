package me.awabi2048.mwmchanpon.tour

import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponPreviewTourKeys
import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * プレビューツアーの実行中セッションを管理するランタイムです。
 *
 * 1回の開始操作で周囲のプレイヤー全員が参加者となり、全員が同じ進行を共有します。
 * ただし帰還位置や離脱はプレイヤーごとに独立して管理し、
 * 誰かが抜けても残りの参加者のツアーは継続します。
 */
class PreviewTourService(
    private val plugin: MWMChanponPlugin,
) {
    /** 1回の開始から終了までの実行単位です。巡業定義は開始時の複製を保持し、実行中の管理編集の影響を受けません。 */
    data class PreviewTourRun(
        val uuid: UUID,
        val tour: PreviewTourData,
        val returnLocations: MutableMap<UUID, Location>,
        var legIndex: Int = -1,
        var legEndsAtMillis: Long = 0L,
        var legDurationSeconds: Int = 1,
        val bossBar: BossBar,
    ) {
        fun participants(): List<UUID> = returnLocations.keys.toList()
    }

    enum class StartResult {
        STARTED,
        NO_STOPS,
        NO_PARTICIPANTS,
        ALREADY_IN_TOUR,
    }

    private val runs = ConcurrentHashMap<UUID, PreviewTourRun>()
    private val playerRunIndex = ConcurrentHashMap<UUID, PreviewTourRun>()
    private var tickTask: BukkitTask? = null

    fun start() {
        stopTask()
        tickTask = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { tick() },
            TICK_PERIOD_TICKS,
            TICK_PERIOD_TICKS,
        )
    }

    private fun stopTask() {
        tickTask?.cancel()
        tickTask = null
    }

    /** プラグイン停止時に全員を開始位置へ戻し、実行状態を破棄します。 */
    fun shutdownAll() {
        stopTask()
        runs.values.toList().forEach { run ->
            run.participants().forEach { uuid -> returnParticipant(uuid, run, teleport = true) }
            run.bossBar.removeAll()
        }
        runs.clear()
        playerRunIndex.clear()
    }

    fun runOf(playerUuid: UUID): PreviewTourRun? = playerRunIndex[playerUuid]

    fun isParticipant(playerUuid: UUID): Boolean = playerRunIndex.containsKey(playerUuid)

    fun activeRunsForTour(tourUuid: UUID): List<PreviewTourRun> =
        runs.values.filter { it.tour.uuid == tourUuid }

    /**
     * 看板の開始確認で確定したツアーを、クリックした本人と周囲のプレイヤー全員で開始します。
     * 参加者は開始時点で確定し、途中参加はありません。
     */
    fun startRun(tour: PreviewTourData, initiator: Player): StartResult {
        if (tour.stops.isEmpty()) return StartResult.NO_STOPS
        if (isParticipant(initiator.uniqueId)) return StartResult.ALREADY_IN_TOUR

        val radius = plugin.config.getDouble("preview_tour.join_radius", DEFAULT_JOIN_RADIUS)
        val participants = Bukkit.getOnlinePlayers()
            .asSequence()
            .filter { it.world == initiator.world }
            .filter { it.uniqueId == initiator.uniqueId || it.location.distanceSquared(initiator.location) <= radius * radius }
            .filterNot { isParticipant(it.uniqueId) }
            .toList()
        if (participants.isEmpty()) return StartResult.NO_PARTICIPANTS

        // 定義は開始時点の複製を持つため、実行中に管理画面で巡業を編集してもこの実行へは反映しません。
        val tourSnapshot = tour.copy(stops = tour.stops.toMutableList())
        val run = PreviewTourRun(
            uuid = UUID.randomUUID(),
            tour = tourSnapshot,
            returnLocations = participants.associateTo(mutableMapOf()) { it.uniqueId to it.location.clone() },
            bossBar = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID),
        )
        runs[run.uuid] = run
        participants.forEach { player ->
            playerRunIndex[player.uniqueId] = run
            run.bossBar.addPlayer(player)
            player.sendMessage(
                if (player.uniqueId == initiator.uniqueId) {
                    i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_STARTED, mapOf("tour" to run.tour.title))
                } else {
                    i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_JOINED, mapOf("tour" to run.tour.title))
                }
            )
            player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_LEAVE_HINT))
        }
        beginLeg(run, 0)
        return StartResult.STARTED
    }

    /**
     * 参加者1人をツアーから抜けさせます。帰還は利用者の意思による中断と同じ扱いで、
     * 常に開始位置へ戻します。全員が抜けた時点で実行は終了します。
     */
    fun leaveRun(player: Player): Boolean {
        val run = playerRunIndex[player.uniqueId] ?: return false
        removeParticipant(run, player.uniqueId, teleport = true)
        if (player.isOnline) {
            player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_LEFT))
        }
        finishIfEmpty(run)
        return true
    }

    /** ツアー削除時に、そのツアーの実行中セッションをすべて中断します。 */
    fun abortRunsForTour(tourUuid: UUID) {
        activeRunsForTour(tourUuid).forEach { run -> abortRun(run) }
    }

    /**
     * ログアウト時の離脱です。MWMの終了時位置保存より先に開始位置へ戻すことで、
     * 再ログイン時にツアー中のワールドへ復帰されないようにします。
     */
    fun handleQuit(player: Player) {
        val run = playerRunIndex[player.uniqueId] ?: return
        removeParticipant(run, player.uniqueId, teleport = true)
        finishIfEmpty(run)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        runs.values.forEach { run ->
            // 念のための整合維持：オフライン・無効化済みの参加情報を定期的に除去します。
            run.participants().forEach { uuid ->
                val player = Bukkit.getPlayer(uuid)
                if (player == null || !player.isOnline) {
                    playerRunIndex.remove(uuid, run)
                    run.returnLocations.remove(uuid)
                }
            }
            finishIfEmpty(run)
            if (!runs.containsKey(run.uuid)) return@forEach

            val remainingMillis = run.legEndsAtMillis - now
            if (remainingMillis <= 0L) {
                advance(run)
            } else {
                updateBossBar(run, remainingMillis)
            }
        }
    }

    private fun advance(run: PreviewTourRun) {
        val nextIndex = run.legIndex + 1
        if (nextIndex >= run.tour.stops.size) {
            completeRun(run)
            return
        }
        beginLeg(run, nextIndex)
    }

    private fun beginLeg(run: PreviewTourRun, index: Int) {
        val stop = run.tour.stops[index]
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(stop.worldUuid)
        if (worldData == null || worldData.isArchived) {
            run.participants().forEach { uuid ->
                Bukkit.getPlayer(uuid)?.sendMessage(
                    i18nComponent(
                        Bukkit.getPlayer(uuid),
                        ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_WORLD_SKIPPED,
                        mapOf("world" to stop.worldUuid.toString()),
                    ),
                )
            }
            advance(run)
            return
        }

        run.legIndex = index
        run.legDurationSeconds = stop.durationSeconds
        run.legEndsAtMillis = System.currentTimeMillis() + stop.durationSeconds * 1000L

        val worldService = MyWorldManagerApi.getWorldService()
        run.participants().forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) return@forEach
            val accepted = worldService?.teleportToWorldWithCompletion(
                player,
                stop.worldUuid,
                Runnable {
                    player.sendMessage(
                        i18nComponent(
                            player,
                            ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_ARRIVED,
                            mapOf(
                                "current" to (index + 1).toString(),
                                "total" to run.tour.stops.size.toString(),
                                "world" to worldData.name,
                            ),
                        ),
                    )
                },
            ) == true
            if (!accepted) {
                // 移行中・操作ロック等で同行できない参加者は、その時点で離脱と同じ帰還処理へ回します。
                removeParticipant(run, uuid, teleport = true)
                player.sendMessage(
                    i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_ABORTED, mapOf("tour" to run.tour.title)),
                )
            }
        }
        finishIfEmpty(run)
        if (runs.containsKey(run.uuid)) updateBossBar(run, stop.durationSeconds * 1000L)
    }

    private fun completeRun(run: PreviewTourRun) {
        run.participants().forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            returnParticipant(uuid, run, teleport = true)
            player?.sendMessage(
                i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_COMPLETED, mapOf("tour" to run.tour.title)),
            )
        }
        disposeRun(run)
    }

    private fun abortRun(run: PreviewTourRun) {
        run.participants().forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            returnParticipant(uuid, run, teleport = true)
            player?.sendMessage(
                i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_ABORTED, mapOf("tour" to run.tour.title)),
            )
        }
        disposeRun(run)
    }

    private fun removeParticipant(run: PreviewTourRun, playerUuid: UUID, teleport: Boolean) {
        playerRunIndex.remove(playerUuid, run)
        returnParticipant(playerUuid, run, teleport)
        run.bossBar.removePlayer(Bukkit.getPlayer(playerUuid) ?: return)
    }

    private fun returnParticipant(playerUuid: UUID, run: PreviewTourRun, teleport: Boolean) {
        val destination = run.returnLocations.remove(playerUuid) ?: return
        if (!teleport) return
        val player = Bukkit.getPlayer(playerUuid) ?: return
        teleportToReturnLocation(player, destination)
    }

    /**
     * 開始位置のワールドが管理ワールドとしてアンロード済みでも帰還できるよう、
     * 必要ならMWM経由で再ロードしてから送ります。
     */
    private fun teleportToReturnLocation(player: Player, saved: Location) {
        val target = saved.clone()
        val worldName = saved.world?.name ?: return
        var world = Bukkit.getWorld(worldName)
        if (world == null) {
            val managed = MyWorldManagerApi.getWorldRepository()?.findByWorldName(worldName)
            if (managed != null) {
                val service = MyWorldManagerApi.getWorldService()
                if (service != null && service.loadWorld(managed.uuid)) {
                    world = Bukkit.getWorld(service.getWorldFolderName(managed)) ?: Bukkit.getWorld(worldName)
                }
            }
        }
        if (world == null) {
            // 復元先を解決できない場合は既定ワールドのスポーンへ退避します。
            world = Bukkit.getWorlds().firstOrNull() ?: return
            player.teleport(world.spawnLocation)
            return
        }
        target.world = world
        player.teleport(target)
    }

    private fun updateBossBar(run: PreviewTourRun, remainingMillis: Long) {
        val worldName = run.tour.stops.getOrNull(run.legIndex)
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it.worldUuid)?.name }
            ?: run.tour.stops.getOrNull(run.legIndex)?.worldUuid?.toString()
            ?: "-"
        run.bossBar.setTitle(
            i18n(
                null,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_BOSSBAR_TITLE,
                mapOf(
                    "tour" to run.tour.title,
                    "world" to worldName,
                    "remaining" to formatDuration((remainingMillis / 1000L).coerceAtLeast(0L).toInt()),
                ),
            ),
        )
        run.bossBar.progress =
            (remainingMillis.toDouble() / (run.legDurationSeconds * 1000.0)).coerceIn(0.0, 1.0)
    }

    private fun finishIfEmpty(run: PreviewTourRun) {
        if (run.returnLocations.isEmpty() && runs.containsKey(run.uuid)) {
            disposeRun(run)
        }
    }

    private fun disposeRun(run: PreviewTourRun) {
        run.bossBar.removeAll()
        run.participants().forEach { playerRunIndex.remove(it, run) }
        runs.remove(run.uuid)
    }

    companion object {
        private const val TICK_PERIOD_TICKS = 20L
        private const val DEFAULT_JOIN_RADIUS = 10.0

        /** 表示用の時間表記です。残り時間・合計所要時間で共通に使います。 */
        fun formatDuration(totalSeconds: Int): String {
            if (totalSeconds <= 0) return "0秒"
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "${hours}時間${minutes}分"
                minutes > 0 && seconds > 0 -> "${minutes}分${seconds}秒"
                minutes > 0 -> "${minutes}分"
                else -> "${seconds}秒"
            }
        }
    }

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): String =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): net.kyori.adventure.text.Component =
        CCSystem.getAPI().getI18nComponent(player, key, placeholders)
}
