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
            val groupName = plugin.config.getString("$base.group")?.takeIf { it.isNotBlank() }
                ?: defaultGroupName(id)
            ToolPermissionDefinition(
                id = id,
                displayName = plugin.config.getString("$base.display_name") ?: id,
                material = material,
                groupName = groupName,
                color = plugin.config.getString("$base.color") ?: "§7"
            )
        }
    }

    private fun defaultGroupName(id: String): String {
        return when (id.lowercase()) {
            "worldedit" -> "worldeditor"
            "voxelsniper" -> "voxelsniper"
            "axiom" -> "axiom"
            else -> id.lowercase()
        }
    }
}
