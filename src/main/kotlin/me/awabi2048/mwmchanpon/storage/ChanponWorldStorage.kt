package me.awabi2048.mwmchanpon.storage

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration

enum class SubmissionState(val storageValue: String, val i18nKey: String) {
    UNSUBMITTED("unsubmitted", "unsubmitted"),
    SUBMITTED("submitted", "submitted"),
    COMPLETED("completed", "completed");

    companion object {
        fun fromStorageValue(value: String?): SubmissionState? =
            entries.firstOrNull { it.storageValue == value }
    }
}

enum class ChanponWorldVisibility(val storageValue: String) {
    PUBLIC("public"),
    DEVELOPMENT("development"),
    PRIVATE("private");

    companion object {
        fun fromStorageValue(value: String?): ChanponWorldVisibility? =
            entries.firstOrNull { it.storageValue == value }
    }
}

enum class ChanponWorldProtectionMode(val storageValue: String, val i18nKey: String) {
    OWNER_ONLY("owner_only", "owner_only"),
    MEMBERS("members", "members"),
    PASSWORD("password", "password"),
    PUBLIC("public", "public");

    companion object {
        fun fromStorageValue(value: String?): ChanponWorldProtectionMode? =
            entries.firstOrNull { it.storageValue == value }
    }
}

class ChanponWorldStorage(dataFolder: File) {
    private val worldsDir = File(dataFolder, "my_worlds")
    private var visibilitySyncHandler: ((UUID) -> Unit)? = null

    init {
        worldsDir.mkdirs()
    }

    fun worldFile(worldUuid: UUID): File = File(worldsDir, "${worldUuid}.yml")

    fun setVisibilitySyncHandler(handler: (UUID) -> Unit) {
        visibilitySyncHandler = handler
    }

    fun isProductionWorld(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("production", false)
    }

    @Synchronized
    fun setProductionWorld(worldUuid: UUID, production: Boolean) {
        val config = loadOrCreate(worldUuid)
        val wasProduction = config.getBoolean("production", false)
        if (production) {
            config.set("production", true)
            config.set("production_set_at", LocalDateTime.now().toString())
            if (!wasProduction || !config.contains("submission.state")) {
                config.set("submission.state", SubmissionState.UNSUBMITTED.storageValue)
            }
        } else {
            config.set("production", null)
            config.set("production_set_at", null)
            config.set("submission", null)
        }
        saveAtomic(worldUuid, config)
    }

    fun getProductionSetAt(worldUuid: UUID): LocalDateTime? {
        val raw = load(worldUuid)?.getString("production_set_at") ?: return null
        return runCatching { LocalDateTime.parse(raw) }.getOrNull()
    }

    fun getVisibility(worldUuid: UUID): ChanponWorldVisibility {
        val config = load(worldUuid) ?: return ChanponWorldVisibility.PRIVATE
        return ChanponWorldVisibility.fromStorageValue(config.getString("visibility"))
            ?: when {
                config.getBoolean("development", false) -> ChanponWorldVisibility.DEVELOPMENT
                else -> ChanponWorldVisibility.PRIVATE
            }
    }

    @Synchronized
    fun setVisibility(worldUuid: UUID, visibility: ChanponWorldVisibility) {
        val config = loadOrCreate(worldUuid)
        config.set("visibility", visibility.storageValue)
        config.set("development", null)
        config.set("development_set_at", null)
        config.set("visibility_set_at", LocalDateTime.now().toString())
        saveAtomic(worldUuid, config)
        visibilitySyncHandler?.invoke(worldUuid)
    }

    fun getProtectionMode(worldUuid: UUID): ChanponWorldProtectionMode {
        val config = load(worldUuid) ?: return ChanponWorldProtectionMode.MEMBERS
        val value = config.getString("protection.mode") ?: return ChanponWorldProtectionMode.MEMBERS
        return ChanponWorldProtectionMode.fromStorageValue(value)
            ?: error("Protection mode is invalid: $worldUuid mode=$value")
    }

    @Synchronized
    fun setProtectionMode(worldUuid: UUID, mode: ChanponWorldProtectionMode) {
        val config = loadOrCreate(worldUuid)
        config.set("protection.mode", mode.storageValue)
        config.set("protection.updated_at", LocalDateTime.now().toString())
        saveAtomic(worldUuid, config)
    }

    fun hasProtectionPassword(worldUuid: UUID): Boolean {
        return getProtectionPassword(worldUuid) != null
    }

