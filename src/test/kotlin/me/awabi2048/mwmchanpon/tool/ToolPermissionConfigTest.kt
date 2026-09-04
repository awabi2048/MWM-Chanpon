package me.awabi2048.mwmchanpon.tool

import java.nio.file.Path
import kotlin.io.path.readText
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ToolPermissionConfigTest {
    @Test
    fun `config exposes only external tool to group mappings`() {
        val config = YamlConfiguration().apply {
            loadFromString(Path.of("src/main/resources/config.yml").readText())
        }

        val definitions = ToolPermissionConfig(config).load()

        assertEquals(listOf("worldedit", "voxelsniper", "axiom"), definitions.map(ToolPermissionDefinition::id))
        assertEquals(listOf("worldeditor", "voxelsniper", "axiom"), definitions.map(ToolPermissionDefinition::groupName))
        assertEquals(Material.WOODEN_AXE, definitions[0].material)
        assertEquals(Material.ARROW, definitions[1].material)
        assertEquals(Material.ENDER_EYE, definitions[2].material)
        assertFalse(config.contains("tool_permissions.menu_slot"))
        assertFalse(config.contains("tool_permissions.tools.commandblock"))
    }

    @Test
    fun `legacy metadata is ignored and only its group is read before migration`() {
        val config = YamlConfiguration().apply {
            loadFromString(
                """
                tool_permissions:
                  menu_slot: 5
                  tools:
                    worldedit:
                      display_name: Changed
                      material: DIAMOND
                      color: "§c"
                      group: legacy-worldedit
                      permission: ignored.node
                    commandblock:
                      group: commandblock
                """.trimIndent(),
            )
        }

        val definitions = ToolPermissionConfig(config).load()

        assertEquals(listOf("worldedit"), definitions.map(ToolPermissionDefinition::id))
        assertEquals("legacy-worldedit", definitions.single().groupName)
        assertEquals("WorldEdit", definitions.single().displayName)
        assertEquals(Material.WOODEN_AXE, definitions.single().material)
    }

    @Test
    fun `v4 migration keeps groups and removes obsolete settings`() {
        val config = YamlConfiguration().apply {
            loadFromString(
                """
                tool_permissions:
                  menu_slot: 5
                  tools:
                    worldedit:
                      display_name: WorldEdit
                      material: WOODEN_AXE
                      color: "§a"
                      group: legacy-worldedit
                    commandblock:
                      display_name: 拡張コマンドブロック
                      material: COMMAND_BLOCK
                      group: commandblock
                """.trimIndent(),
            )
        }

        ToolPermissionConfigSchema.migrate(config)
        ToolPermissionConfigSchema.validate(config)

        assertEquals("legacy-worldedit", config.getString("tool_permissions.tools.worldedit"))
        assertFalse(config.contains("tool_permissions.menu_slot"))
        assertFalse(config.contains("tool_permissions.tools.commandblock"))
        assertFalse(config.contains("tool_permissions.tools.worldedit.group"))
    }
}
