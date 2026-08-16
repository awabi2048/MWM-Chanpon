package me.awabi2048.mwmchanpon.storage

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration

class ChanponGlobalStorage(dataFolder: File) {
    private val settingsFile = File(dataFolder, "settings.yml")
    private val playersDir = File(dataFolder, "players")
    private var currentWriteGate: (() -> Boolean)? = null

    init {
        dataFolder.mkdirs()
        playersDir.mkdirs()
    }

    fun getSpawnWorldUuid(): UUID? {
        val value = settings().getString("spawn_world.uuid") ?: return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun setSpawnWorldUuid(worldUuid: UUID) {
        ensureCurrentForWrite()
        val config = settings()
        if (config.getString("spawn_world.uuid") != worldUuid.toString()) {
            config.set("spawn_world.policy_initialized_uuid", null)
        }
        config.set("spawn_world.uuid", worldUuid.toString())
        saveSettings(config)
    }

    fun isSpawnPolicyInitialized(worldUuid: UUID): Boolean =
        settings().getString("spawn_world.policy_initialized_uuid") == worldUuid.toString()

    fun setSpawnPolicyInitialized(worldUuid: UUID) {
        ensureCurrentForWrite()
        val config = settings()
        config.set("spawn_world.policy_initialized_uuid", worldUuid.toString())
        saveSettings(config)
    }

    fun isInitialSpawnVisitCompleted(playerUuid: UUID): Boolean {
        return playerConfig(playerUuid).getBoolean("initial_spawn_visit_completed", false)
    }

    fun setInitialSpawnVisitCompleted(playerUuid: UUID) {
        val config = playerConfig(playerUuid)
        config.set("initial_spawn_visit_completed", true)
        config.save(playerFile(playerUuid))
    }

    /**
     * プレイヤーごとに、ログイン時のオフラインメンバー招待通知を送信済みか記録します。
     * 招待自体の状態はMWMが管理するため、ここには通知済みIDだけを保持します。
     */
    @Synchronized
    fun getSentOfflineMemberInviteIds(playerUuid: UUID): Set<UUID> =
        playerConfig(playerUuid)
            .getStringList(OFFLINE_MEMBER_INVITE_SENT_IDS_PATH)
            .mapNotNull(::parseUuid)
            .toSet()

    /**
     * MWMが現在返した保留招待に存在しない通知済みIDを整理します。
     * 保留招待の取得に成功した後だけ呼び出すことで、API障害時に履歴を誤って消しません。
     */
    @Synchronized
    fun retainOfflineMemberInviteIds(playerUuid: UUID, pendingInviteIds: Set<UUID>): Set<UUID> {
        val config = playerConfig(playerUuid)
        val current = config.getStringList(OFFLINE_MEMBER_INVITE_SENT_IDS_PATH)
            .mapNotNull(::parseUuid)
            .toSet()
        val retained = current.intersect(pendingInviteIds)
        if (retained != current) {
            config.set(
                OFFLINE_MEMBER_INVITE_SENT_IDS_PATH,
                retained.map(UUID::toString).sorted()
            )
            config.save(playerFile(playerUuid))
        }
        return retained
    }

    /**
     * 通知送信後にIDを保存します。UUID文字列はソートして保存し、ファイル差分を安定させます。
     * 戻り値は今回新しく追加されたかどうかです。
     */
    @Synchronized
    fun markOfflineMemberInviteNotified(playerUuid: UUID, inviteId: UUID): Boolean {
        val config = playerConfig(playerUuid)
        val notified = config.getStringList(OFFLINE_MEMBER_INVITE_SENT_IDS_PATH)
            .mapNotNull(::parseUuid)
            .toMutableSet()
        if (!notified.add(inviteId)) return false
        config.set(
            OFFLINE_MEMBER_INVITE_SENT_IDS_PATH,
            notified.map(UUID::toString).sorted()
        )
        config.save(playerFile(playerUuid))
        return true
    }

    fun isArchiveLocked(): Boolean =
        settings().getBoolean("archive_locked", false)

    fun setArchiveLocked(locked: Boolean) {
        ensureCurrentForWrite()
        val config = settings()
        config.set("archive_locked", locked)
        saveSettings(config)
    }

    fun isBuildingAllowed(): Boolean {
        val config = settings()
        return if (config.contains("building_allowed")) {
            config.getBoolean("building_allowed", true)
        } else {
            legacyBuildingAllowed(config)
        }
    }

    fun setCurrentWriteGate(gate: () -> Boolean) {
        currentWriteGate = gate
    }

    fun canWriteSettings(): Boolean = runCatching {
        ensureCurrentForWrite()
        true
    }.getOrDefault(false)

    fun setBuildingAllowed(allowed: Boolean) {
        ensureCurrentForWrite()
        val config = settings()
        require(!config.contains("phase")) {
            "settings.yml requires /mwm migration before building controls can be changed"
        }
        config.set("building_allowed", allowed)
        saveSettings(config)
    }

    fun getWorldCreationAvailability(): WorldCreationAvailability {
        val config = settings()
        val raw = config.getString("world_creation_availability")
        return runCatching { WorldCreationAvailability.valueOf(raw.orEmpty()) }
            .getOrElse { legacyCreationAvailability(config) }
    }

    fun setWorldCreationAvailability(availability: WorldCreationAvailability) {
        ensureCurrentForWrite()
        val config = settings()
        require(!config.contains("phase")) {
            "settings.yml requires /mwm migration before world creation controls can be changed"
        }
        config.set("world_creation_availability", availability.name)
        saveSettings(config)
    }

    /** 管理ワールド制御の可逆操作で比較・復元する不変スナップショットです。 */
    data class AdminWorldControlsSnapshot(
        val buildingAllowed: Boolean,
        val creationAvailability: WorldCreationAvailability,
        val spawnWorldUuid: UUID?,
        val spawnPolicyInitializedUuid: UUID?,
    )

    @Synchronized
    fun captureAdminWorldControls(): AdminWorldControlsSnapshot {
        val config = settings()
        return AdminWorldControlsSnapshot(
            buildingAllowed = if (config.contains("building_allowed")) {
                config.getBoolean("building_allowed", true)
            } else {
                legacyBuildingAllowed(config)
            },
            creationAvailability = getWorldCreationAvailability(),
            spawnWorldUuid = config.getString("spawn_world.uuid")?.let(::parseUuid),
            spawnPolicyInitializedUuid = config.getString("spawn_world.policy_initialized_uuid")?.let(::parseUuid),
        )
    }

    @Synchronized
    fun restoreAdminWorldControls(snapshot: AdminWorldControlsSnapshot) {
        ensureCurrentForWrite()
        val config = settings()
        require(!config.contains("phase")) {
            "settings.yml requires /mwm migration before world controls can be restored"
        }
        config.set("building_allowed", snapshot.buildingAllowed)
        config.set("world_creation_availability", snapshot.creationAvailability.name)
        config.set("spawn_world.uuid", snapshot.spawnWorldUuid?.toString())
        config.set("spawn_world.policy_initialized_uuid", snapshot.spawnPolicyInitializedUuid?.toString())
        saveSettings(config)
    }

    /** 旧 phase は読み取り時に解釈するだけで、ファイルへは書き戻しません。 */
    private fun legacyBuildingAllowed(config: YamlConfiguration): Boolean =
        config.getString("phase") != "BUILD_LOCKED"

    /** 旧 phase は読み取り時に解釈するだけで、ファイルへは書き戻しません。 */
    private fun legacyCreationAvailability(config: YamlConfiguration): WorldCreationAvailability = when (config.getString("phase")) {
        "BUILD_LOCKED" -> WorldCreationAvailability.DENIED
        "CREATION_ALLOWED" -> WorldCreationAvailability.NON_PRODUCTION
        else -> WorldCreationAvailability.PRODUCTION
    }

    private fun settings(): YamlConfiguration {
        if (!settingsFile.exists()) settingsFile.createNewFile()
        return YamlConfiguration.loadConfiguration(settingsFile)
    }

    /** settings.yml も移行後の通常更新を原子的に確定し、途中書き込みを残しません。 */
    private fun saveSettings(config: YamlConfiguration) {
        val temporary = File(settingsFile.parentFile, settingsFile.name + ".save.tmp")
        try {
            config.save(temporary)
            FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            val verified = YamlConfiguration().also { it.load(temporary) }
            check(!verified.contains("phase")) {
                "settings.yml still contains the legacy phase field"
            }
            if (verified.contains("building_allowed")) {
                check(verified.isBoolean("building_allowed")) {
                    "settings.yml building_allowed was not serialized as boolean"
                }
            }
            if (verified.contains("world_creation_availability")) {
                WorldCreationAvailability.valueOf(verified.getString("world_creation_availability").orEmpty())
            }
            try {
                Files.move(
                    temporary.toPath(),
                    settingsFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            Files.deleteIfExists(temporary.toPath())
            throw IllegalStateException("Could not save current Chanpon settings", error)
        }
    }

    /** 旧 phase の設定を通常処理が暗黙再保存しないための書き込み境界です。 */
    private fun ensureCurrentForWrite() {
        check(currentWriteGate?.invoke() != false) {
            "settings.yml requires /mwm migration before it can be modified"
        }
        check(!settingsFile.exists() || !YamlConfiguration().also { it.load(settingsFile) }.contains("phase")) {
            "settings.yml requires /mwm migration before it can be modified"
        }
    }

    private fun playerFile(playerUuid: UUID): File = File(playersDir, "$playerUuid.yml")

    private fun playerConfig(playerUuid: UUID): YamlConfiguration {
        val file = playerFile(playerUuid)
        if (!file.exists()) file.createNewFile()
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private companion object {
        const val OFFLINE_MEMBER_INVITE_SENT_IDS_PATH = "offline_member_invite_notifications.sent_ids"
    }
}
