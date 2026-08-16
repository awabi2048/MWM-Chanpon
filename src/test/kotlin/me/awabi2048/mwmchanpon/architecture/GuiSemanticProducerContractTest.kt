package me.awabi2048.mwmchanpon.architecture

import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.MenuElementPresentationSemantics
import com.awabi2048.ccsystem.api.gui.MenuLoreLineKind
import com.awabi2048.ccsystem.api.gui.MenuNameSemantic
import com.awabi2048.ccsystem.api.gui.MenuPresentationProfile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import me.awabi2048.mwmchanpon.ui.framedLore
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuiSemanticProducerContractTest {
    @Test
    fun `inventory producers use explicit v3 semantic contracts`() {
        val combined = inventorySources().joinToString("\n") { Files.readString(it) }

        assertFalse(combined.contains("GuiLoreSpec.Rich"))
        assertFalse(Regex("GuiNameSpec\\.(Text|Component)").containsMatchIn(combined))
        assertFalse(Regex("MenuCapabilityAvailability\\s*\\{").containsMatchIn(combined))
        assertTrue(Regex("framedLore\\(").findAll(combined).count() >= 19)
        assertTrue(combined.contains("GuiNameSpec.FixedLabel") || combined.contains("fixedName("))
        assertTrue(combined.contains("GuiNameSpec.TargetIdentity") || combined.contains("targetIdentityName("))
        assertTrue(combined.contains("GuiNameSpec.Opaque") || combined.contains("opaqueName("))
    }

    @Test
    fun `framed blocks retain every requested frame and text has a known category`() {
        GuiLoreFrame.entries.forEach { frame ->
            val spec = framedLore(listOf(GuiLoreLine.Text("description")), frame) as GuiLoreSpec.FramedBlocks
            assertEquals(frame, spec.frame)
            assertEquals(listOf(GuiLoreLine.Text("description")), spec.blocks.single().lines)

            val semantics = semantics(
                GuiNameSpec.FixedLabel(Component.text("label")),
                spec,
                MenuPresentationProfile.DISPLAY_ONLY,
            )
            assertEquals(MenuNameSemantic.FIXED_LABEL, semantics.name)
            assertEquals(MenuLoreLineKind.DESCRIPTION, semantics.lore.blocks.single().lines.single().kind)
        }
    }

    @Test
    fun `displayed disabled producers carry explicit reasons through cc entry points`() {
        val export = Files.readString(sourceRoot().resolve("ui/WorldDataExportMenu.kt"))
        val settings = Files.readString(sourceRoot().resolve("extension/ChanponWorldSettingsCapabilities.kt"))

        assertEquals(2, Regex("menuUnavailable\\(").findAll(export).count())
        assertEquals(3, Regex("CHANPON_BACKUP_EXPORT_MENU_EXECUTE_DISABLED").findAll(export).count())
        assertTrue(settings.contains("reasonedAvailability("))
        assertTrue(settings.contains("featureUnavailableReason("))
        assertFalse(Regex("reason\\s*=\\s*\\{\\s*(null|Component\\.empty\\(\\))").containsMatchIn(export + settings))
    }

    @Test
    fun `semantic name categories remain distinguishable`() {
        val lore = GuiLoreSpec.FramedBlocks(
            listOf(com.awabi2048.ccsystem.api.gui.GuiLoreBlock(listOf(GuiLoreLine.Text("description")))),
            GuiLoreFrame.NONE,
        )
        assertEquals(MenuNameSemantic.FIXED_LABEL, semantics(
            GuiNameSpec.FixedLabel(Component.text("fixed")), lore, MenuPresentationProfile.DISPLAY_ONLY,
        ).name)
        assertEquals(MenuNameSemantic.TARGET_IDENTITY, semantics(
            GuiNameSpec.TargetIdentity(Component.text("target")), lore, MenuPresentationProfile.LIST_TARGET,
        ).name)
        assertEquals(MenuNameSemantic.OPAQUE, semantics(
            GuiNameSpec.Opaque(Component.text("opaque")), lore, MenuPresentationProfile.UNKNOWN,
        ).name)
    }

    @Test
    fun `tool permission capability supplies only host augmentation blocks`() {
        val source = Files.readString(sourceRoot().resolve("extension/ToolPermissionCapability.kt"))

        assertTrue(source.contains("MenuCapabilityPresentation.hostAugmentation("))
        assertTrue(source.contains("toolPermissionLoreBlock("))
        assertTrue(source.contains("CHANPON_MEMBER_LORE_TOOL_PERMISSION_OPERATION"))
        assertFalse(source.contains("GuiNameSpec.TargetIdentity("))
        assertFalse(source.contains("playerHeadOwner"))
        assertFalse(source.contains("Material.PLAYER_HEAD"))
        assertFalse(source.contains("last_online_label"))
    }

    private fun inventorySources(): List<Path> = Files.walk(sourceRoot()).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.name.endsWith(".kt") }
            // Cumulus/Floodgate forms do not produce inventory items.
            .filter { !it.toString().replace('\\', '/').contains("/ui/bedrock/") }
            // Dialog bodies and actual gameplay ItemStacks are outside the inventory-menu producer contract.
            .filter { !it.name.contains("Dialog") && it.name != "CustomItem.kt" }
            .toList()
    }

    private fun semantics(
        name: GuiNameSpec,
        lore: GuiLoreSpec,
        profile: MenuPresentationProfile,
    ): MenuElementPresentationSemantics {
        val type = Class.forName("com.awabi2048.ccsystem.core.gui.MenuPresentationSemanticsFactory")
        val i18n: (Player?, String, Map<String, Any>) -> String = { _, key, _ -> key }
        val factory = type.getConstructor(Function3::class.java).newInstance(i18n)
        return type.getMethod(
            "create",
            GuiNameSpec::class.java,
            GuiLoreSpec::class.java,
            MenuPresentationProfile::class.java,
            Component::class.java,
        ).invoke(factory, name, lore, profile, null) as MenuElementPresentationSemantics
    }

    private fun sourceRoot(): Path = Path.of("src/main/kotlin/me/awabi2048/mwmchanpon")
}
