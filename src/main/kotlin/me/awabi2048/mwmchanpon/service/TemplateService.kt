package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.TemplateDefinition
import me.awabi2048.mwmchanpon.model.TemplateOriginLocation
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class TemplateService(private val plugin: MWMChanponPlugin) {
    private var templates: Map<String, TemplateDefinition> = emptyMap()

    fun reload() {
        val file = File(plugin.dataFolder, "templates.yml")
        val config = YamlConfiguration.loadConfiguration(file)
        val loaded = linkedMapOf<String, TemplateDefinition>()

        for (key in config.getKeys(false)) {
            val section = config.getConfigurationSection(key) ?: continue
            val path = section.getString("path") ?: continue
            val worldDirectory = File(plugin.server.worldContainer, path)
            if (!worldDirectory.exists() || !worldDirectory.isDirectory) {
                continue
            }

            loaded[key] = TemplateDefinition(
                key = key,
                path = path,
                name = section.getString("name") ?: key,
                description = section.getStringList("description"),
                icon = Material.matchMaterial(section.getString("icon").orEmpty()) ?: Material.GRASS_BLOCK,
                originLocation = parseOriginLocation(section.getString("origin_location")),
            )
        }

        templates = loaded
    }

    fun getAll(): List<TemplateDefinition> = templates.values.toList()

    fun get(key: String): TemplateDefinition? = templates[key]

    private fun parseOriginLocation(raw: String?): TemplateOriginLocation? {
        if (raw.isNullOrBlank()) {
            return null
        }

        val parts = raw.split(',').map { it.trim() }
        if (parts.size != 3) {
            return null
        }

        val x = parts[0].toDoubleOrNull() ?: return null
        val y = parts[1].toDoubleOrNull() ?: return null
        val z = parts[2].toDoubleOrNull() ?: return null
        return TemplateOriginLocation(x, y, z)
    }
}
