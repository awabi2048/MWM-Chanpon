package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuRenderer
import com.awabi2048.ccsystem.api.gui.MenuActionObservation
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuReversibleContract
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuContractValidator
import com.awabi2048.ccsystem.api.gui.MenuInteraction
import com.awabi2048.ccsystem.api.gui.ResolvedMenuCapability
import com.awabi2048.ccsystem.api.gui.ResolvedMenuCapabilityAction
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.mwmchanpon.service.EasyCreationReversibleProvider
import me.awabi2048.myworldmanager.service.MwmReversibleContracts
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponPlayerWorldCapabilityInvocationTest {
    @Test
    fun `player world capability entries retain their production restore contracts`() {
        val subject = Any()
        val world = Any()
        val invocations = listOf(
            playerWorldCapabilityInvocation(
                slot = 10,
                capability = capability(
                    "mwm-chanpon:player-world-item-current",
                    listOf(
                        action(
                            MenuCapabilityTrigger.SHIFT_LEFT,
                            MenuActionSafety.REVERSIBLE,
                            MwmReversibleContracts.displayOrder(),
                            ChanponReversibleActionClassification.Operation.CURRENT_PLAYER_WORLD_DISPLAY_ORDER,
                        ),
                    ),
                ),
                attributes = mapOf("subject" to subject, "world" to world),
            ),
            playerWorldCapabilityInvocation(
                slot = 19,
                capability = capability(
                    "mwm-chanpon:player-world-item-away",
                    listOf(
                        action(MenuCapabilityTrigger.LEFT, MenuActionSafety.EXTERNAL_SIDE_EFFECT),
                        action(MenuCapabilityTrigger.RIGHT, MenuActionSafety.NAVIGATION_ONLY),
                        action(
                            MenuCapabilityTrigger.SHIFT_LEFT,
                            MenuActionSafety.REVERSIBLE,
                            MwmReversibleContracts.displayOrder(),
                            ChanponReversibleActionClassification.Operation.AWAY_PLAYER_WORLD_DISPLAY_ORDER,
                        ),
                    ),
                ),
                attributes = mapOf("subject" to subject, "world" to world),
            ),
            playerWorldCapabilityInvocation(
                slot = 20,
                capability = capability(
                    "mwm-chanpon:player-world-creation",
                    listOf(
                        action(
                            MenuCapabilityTrigger.LEFT,
                            MenuActionSafety.REVERSIBLE,
                            MwmReversibleContracts.creationSession(),
                            ChanponReversibleActionClassification.Operation.STANDARD_CREATION_SESSION,
                        ),
                        action(
                            MenuCapabilityTrigger.RIGHT,
                            MenuActionSafety.REVERSIBLE,
                            EasyCreationReversibleProvider.contract(),
                            ChanponReversibleActionClassification.Operation.EASY_CREATION_START,
                        ),
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
                        reversibleContractByClick = invocation.capability.reversibleContractByClick,
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

        val providerOnlyViolations = MenuContractValidator.validate(definition, observations)
        assertEquals(4, providerOnlyViolations.size)
        assertEquals(
            setOf(
                "reversible provider is not registered: myworldmanager:world-display-order",
                "reversible provider is not registered: myworldmanager:creation-session",
                "reversible provider is not registered: mwm-chanpon:easy-creation",
            ),
            providerOnlyViolations.map { it.message }.toSet(),
        )
        assertEquals(
            listOf(
                "mwm-chanpon:player-world-item-current",
                "mwm-chanpon:player-world-item-away",
                "mwm-chanpon:player-world-creation",
                "mwm-chanpon:player-world-summary",
            ),
            invocations.map { it.capability.capabilityId },
        )
        val worldInteraction =
            assertInstanceOf(MenuInteraction.Capability::class.java, observations[1].interaction)
        assertEquals(
            mapOf("subject" to subject, "world" to world),
            worldInteraction.attributes,
        )
        assertEquals(mapOf("subject" to subject),
            assertInstanceOf(MenuInteraction.Capability::class.java, observations[2].interaction).attributes)
        assertEquals(mapOf("subject" to subject), invocations[3].attributes)
        assertEquals(
            setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT),
            worldInteraction.acceptedClicks,
        )
        assertEquals(MenuActionSafety.EXTERNAL_SIDE_EFFECT, worldInteraction.safetyFor(ClickType.LEFT))
        assertEquals(MenuActionSafety.NAVIGATION_ONLY, worldInteraction.safetyFor(ClickType.RIGHT))
        assertEquals(MenuActionSafety.REVERSIBLE, worldInteraction.safetyFor(ClickType.SHIFT_LEFT))
        assertEquals(MenuActionSafety.REVERSIBLE,
            assertInstanceOf(MenuInteraction.Capability::class.java, observations[0].interaction).safety)
        assertEquals(MenuActionSafety.REVERSIBLE,
            assertInstanceOf(MenuInteraction.Capability::class.java, observations[2].interaction).safety)
        assertEquals(0, invocations[3].capability.acceptedClicks.size)
        assertInstanceOf(MenuInteraction.DisplayOnly::class.java, observations[3].interaction)
    }

    private fun capability(
        capabilityId: String,
        actions: List<CapabilityAction>,
    ): ResolvedMenuCapability = ResolvedMenuCapability(
        capabilityId = capabilityId,
        presentation = MenuCapabilityPresentation(
            GuiItemSpec(Material.STONE, GuiNameSpec.Empty, GuiLoreSpec.None, GuiElementRole.ACTION, 1),
        ),
        actions = actions.mapIndexed { index, action ->
            action.classification?.let { classification ->
                ChanponReversibleActionClassification.validateActionContract(
                    classification,
                    action.reversibleContract?.providerId,
                    action.safety,
                )
            }
            ResolvedMenuCapabilityAction(
                "action-$index", action.trigger, "クリック", action.safety, action.reversibleContract,
            )
        },
    )

    private fun action(
        trigger: MenuCapabilityTrigger,
        safety: MenuActionSafety,
        reversibleContract: MenuReversibleContract? = null,
        classification: ChanponReversibleActionClassification.Operation? = null,
    ) = CapabilityAction(trigger, safety, reversibleContract, classification)

    private data class CapabilityAction(
        val trigger: MenuCapabilityTrigger,
        val safety: MenuActionSafety,
        val reversibleContract: MenuReversibleContract?,
        val classification: ChanponReversibleActionClassification.Operation?,
    )
}
