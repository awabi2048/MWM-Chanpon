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
}
