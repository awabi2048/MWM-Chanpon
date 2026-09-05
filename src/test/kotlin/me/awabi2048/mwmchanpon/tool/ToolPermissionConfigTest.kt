package me.awabi2048.mwmchanpon.tool

import java.nio.file.Path
import kotlin.io.path.readText
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPermissionConfigTest {
    @Test
    fun `config exposes external tools and Kantan command block mapping`() {
        val config = YamlConfiguration().apply {
            loadFromString(Path.of("src/main/resources/config.yml").readText())
        }

        val definitions = ToolPermissionConfig(config).load()

        assertEquals(listOf("worldedit", "voxelsniper", "axiom", "commandblock"), definitions.map(ToolPermissionDefinition::id))
        assertEquals(listOf("worldeditor", "voxelsniper", "axiom", "commandblock"), definitions.map(ToolPermissionDefinition::groupName))
        assertEquals(Material.WOODEN_AXE, definitions[0].material)
        assertEquals(Material.ARROW, definitions[1].material)
        assertEquals(Material.ENDER_EYE, definitions[2].material)
        assertEquals(Material.COMMAND_BLOCK, definitions[3].material)
        assertEquals("kankoma.extended_command_block", definitions[3].permissionNode)
        assertFalse(config.contains("tool_permissions.menu_slot"))
        assertTrue(config.contains("tool_permissions.tools.commandblock"))
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

        assertEquals(listOf("worldedit", "commandblock"), definitions.map(ToolPermissionDefinition::id))
        assertEquals("legacy-worldedit", definitions.first().groupName)
        assertEquals("WorldEdit", definitions.first().displayName)
        assertEquals(Material.WOODEN_AXE, definitions.first().material)
        assertEquals("kankoma.extended_command_block", definitions.last().permissionNode)
    }

    @Test
    fun `v4 migration keeps groups and removes obsolete layout metadata`() {
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
        assertEquals("commandblock", config.getString("tool_permissions.tools.commandblock"))
        assertFalse(config.contains("tool_permissions.tools.worldedit.group"))
    }

    @Test
    fun `v5 migration restores Kantan command block mapping without overwriting groups`() {
        val config = YamlConfiguration().apply {
            loadFromString(
                """
                tool_permissions:
                  tools:
                    worldedit: custom-worldedit
                    voxelsniper: voxelsniper
                    axiom: axiom
                config_version: 5
                """.trimIndent(),
            )
        }

        ToolPermissionConfigSchema.ensureKantanCommandBlock(config)
        ToolPermissionConfigSchema.validate(config)

        assertEquals("custom-worldedit", config.getString("tool_permissions.tools.worldedit"))
        assertEquals("commandblock", config.getString("tool_permissions.tools.commandblock"))

        config.set("tool_permissions.tools.commandblock", "custom-kantan-group")
        ToolPermissionConfigSchema.ensureKantanCommandBlock(config)
        assertEquals("custom-kantan-group", config.getString("tool_permissions.tools.commandblock"))
    }
}