    fun getProtectionPassword(worldUuid: UUID): String? {
        return load(worldUuid)?.getString("protection.password")?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun setProtectionPassword(worldUuid: UUID, password: String) {
        val config = loadOrCreate(worldUuid)
        config.set("protection.password", password)
        config.set("protection.password_hash", null)
        config.set("protection.password_updated_at", LocalDateTime.now().toString())
        saveAtomic(worldUuid, config)
    }

    fun matchesProtectionPassword(worldUuid: UUID, password: String): Boolean {
        return getProtectionPassword(worldUuid) == password
    }

    @Synchronized
    fun initializeSubmissionState(worldUuid: UUID) {
        val config = loadOrCreate(worldUuid)
        config.set("submission.state", SubmissionState.UNSUBMITTED.storageValue)
        saveAtomic(worldUuid, config)
    }

    fun getSubmissionState(worldUuid: UUID): SubmissionState {
        val config = load(worldUuid)
            ?: error("MWM-Chanpon world data is missing: $worldUuid")
        val value = config.getString("submission.state")
        return SubmissionState.fromStorageValue(value)
            ?: error("Submission state is missing or invalid: $worldUuid")
    }

    fun isFreecamAllowed(worldUuid: UUID): Boolean {
        return load(worldUuid)?.getBoolean("environment.freecam_allowed", true) ?: true
    }

    @Synchronized
    fun setFreecamAllowed(worldUuid: UUID, allowed: Boolean) {
        val config = loadOrCreate(worldUuid)
        config.set("environment.freecam_allowed", allowed)
        saveAtomic(worldUuid, config)
    }

    fun isSubmissionCompleted(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return SubmissionState.fromStorageValue(config.getString("submission.state")) == SubmissionState.COMPLETED
    }

    @Synchronized
    fun setSubmissionState(
        worldUuid: UUID,
        state: SubmissionState,
        actorUuid: UUID,
        actorName: String,
        action: String
    ) {
        val config = load(worldUuid)
            ?: error("MWM-Chanpon world data is missing: $worldUuid")
        if (!config.contains("submission.state")) {
            error("Submission state is missing: $worldUuid")
        }
        config.set("submission.state", state.storageValue)
        val history = config.getMapList("submission.history").toMutableList()
        history.add(
            linkedMapOf(
                "at" to LocalDateTime.now().toString(),
                "action" to action,
                "state" to state.storageValue,
                "actor_uuid" to actorUuid.toString(),
                "actor_name" to actorName
            )
        )
        config.set("submission.history", history)
        saveAtomic(worldUuid, config)
    }

    fun isToolEnabled(worldUuid: UUID, playerUuid: UUID, toolId: String): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("tool_permissions.${playerUuid}.${toolId}", false)
    }

    fun getToolEnabledState(worldUuid: UUID, playerUuid: UUID, toolId: String): Boolean? {
        val config = load(worldUuid) ?: return null
        val path = "tool_permissions.${playerUuid}.${toolId}"
        return if (config.contains(path)) config.getBoolean(path, false) else null
    }

    @Synchronized
    fun setToolEnabled(worldUuid: UUID, playerUuid: UUID, toolId: String, enabled: Boolean) {
        val config = loadOrCreate(worldUuid)
        config.set("tool_permissions.${playerUuid}.${toolId}", enabled)
        saveAtomic(worldUuid, config)
    }

    fun getMemberToolStates(worldUuid: UUID, playerUuid: UUID): Map<String, Boolean> {
        val config = load(worldUuid) ?: return emptyMap()
        val section = config.getConfigurationSection("tool_permissions.${playerUuid}") ?: return emptyMap()
        return section.getKeys(false).associateWith { section.getBoolean(it, false) }
    }

    @Synchronized
    fun removeMember(worldUuid: UUID, playerUuid: UUID) {
        val config = load(worldUuid) ?: return
        config.set("tool_permissions.${playerUuid}", null)
        saveAtomic(worldUuid, config)
    }

    fun getLastBackupHash(worldUuid: UUID): String? {
        return load(worldUuid)?.getString("backup.last_hash")
    }

    @Synchronized
    fun setLastBackupHash(worldUuid: UUID, hash: String) {
        val config = loadOrCreate(worldUuid)
        config.set("backup.last_hash", hash)
        saveAtomic(worldUuid, config)
    }

    fun getOwnerManualBackupLastCreatedAt(worldUuid: UUID): Long {
        return load(worldUuid)?.getLong("backup.owner_manual.last_created_at", 0L) ?: 0L
    }

    @Synchronized
    fun setOwnerManualBackupLastCreatedAt(worldUuid: UUID, value: Long) {
        val config = loadOrCreate(worldUuid)
        config.set("backup.owner_manual.last_created_at", value)
        saveAtomic(worldUuid, config)
    }

    fun getManualRollbackLastExecutedAt(worldUuid: UUID): Long {
        return load(worldUuid)?.getLong("backup.manual_rollback.last_executed_at", 0L) ?: 0L
    }

    @Synchronized
    fun setManualRollbackLastExecutedAt(worldUuid: UUID, value: Long) {
        val config = loadOrCreate(worldUuid)
        config.set("backup.manual_rollback.last_executed_at", value)
        saveAtomic(worldUuid, config)
    }

    @Synchronized
    fun deleteWorld(worldUuid: UUID) {
        worldFile(worldUuid).delete()
    }

    fun isImportedArchive(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("imported_archive", false)
    }

    @Synchronized
    fun setImportedArchive(worldUuid: UUID, value: Boolean) {
        val config = loadOrCreate(worldUuid)
        config.set("imported_archive", value)
        saveAtomic(worldUuid, config)
    }

    fun getCompletedAt(worldUuid: UUID): java.time.LocalDateTime? {
        val config = load(worldUuid) ?: return null
        if (getSubmissionState(worldUuid) != SubmissionState.COMPLETED) return null
        val history = config.getMapList("submission.history")
        val completedEntries = history.filter {
            (it["action"] as? String) == "mark_completed"
        }
        val lastCompleted = completedEntries.maxByOrNull {
            (it["at"] as? String) ?: ""
        }
        val raw = lastCompleted?.get("at") as? String ?: return null
        return runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull()
    }

    private fun load(worldUuid: UUID): YamlConfiguration? {
        val file = worldFile(worldUuid)
        if (!file.exists()) return null
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun loadOrCreate(worldUuid: UUID): YamlConfiguration {
        val file = worldFile(worldUuid)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun saveAtomic(worldUuid: UUID, config: YamlConfiguration) {
        val target = worldFile(worldUuid)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        config.save(temporary)
        FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        val verified = YamlConfiguration.loadConfiguration(temporary)
        check(verified.getKeys(true) == config.getKeys(true)) {
            "Temporary Chanpon world data verification failed: $worldUuid"
        }
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

}
