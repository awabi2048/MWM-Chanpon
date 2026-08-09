package me.awabi2048.mwmchanpon.storage

import java.io.File
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration

class ChanponGlobalStorage(dataFolder: File) {
    private val settingsFile = File(dataFolder, "settings.yml")
    private val playersDir = File(dataFolder, "players")

    init {
        dataFolder.mkdirs()
        playersDir.mkdirs()
    }

    fun getSpawnWorldUuid(): UUID? {
        val value = settings().getString("spawn_world.uuid") ?: return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun setSpawnWorldUuid(worldUuid: UUID) {
        val config = settings()
        if (config.getString("spawn_world.uuid") != worldUuid.toString()) {
            config.set("spawn_world.policy_initialized_uuid", null)
        }
        config.set("spawn_world.uuid", worldUuid.toString())
        config.save(settingsFile)
    }

    fun isSpawnPolicyInitialized(worldUuid: UUID): Boolean =
        settings().getString("spawn_world.policy_initialized_uuid") == worldUuid.toString()

    fun setSpawnPolicyInitialized(worldUuid: UUID) {
        val config = settings()
        config.set("spawn_world.policy_initialized_uuid", worldUuid.toString())
        config.save(settingsFile)
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
        val config = settings()
        config.set("archive_locked", locked)
        config.save(settingsFile)
    }

    fun isBuildingAllowed(): Boolean {
        migrateLegacyPhase()
        return settings().getBoolean("building_allowed", true)
    }

    fun setBuildingAllowed(allowed: Boolean) {
        migrateLegacyPhase()
        val config = settings()
        config.set("building_allowed", allowed)
        config.save(settingsFile)
    }

    fun getWorldCreationAvailability(): WorldCreationAvailability {
        migrateLegacyPhase()
        val raw = settings().getString("world_creation_availability")
        return runCatching { WorldCreationAvailability.valueOf(raw.orEmpty()) }
            .getOrDefault(WorldCreationAvailability.PRODUCTION)
    }

    fun setWorldCreationAvailability(availability: WorldCreationAvailability) {
        migrateLegacyPhase()
        val config = settings()
        config.set("world_creation_availability", availability.name)
        config.save(settingsFile)
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
        migrateLegacyPhase()
        val config = settings()
        return AdminWorldControlsSnapshot(
            buildingAllowed = config.getBoolean("building_allowed", true),
            creationAvailability = getWorldCreationAvailability(),
            spawnWorldUuid = config.getString("spawn_world.uuid")?.let(::parseUuid),
            spawnPolicyInitializedUuid = config.getString("spawn_world.policy_initialized_uuid")?.let(::parseUuid),
        )
    }

    @Synchronized
    fun restoreAdminWorldControls(snapshot: AdminWorldControlsSnapshot) {
        val config = settings()
        config.set("building_allowed", snapshot.buildingAllowed)
        config.set("world_creation_availability", snapshot.creationAvailability.name)
        config.set("spawn_world.uuid", snapshot.spawnWorldUuid?.toString())
        config.set("spawn_world.policy_initialized_uuid", snapshot.spawnPolicyInitializedUuid?.toString())
        config.save(settingsFile)
    }

    private fun migrateLegacyPhase() {
        val config = settings()
        if (config.contains("building_allowed") && config.contains("world_creation_availability")) return
        when (config.getString("phase")) {
            "BUILD_LOCKED" -> {
                config.set("building_allowed", false)
                config.set("world_creation_availability", WorldCreationAvailability.DENIED.name)
            }
            "CREATION_ALLOWED" -> {
                config.set("building_allowed", true)
                config.set("world_creation_availability", WorldCreationAvailability.NON_PRODUCTION.name)
            }
            else -> {
                config.set("building_allowed", true)
                config.set("world_creation_availability", WorldCreationAvailability.PRODUCTION.name)
            }
        }
        config.set("phase", null)
        config.save(settingsFile)
    }

    private fun settings(): YamlConfiguration {
        if (!settingsFile.exists()) settingsFile.createNewFile()
        return YamlConfiguration.loadConfiguration(settingsFile)
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
