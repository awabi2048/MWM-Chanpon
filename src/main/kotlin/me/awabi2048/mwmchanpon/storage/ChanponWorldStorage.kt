package me.awabi2048.mwmchanpon.storage

import java.io.File
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

class ChanponWorldStorage(dataFolder: File) {
    private val worldsDir = File(dataFolder, "my_worlds")

    init {
        worldsDir.mkdirs()
    }

    fun worldFile(worldUuid: UUID): File = File(worldsDir, "${worldUuid}.yml")

    fun isProductionWorld(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("production", false)
    }

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
        config.save(worldFile(worldUuid))
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

    fun setVisibility(worldUuid: UUID, visibility: ChanponWorldVisibility) {
        val config = loadOrCreate(worldUuid)
        config.set("visibility", visibility.storageValue)
        config.set("development", null)
        config.set("development_set_at", null)
        config.set("visibility_set_at", LocalDateTime.now().toString())
        config.save(worldFile(worldUuid))
    }

    fun initializeSubmissionState(worldUuid: UUID) {
        val config = loadOrCreate(worldUuid)
        config.set("submission.state", SubmissionState.UNSUBMITTED.storageValue)
        config.save(worldFile(worldUuid))
    }

    fun getSubmissionState(worldUuid: UUID): SubmissionState {
        val config = load(worldUuid)
            ?: error("MWM-Chanpon world data is missing: $worldUuid")
        val value = config.getString("submission.state")
        return SubmissionState.fromStorageValue(value)
            ?: error("Submission state is missing or invalid: $worldUuid")
    }

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
        config.save(worldFile(worldUuid))
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

    fun setToolEnabled(worldUuid: UUID, playerUuid: UUID, toolId: String, enabled: Boolean) {
        val config = loadOrCreate(worldUuid)
        config.set("tool_permissions.${playerUuid}.${toolId}", enabled)
        config.save(worldFile(worldUuid))
    }

    fun getMemberToolStates(worldUuid: UUID, playerUuid: UUID): Map<String, Boolean> {
        val config = load(worldUuid) ?: return emptyMap()
        val section = config.getConfigurationSection("tool_permissions.${playerUuid}") ?: return emptyMap()
        return section.getKeys(false).associateWith { section.getBoolean(it, false) }
    }

    fun removeMember(worldUuid: UUID, playerUuid: UUID) {
        val config = load(worldUuid) ?: return
        config.set("tool_permissions.${playerUuid}", null)
        config.save(worldFile(worldUuid))
    }

    fun getLastBackupHash(worldUuid: UUID): String? {
        return load(worldUuid)?.getString("backup.last_hash")
    }

    fun setLastBackupHash(worldUuid: UUID, hash: String) {
        val config = loadOrCreate(worldUuid)
        config.set("backup.last_hash", hash)
        config.save(worldFile(worldUuid))
    }

    fun getOwnerManualBackupLastCreatedAt(worldUuid: UUID): Long {
        return load(worldUuid)?.getLong("backup.owner_manual.last_created_at", 0L) ?: 0L
    }

    fun setOwnerManualBackupLastCreatedAt(worldUuid: UUID, value: Long) {
        val config = loadOrCreate(worldUuid)
        config.set("backup.owner_manual.last_created_at", value)
        config.save(worldFile(worldUuid))
    }

    fun getManualRollbackLastExecutedAt(worldUuid: UUID): Long {
        return load(worldUuid)?.getLong("backup.manual_rollback.last_executed_at", 0L) ?: 0L
    }

    fun setManualRollbackLastExecutedAt(worldUuid: UUID, value: Long) {
        val config = loadOrCreate(worldUuid)
        config.set("backup.manual_rollback.last_executed_at", value)
        config.save(worldFile(worldUuid))
    }

    fun deleteWorld(worldUuid: UUID) {
        worldFile(worldUuid).delete()
    }

    fun isImportedArchive(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("imported_archive", false)
    }

    fun setImportedArchive(worldUuid: UUID, value: Boolean) {
        val config = loadOrCreate(worldUuid)
        config.set("imported_archive", value)
        config.save(worldFile(worldUuid))
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
}
