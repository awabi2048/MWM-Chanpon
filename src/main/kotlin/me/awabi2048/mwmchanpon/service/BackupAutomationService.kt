package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.max

class BackupAutomationService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService
) {
    private var task: BukkitTask? = null
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val activeRuns = mutableMapOf<AutomationType, AutomationRun>()
    private val executionGates = AutomationType.entries.associateWith { ScheduledExecutionGate() }
    private val lastPreparationSecond = mutableMapOf<AutomationType, LocalDateTime>()
    private var lastMigrationCheckSecond: LocalDateTime? = null
    private var migrationCurrent = false

    fun start() {
        stop()
        task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { tick() },
            1L,
            1L
        )
    }

    fun stop() {
        task?.cancel()
        task = null
        activeRuns.values.forEach { it.bossBar?.removeAll() }
        activeRuns.clear()
    }

    fun isManualOperationBlocked(player: Player): Boolean {
        if (player.hasPermission("mwmchanpon.admin")) return false
        val windowSeconds = plugin.config.getLong("backup.automation.manual_lock_minutes", 10L).coerceAtLeast(0L) * 60L
        if (windowSeconds <= 0L) return false
        return isWithinManualLockWindow("backup.auto_backup") || isWithinManualLockWindow("backup.auto_rollback")
    }

    private fun tick() {
        // 旧形式の設定を読み込んだまま自動処理を進めると、実行日時などの書き込みだけが
        // 先に発生し、移行前の設定と処理状態が食い違います。/mwm migration 完了まで
        // タスク自体は保持しますが、判定と書き込みを含む自動処理は停止します。
        // 時刻は毎 tick 判定し、ディスクを伴う設定検査と準備処理は毎秒に抑えます。
        val second = LocalDateTime.now().withNano(0)
        if (lastMigrationCheckSecond != second) {
            lastMigrationCheckSecond = second
            migrationCurrent = plugin.isConfigurationMigrationCurrent()
        }
        if (!migrationCurrent) return
        handleAutoBackup()
        handleAutoRollback()
    }

    private fun handleAutoBackup() {
        if (!plugin.config.getBoolean("backup.auto_backup.enabled", false)) {
            executionGates.getValue(AutomationType.BACKUP).reset()
            finishRun(AutomationType.BACKUP)
            return
        }
        val today = LocalDate.now().toString()
        if (plugin.config.getString("backup.auto_backup.last_run_date") == today) return
        val targetTime = parseTime(plugin.config.getString("backup.auto_backup.time", "04:00")) ?: return
        val decision = executionGates.getValue(AutomationType.BACKUP).observe(LocalDateTime.now(), targetTime)
        if (decision == ScheduledExecutionGate.Decision.SKIP) {
            finishRun(AutomationType.BACKUP)
            plugin.logger.warning("定時バックアップを定刻に実行できなかったため、今回はスキップしました。")
            return
        }
        if (!shouldPrepare(AutomationType.BACKUP, decision)) return
        val worldUuids = productionWorldUuids()
        handleRun(AutomationType.BACKUP, today, targetTime, worldUuids, decision == ScheduledExecutionGate.Decision.EXECUTE) {
            backupService.createVisitedScheduledBackup().thenApply { it.status }
        }
    }

    private fun handleAutoRollback() {
        if (!plugin.config.getBoolean("backup.auto_rollback.enabled", false)) {
            executionGates.getValue(AutomationType.ROLLBACK).reset()
            finishRun(AutomationType.ROLLBACK)
            return
        }
        val today = LocalDate.now().toString()
        if (plugin.config.getString("backup.auto_rollback.last_run_date") == today) return
        val targetTime = parseTime(plugin.config.getString("backup.auto_rollback.time", "05:00")) ?: return
        val decision = executionGates.getValue(AutomationType.ROLLBACK).observe(LocalDateTime.now(), targetTime)
        if (decision == ScheduledExecutionGate.Decision.SKIP) {
            finishRun(AutomationType.ROLLBACK)
            plugin.logger.warning("自動ロールバックを定刻に実行できなかったため、今回はスキップしました。")
            return
        }
        if (!shouldPrepare(AutomationType.ROLLBACK, decision)) return
        val restorePoint = backupService.latestRestorePoint()
        if (restorePoint == null) {
            if (decision == ScheduledExecutionGate.Decision.EXECUTE) {
                plugin.config.set("backup.auto_rollback.last_run_date", today)
                plugin.saveConfigIfCurrent()
                plugin.logger.warning("Auto rollback skipped because restore point is missing.")
            }
            return
        }
        handleRun(AutomationType.ROLLBACK, today, targetTime, restorePoint.worldUuids, decision == ScheduledExecutionGate.Decision.EXECUTE) {
            backupService.restoreBackup(restorePoint).thenApply { it.status }
        }
    }

    private fun shouldPrepare(type: AutomationType, decision: ScheduledExecutionGate.Decision): Boolean {
        if (decision == ScheduledExecutionGate.Decision.EXECUTE) return true
        val second = LocalDateTime.now().withNano(0)
        return lastPreparationSecond.put(type, second) != second
    }

    private fun handleRun(
        type: AutomationType,
        today: String,
        targetTime: LocalTime,
        worldUuids: List<UUID>,
        execute: Boolean,
        operation: () -> CompletableFuture<BackupOperationStatus>
    ) {
        if (worldUuids.isEmpty()) return
        val remaining = secondsUntil(targetTime)
        if (remaining > PREPARE_SECONDS) return

        val run = activeRuns.getOrPut(type) { AutomationRun(type, today, targetTime, worldUuids.distinct()) }
        if (run.date != today || run.targetTime != targetTime || run.worldUuids != worldUuids.distinct()) {
            finishRun(type)
            activeRuns[type] = AutomationRun(type, today, targetTime, worldUuids.distinct())
        }
        val current = activeRuns[type] ?: return
        updateBossBar(current, max(remaining, 0L))
        notifyWarnings(current, remaining)
        if (execute && !current.started) {
            startOperation(current, operation)
        }
    }

    private fun startOperation(run: AutomationRun, operation: () -> CompletableFuture<BackupOperationStatus>) {
        run.started = true
        val configPath = if (run.type == AutomationType.BACKUP) "backup.auto_backup.last_run_date" else "backup.auto_rollback.last_run_date"
        plugin.config.set(configPath, run.date)
        plugin.saveConfigIfCurrent()

        // ロールバックの退避は BackupService がロック取得後に担当します。
        // ここで先に退避させると失敗時の復帰と通知先が失われます。
        run.audience.addAll(run.players().map { it.uniqueId })
        if (run.type == AutomationType.BACKUP) {
            val evacuated = evacuatePlayers(run.worldUuids)
            if (evacuated == null) {
                run.players().forEach { it.sendMessage(i18nComponentFor(it, ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_BACKUP_FAILED)) }
                finishRun(run.type)
                return
            }
            run.evacuatedPlayers = evacuated
        }
        run.bossBar?.setTitle(i18n(run.type.runningKey))
        run.bossBar?.progress = 1.0
        run.players().forEach { it.sendMessage(i18nComponentFor(it, run.type.runningKey)) }

        val future = try {
            operation()
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
        future.whenComplete { status, throwable ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (throwable != null) {
                    plugin.logger.warning("${run.type.logName} failed: ${throwable.message}")
                }
                if (run.type == AutomationType.BACKUP) {
                    restoreEvacuatedPlayers(run.evacuatedPlayers, run.worldUuids)
                }
                val result = if (throwable == null) status else BackupOperationStatus.FAILED
                val key = if (run.type == AutomationType.BACKUP) {
                    if (result == BackupOperationStatus.SUCCESS) run.type.finishedKey else ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_BACKUP_FAILED
                } else when (result) {
                    BackupOperationStatus.SUCCESS -> run.type.finishedKey
                    BackupOperationStatus.RECOVERED -> ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_RECOVERED
                    BackupOperationStatus.RECOVERY_FAILED -> ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_RECOVERY_FAILED
                    else -> ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_FAILED
                }
                run.players().forEach { it.sendMessage(i18nComponentFor(it, key)) }
                if (activeRuns[run.type] === run) finishRun(run.type)
            })
        }
    }

    fun productionWorldUuids(): List<UUID> {
        return MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
            .map { it.uuid }
    }

    private fun notifyWarnings(run: AutomationRun, remaining: Long) {
        WARNING_SECONDS.forEach { warning ->
            if (warning in run.notifiedWarnings) return@forEach
            if (remaining > warning || remaining <= warning - WARNING_WINDOW_SECONDS) return@forEach
            run.notifiedWarnings.add(warning)
            run.players().forEach { it.sendMessage(i18nComponent(run.type.warningKey, mapOf("remaining" to warningLabel(warning)))) }
        }
    }

    private fun updateBossBar(run: AutomationRun, remaining: Long) {
        val bar = run.bossBar ?: Bukkit.createBossBar(
            i18n(run.type.countdownKey, mapOf("remaining" to warningLabel(remaining))),
            if (run.type == AutomationType.BACKUP) BarColor.BLUE else BarColor.RED,
            BarStyle.SEGMENTED_10
        ).also { run.bossBar = it }
        bar.setTitle(i18n(run.type.countdownKey, mapOf("remaining" to warningLabel(remaining))))
        bar.progress = (remaining.toDouble() / PREPARE_SECONDS.toDouble()).coerceIn(0.0, 1.0)
        run.players().forEach { if (!bar.players.contains(it)) bar.addPlayer(it) }
        bar.players.filter { it !in run.players().toSet() }.forEach(bar::removePlayer)
    }

    private fun evacuatePlayers(worldUuids: List<UUID>): List<EvacuatedPlayer>? {
        val targets = playersInWorlds(worldUuids)
        if (targets.isEmpty()) return emptyList()
        val location = MyWorldManagerApi.getEvacuationLocationOverride() ?: return null
        val evacuatedPlayers = mutableListOf<EvacuatedPlayer>()
        for (player in targets) {
            val worldUuid = currentWorldUuid(player) ?: continue
            val evacuated = EvacuatedPlayer(player.uniqueId, worldUuid, player.location.clone())
            if (!player.teleport(location)) {
                // 一人でも退避できなければ処理せず、先に退避した人を元の場所へ戻します。
                evacuatedPlayers.forEach { previous -> Bukkit.getPlayer(previous.playerUuid)?.teleport(previous.location) }
                return null
            }
            evacuatedPlayers.add(evacuated)
        }
        return evacuatedPlayers
    }

    private fun restoreEvacuatedPlayers(evacuatedPlayers: List<EvacuatedPlayer>, safeWorldUuids: List<UUID>) {
        val core = MyWorldManagerApi.getWorldService() ?: return
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        val safeWorlds = safeWorldUuids.toSet()
        evacuatedPlayers.filter { it.worldUuid in safeWorlds }.forEach { evacuated ->
            val player = Bukkit.getPlayer(evacuated.playerUuid) ?: return@forEach
            if (!player.isOnline) return@forEach
            val worldData = repository.findByUuid(evacuated.worldUuid) ?: return@forEach
            if (!core.loadWorld(evacuated.worldUuid)) return@forEach
            val world = Bukkit.getWorld(core.getWorldFolderName(worldData)) ?: return@forEach
            val destination = evacuated.location.clone()
            destination.world = world
            player.teleport(destination)
        }
    }

    private fun AutomationRun.players(): List<Player> = (playersInWorlds(worldUuids) +
        audience.mapNotNull { Bukkit.getPlayer(it) }.filter { it.isOnline }).distinctBy { it.uniqueId }

    private fun playersInWorlds(worldUuids: List<UUID>): List<Player> {
        val targetSet = worldUuids.toSet()
        return Bukkit.getOnlinePlayers().filter { player -> currentWorldUuid(player) in targetSet }
    }

    private fun currentWorldUuid(player: Player): UUID? {
        return MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid
            ?: if (player.world.name.startsWith("my_world.")) {
                runCatching { UUID.fromString(player.world.name.removePrefix("my_world.")) }.getOrNull()
            } else {
                null
            }
    }

    private fun finishRun(type: AutomationType) {
        activeRuns.remove(type)?.bossBar?.removeAll()
    }

    private fun secondsUntil(target: LocalTime): Long {
        return Duration.between(LocalTime.now(), target).seconds
    }

    private fun parseTime(raw: String?): LocalTime? {
        return runCatching { LocalTime.parse(raw ?: return null, timeFormatter) }.getOrNull()
    }

    private fun isWithinManualLockWindow(path: String): Boolean {
        if (!plugin.config.getBoolean("$path.enabled", false)) return false
        val target = parseTime(plugin.config.getString("$path.time")) ?: return false
        val windowSeconds = plugin.config.getLong("backup.automation.manual_lock_minutes", 10L).coerceAtLeast(0L) * 60L
        val diff = kotlin.math.abs(Duration.between(target, LocalTime.now()).seconds)
        val circularDiff = minOf(diff, 24L * 60L * 60L - diff)
        return circularDiff <= windowSeconds
    }

    private fun warningLabel(seconds: Long): String {
        return when (seconds) {
            900L -> "15分"
            300L -> "5分"
            60L -> "1分"
            15L -> "15秒"
            else -> if (seconds >= 60L) "${(seconds + 59L) / 60L}分" else "${seconds.coerceAtLeast(0L)}秒"
        }
    }

    private fun i18n(key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getLocalized(null, key, placeholders)
    }

    private fun i18nComponent(key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(null, key, placeholders)
    }

    private fun i18nComponentFor(player: Player, key: LocalizationKey<String>): Component =
        CCSystem.getAPI().getI18nComponent(player, key, emptyMap())

    private enum class AutomationType(
        val logName: String,
        val warningKey: LocalizationKey<String>,
        val countdownKey: LocalizationKey<String>,
        val runningKey: LocalizationKey<String>,
        val finishedKey: LocalizationKey<String>,
    ) {
        BACKUP("Scheduled backup", ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_BACKUP_WARNING, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_BACKUP_COUNTDOWN, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_BACKUP_RUNNING, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_BACKUP_FINISHED),
        ROLLBACK("Auto rollback", ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_ROLLBACK_WARNING, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_ROLLBACK_COUNTDOWN, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_ROLLBACK_RUNNING, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_NOTICE_ROLLBACK_FINISHED),
    }

    private data class AutomationRun(
        val type: AutomationType,
        val date: String,
        val targetTime: LocalTime,
        val worldUuids: List<UUID>,
        val notifiedWarnings: MutableSet<Long> = mutableSetOf(),
        var bossBar: BossBar? = null,
        val audience: MutableSet<UUID> = mutableSetOf(),
        var evacuatedPlayers: List<EvacuatedPlayer> = emptyList(),
        var started: Boolean = false
    )

    private data class EvacuatedPlayer(
        val playerUuid: UUID,
        val worldUuid: UUID,
        val location: Location
    )

    companion object {
        private const val PREPARE_SECONDS = 900L
        private const val WARNING_WINDOW_SECONDS = 10L
        private val WARNING_SECONDS = listOf(900L, 300L, 60L, 15L)
    }
}
