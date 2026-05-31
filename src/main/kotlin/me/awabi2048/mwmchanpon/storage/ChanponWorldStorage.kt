package me.awabi2048.mwmchanpon.storage

import java.io.File
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration

class ChanponWorldStorage(dataFolder: File) {
    private val worldsDir = File(dataFolder, "my_worlds")

    init {
        worldsDir.mkdirs()
    }

    private fun worldFile(worldUuid: UUID): File = File(worldsDir, "${worldUuid}.yml")

    fun isProductionWorld(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("production", false)
    }

    fun setProductionWorld(worldUuid: UUID, production: Boolean) {
        val config = loadOrCreate(worldUuid)
        if (production) {
            config.set("production", true)
            config.set("production_set_at", java.time.LocalDateTime.now().toString())
        } else {
            config.set("production", null)
            config.set("production_set_at", null)
        }
        config.save(worldFile(worldUuid))
    }

    fun isToolEnabled(worldUuid: UUID, playerUuid: UUID, toolId: String): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("tool_permissions.${playerUuid}.${toolId}", false)
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

    fun deleteWorld(worldUuid: UUID) {
        worldFile(worldUuid).delete()
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
