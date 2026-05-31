package me.awabi2048.mwmchanpon.tool

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import org.bukkit.Material

class ToolPermissionConfig(private val plugin: MWMChanponPlugin) {
    fun load(): List<ToolPermissionDefinition> {
        val section = plugin.config.getConfigurationSection("tool_permissions.tools") ?: return emptyList()
        return section.getKeys(false).mapNotNull { id ->
            val base = "tool_permissions.tools.$id"
            val materialName = plugin.config.getString("$base.material") ?: return@mapNotNull null
            val material = Material.matchMaterial(materialName) ?: Material.STONE
            val permissions = plugin.config.getStringList("$base.permissions").filter { it.isNotBlank() }
            if (permissions.isEmpty()) return@mapNotNull null
            ToolPermissionDefinition(
                id = id,
                displayName = plugin.config.getString("$base.display_name") ?: id,
                material = material,
                permissions = permissions,
                color = plugin.config.getString("$base.color") ?: "§7"
            )
        }
    }
}
