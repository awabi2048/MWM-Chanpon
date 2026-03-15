package me.awabi2048.mwmchanpon.repository

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.PlayerSettingsData
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class PlayerSettingsRepository(private val plugin: MWMChanponPlugin) {
    private val rootDirectory: File
        get() = File(plugin.dataFolder.parentFile, "player_settings")

    fun load(playerUuid: UUID): PlayerSettingsData? {
        val file = File(rootDirectory, "$playerUuid.yml")
        if (!file.exists()) {
            return null
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val section = config.getConfigurationSection("player_settings") ?: return null
        return PlayerSettingsData(
            playerUuid = UUID.fromString(section.getString("player_uuid") ?: return null),
            language = section.getString("language")?.takeIf { it.isNotBlank() },
        )
    }

    fun save(data: PlayerSettingsData) {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }

        val config = YamlConfiguration()
        val section = config.createSection("player_settings")
        section.set("player_uuid", data.playerUuid.toString())
        section.set("language", data.language)
        config.save(File(rootDirectory, "${data.playerUuid}.yml"))
    }
}
