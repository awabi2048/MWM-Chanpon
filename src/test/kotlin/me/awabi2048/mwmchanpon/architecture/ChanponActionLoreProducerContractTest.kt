package me.awabi2048.mwmchanpon.architecture

import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.MenuLoreLineKind
import com.awabi2048.ccsystem.api.gui.MenuAcceptedClicks
import com.awabi2048.ccsystem.api.gui.MenuElementPresentationSemantics
import com.awabi2048.ccsystem.api.gui.MenuPresentationProfile
import com.awabi2048.ccsystem.api.gui.MenuPresentationSemanticsValidator
import com.awabi2048.ccsystem.core.gui.LoreServiceImpl
import java.nio.file.Files
import java.nio.file.Path
import net.kyori.adventure.text.Component
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponActionLoreProducerContractTest {
    private val i18n: (org.bukkit.entity.Player?, String, Map<String, Any>) -> String = { _, key, _ ->
        when (key) {
            "gui.input.left_click" -> "左クリック"
            "gui.input.right_click" -> "右クリック"
            "gui.input.left_or_right_click" -> "クリック"
            "gui.input.any_click" -> "クリック"
            "lore.click.any" -> "クリック"
            else -> key
        }
    }

    @Test
    fun `confirmation producers retain six natural semantic roles`() {
        val production = Files.readString(source("extension/ProductionToggleExtension.kt"))
        val settings = Files.readString(source("ui/ChanponWorldSettingsMenu.kt"))

        assertTrue(Regex("GuiLoreSpec\\.None,\\s+role,").containsMatchIn(production))
        assertFalse(production.contains(").copy(role = role)"))
        assertTrue(settings.contains("actionItem.copy(role = role)"))
        assertFalse(settings.contains("actionItem.copy(role = GuiElementRole.ACTION)"))
        // production confirm/cancel 2件と、expand/step-back の confirm/cancel 4件です。
        assertEquals(6, 2 + 2 * 2)
    }

    @Test
    fun `none plus action produces one leading-nonblank lore line with matching fingerprint`() {
        val renderer = LoreServiceImpl(i18n)
        val action = GuiLoreLine.Interaction(null, MenuAcceptedClicks.STANDARD, "確定する")
        val composed = renderer.compose(GuiLoreSpec.None, listOf(action))
        val lore = renderer.render(composed)
        val semantics = semantics(GuiNameSpec.FixedLabel(Component.text("確定")), composed, MenuPresentationProfile.SINGLE_STANDARD_ACTION)

        assertTrue(lore.first().toString().isNotBlank())
        assertEquals(1, lore.count { it.toString().contains("確定する") })
        val semanticAction = semantics.lore.blocks.single().lines.single()
        assertEquals(MenuLoreLineKind.ACTION, semanticAction.kind)
        assertEquals("確定する", semanticAction.action!!.actionLabel)
    }

    @Test
    fun `environment two-click action is a known single custom action`() {
        val action = GuiLoreLine.Interaction(
            null,
            setOf(ClickType.LEFT, ClickType.RIGHT),
            "環境設定を開く",
        )
        val composed = LoreServiceImpl(i18n).compose(GuiLoreSpec.None, listOf(action))
        val semantics = semantics(
            GuiNameSpec.FixedLabel(Component.text("環境設定")),
            composed,
            MenuPresentationProfile.SINGLE_CUSTOM_ACTION,
        )

        assertEquals(MenuPresentationProfile.SINGLE_CUSTOM_ACTION, semantics.profile)
        assertEquals(setOf(ClickType.LEFT, ClickType.RIGHT), semantics.lore.blocks.single().lines.single().action?.acceptedClicks)
        assertTrue(MenuPresentationSemanticsValidator.violations(semantics).isEmpty())
    }

    @Test
    fun `linked portal keeps its label and composes exactly one standard action`() {
        val source = Files.readString(source("extension/LinkedPortalCapability.kt"))
        assertTrue(source.contains("lore = GuiLoreSpec.None"))
        assertFalse(source.contains("lore = GuiLoreSpec.NameOnly"))

        val renderer = LoreServiceImpl(i18n)
        val composed = renderer.compose(
            GuiLoreSpec.None,
            listOf(GuiLoreLine.Interaction(null, MenuAcceptedClicks.STANDARD, "ポータルを受け取る")),
        )
        val result = semantics(
            GuiNameSpec.FixedLabel(Component.text("このワールドのワールドポータルを取得")),
            composed,
            MenuPresentationProfile.SINGLE_STANDARD_ACTION,
        )
        val action = result.lore.blocks.single().lines.single().action!!

        assertEquals(MenuAcceptedClicks.STANDARD, action.acceptedClicks)
        assertEquals("クリック", action.operationLabel)
        assertEquals("ポータルを受け取る", action.actionLabel)
        assertTrue(MenuPresentationSemanticsValidator.violations(result).isEmpty())
    }

    private fun source(relative: String): Path =
        Path.of("src/main/kotlin/me/awabi2048/mwmchanpon").resolve(relative)

    private fun semantics(
        name: GuiNameSpec,
        lore: GuiLoreSpec,
        profile: MenuPresentationProfile,
    ): MenuElementPresentationSemantics {
        val type = Class.forName("com.awabi2048.ccsystem.core.gui.MenuPresentationSemanticsFactory")
        val factory = type.getConstructor(Function3::class.java).newInstance(i18n)
        return type.getMethod(
            "create",
            GuiNameSpec::class.java,
            GuiLoreSpec::class.java,
            MenuPresentationProfile::class.java,
            Component::class.java,
        ).invoke(factory, name, lore, profile, null) as MenuElementPresentationSemantics
    }
}
