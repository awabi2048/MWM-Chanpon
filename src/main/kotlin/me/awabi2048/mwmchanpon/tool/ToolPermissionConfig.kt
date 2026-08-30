package me.awabi2048.mwmchanpon.tool

import org.bukkit.configuration.Configuration
import org.bukkit.Material

class ToolPermissionConfig(private val config: Configuration) {
    fun load(): List<ToolPermissionDefinition> {
        val section = config.getConfigurationSection("tool_permissions.tools") ?: return emptyList()
        return section.getKeys(false).mapNotNull { id ->
            val base = "tool_permissions.tools.$id"
            val materialName = config.getString("$base.material") ?: return@mapNotNull null
            val material = Material.matchMaterial(materialName) ?: Material.STONE
            val groupName = config.getString("$base.group")?.takeIf { it.isNotBlank() }
                ?: defaultGroupName(id)
            ToolPermissionDefinition(
                id = id,
                displayName = config.getString("$base.display_name") ?: id,
                material = material,
                groupName = groupName,
                color = config.getString("$base.color") ?: "§7"
            )
        }
    }

    private fun defaultGroupName(id: String): String {
        return when (id.lowercase()) {
            "worldedit" -> "worldeditor"
            "voxelsniper" -> "voxelsniper"
            "axiom" -> "axiom"
            "commandblock", "command_block" -> "commandblock"
            else -> id.lowercase()
        }
    }
}
