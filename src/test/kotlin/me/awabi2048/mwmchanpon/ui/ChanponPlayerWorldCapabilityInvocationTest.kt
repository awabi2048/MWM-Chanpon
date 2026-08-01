package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuRenderer
import com.awabi2048.ccsystem.api.gui.MenuActionObservation
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuContractValidator
import com.awabi2048.ccsystem.api.gui.MenuInteraction
import com.awabi2048.ccsystem.api.gui.ResolvedMenuCapability
import com.awabi2048.ccsystem.api.gui.ResolvedMenuCapabilityAction
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponPlayerWorldCapabilityInvocationTest {
    @Test
    fun `player world capability entries retain their direct runtime contracts`() {
        val subject = Any()
        val world = Any()
        val invocations = listOf(
            playerWorldCapabilityInvocation(
                slot = 10,
                capability = capability(
                    "mwm-chanpon:player-world-item-away",
                    listOf(
                        MenuCapabilityTrigger.LEFT to MenuActionSafety.EXTERNAL_SIDE_EFFECT,
                        MenuCapabilityTrigger.RIGHT to MenuActionSafety.NAVIGATION_ONLY,
                        MenuCapabilityTrigger.SHIFT_LEFT to MenuActionSafety.REVERSIBLE,
                    ),
                ),
                attributes = mapOf("subject" to subject, "world" to world),
            ),
            playerWorldCapabilityInvocation(
                slot = 20,
                capability = capability(
                    "mwm-chanpon:player-world-creation",
                    listOf(
                        MenuCapabilityTrigger.LEFT to MenuActionSafety.REVERSIBLE,
                        MenuCapabilityTrigger.RIGHT to MenuActionSafety.REVERSIBLE,
                    ),
                ),
                attributes = mapOf("subject" to subject),
            ),
            playerWorldCapabilityInvocation(
                slot = 24,
                capability = capability(
                    "mwm-chanpon:player-world-summary",
                    emptyList(),
                ),
                attributes = mapOf("subject" to subject),
            ),
        )
        val observations = invocations.map { invocation ->
            MenuActionObservation(
                invocation.slot,
                if (invocation.capability.actionable) {
                    MenuInteraction.Capability(
                        capabilityId = invocation.capability.capabilityId,
                        arguments = invocation.arguments,
                        attributes = invocation.attributes,
                        acceptedClicks = invocation.capability.acceptedClicks,
                        safety = invocation.capability.safety,
                        safetyByClick = invocation.capability.safetyByClick,
                    )
                } else {
                    MenuInteraction.DisplayOnly
                },
            )
        }
        val definition = InventoryMenuDefinition(
            owner = "mwm-chanpon",
            id = "player-worlds",
            renderer = InventoryMenuRenderer { error("render is not part of this contract test") },
            actions = emptyMap(),
        )

        assertTrue(MenuContractValidator.validate(definition, observations).isEmpty())
        assertEquals(
            listOf(
                "mwm-chanpon:player-world-item-away",
                "mwm-chanpon:player-world-creation",
                "mwm-chanpon:player-world-summary",
            ),
            invocations.map { it.capability.capabilityId },
        )
        val worldInteraction =
            assertInstanceOf(MenuInteraction.Capability::class.java, observations[0].interaction)
        assertEquals(
            mapOf("subject" to subject, "world" to world),
            worldInteraction.attributes,
        )
        assertEquals(mapOf("subject" to subject),
            assertInstanceOf(MenuInteraction.Capability::class.java, observations[1].interaction).attributes)
        assertEquals(mapOf("subject" to subject), invocations[2].attributes)
        assertEquals(
            setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT),
            worldInteraction.acceptedClicks,
        )
        assertEquals(MenuActionSafety.EXTERNAL_SIDE_EFFECT, worldInteraction.safetyFor(ClickType.LEFT))
        assertEquals(MenuActionSafety.NAVIGATION_ONLY, worldInteraction.safetyFor(ClickType.RIGHT))
        assertEquals(MenuActionSafety.REVERSIBLE, worldInteraction.safetyFor(ClickType.SHIFT_LEFT))
        assertEquals(MenuActionSafety.REVERSIBLE,
            assertInstanceOf(MenuInteraction.Capability::class.java, observations[1].interaction).safety)
        assertEquals(0, invocations[2].capability.acceptedClicks.size)
        assertInstanceOf(MenuInteraction.DisplayOnly::class.java, observations[2].interaction)
    }

    private fun capability(
        capabilityId: String,
        actions: List<Pair<MenuCapabilityTrigger, MenuActionSafety>>,
    ): ResolvedMenuCapability = ResolvedMenuCapability(
        capabilityId = capabilityId,
        presentation = MenuCapabilityPresentation(
            GuiItemSpec(Material.STONE, GuiNameSpec.Empty, GuiLoreSpec.None, GuiElementRole.ACTION, 1),
        ),
        actions = actions.mapIndexed { index, (trigger, safety) ->
            ResolvedMenuCapabilityAction("action-$index", trigger, "クリック", safety)
        },
    )
}
