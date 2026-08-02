package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.MenuCapabilityCompositionMode
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPermissionCapabilityPresentationTest {
    @Test
    fun `four world-state fixtures render title and each tool exactly once in definition order`() {
        val fixtures = listOf(
            emptyMap(),
            mapOf("worldedit" to true, "voxelsniper" to true, "axiom" to true),
            mapOf("worldedit" to true, "voxelsniper" to false, "axiom" to true),
            mapOf("worldedit" to false, "voxelsniper" to true, "axiom" to false),
        )

        fixtures.forEach { states ->
            val block = toolPermissionLoreBlock("ツール権限", definitions, states)

            assertEquals(GuiLoreLine.Data("ツール権限", null, ""), block.lines.first())
            assertEquals(4, block.lines.size)
            val rendered = block.lines.drop(1).map { (it as GuiLoreLine.Text).text }
            assertEquals(
                definitions.map(ToolPermissionDefinition::displayName),
                rendered.map { it.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "").substringAfterLast(' ') },
            )
            definitions.forEach { definition ->
                assertEquals(1, rendered.count { it.contains(definition.displayName) })
                val line = rendered.single { it.contains(definition.displayName) }
                if (states[definition.id] == true) assertTrue(line.startsWith("§6●"))
                else assertTrue(line.startsWith("§8● §7"))
            }
        }
    }

    @Test
    fun `tool block is a non-empty host augmentation without item lore ownership`() {
        val block = toolPermissionLoreBlock("ツール権限", definitions, emptyMap())
        val presentation = MenuCapabilityPresentation.hostAugmentation(listOf(block))

        assertEquals(MenuCapabilityCompositionMode.HOST_AUGMENTATION, presentation.compositionMode)
        assertEquals(GuiLoreSpec.None, presentation.item.lore)
        assertEquals(listOf(block), presentation.embeddedLoreBlocks)
        assertTrue(presentation.embeddedLoreBlocks.isNotEmpty())
    }

    private val definitions = listOf(
        ToolPermissionDefinition("worldedit", "WorldEdit", Material.WOODEN_AXE, "worldedit"),
        ToolPermissionDefinition("voxelsniper", "VoxelSniper", Material.ARROW, "voxelsniper"),
        ToolPermissionDefinition("axiom", "Axiom", Material.PAPER, "axiom"),
    )
}
