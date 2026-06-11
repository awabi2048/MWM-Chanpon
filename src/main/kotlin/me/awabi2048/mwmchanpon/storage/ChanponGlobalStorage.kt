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
        config.set("spawn_world.uuid", worldUuid.toString())
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

    fun isArchiveLocked(): Boolean =
        settings().getBoolean("archive_locked", false)

    fun setArchiveLocked(locked: Boolean) {
        val config = settings()
        config.set("archive_locked", locked)
        config.save(settingsFile)
    }

    fun isWorldCreationEnabled(): Boolean =
        settings().getBoolean("world_creation.enabled", true)

    fun setWorldCreationEnabled(enabled: Boolean) {
        val config = settings()
        config.set("world_creation.enabled", enabled)
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
}
