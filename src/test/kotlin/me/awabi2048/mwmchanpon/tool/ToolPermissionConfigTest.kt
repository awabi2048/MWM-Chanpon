package me.awabi2048.mwmchanpon.tool

import java.nio.file.Path
import kotlin.io.path.readText
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPermissionConfigTest {
    @Test
    fun `config file exposes the extended command block permission`() {
        val config = YamlConfiguration().apply {
            loadFromString(Path.of("src/main/resources/config.yml").readText())
        }

        val definitions = ToolPermissionConfig(config).load()
        val commandBlock = definitions.single { it.id == "commandblock" }

        assertEquals(4, definitions.size)
        assertEquals("拡張コマンドブロック", commandBlock.displayName)
        assertEquals(Material.COMMAND_BLOCK, commandBlock.material)
        assertEquals("commandblock", commandBlock.groupName)
        assertEquals("§6", commandBlock.color)
        assertTrue(definitions.map(ToolPermissionDefinition::id).contains("commandblock"))
    }
}
