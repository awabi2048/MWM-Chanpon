package me.awabi2048.mwmchanpon

import com.awabi2048.ccsystem.api.gui.MenuReversibleOpaqueState
import com.awabi2048.ccsystem.api.gui.MenuReversibleRestoreInteractionContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateRestoreContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateSnapshot
import org.bukkit.entity.Player

internal fun restoreContextForTest(
    player: Player,
    capture: MenuReversibleStateCaptureContext,
    state: MenuReversibleOpaqueState,
): MenuReversibleStateRestoreContext {
    val interactionConstructor = MenuReversibleRestoreInteractionContext::class.java.declaredConstructors.single()
    interactionConstructor.isAccessible = true
    val interaction = interactionConstructor.newInstance(
        capture.interaction.slot,
        capture.interaction.click,
        capture.interaction.actionId,
        capture.interaction.capabilityId,
        capture.interaction.contract,
        capture.interaction.revision,
        capture.interaction.arguments,
        capture.interaction.routePayload,
    ) as MenuReversibleRestoreInteractionContext
    val snapshot = snapshotForTest(state)
    return MenuReversibleStateRestoreContext(player, capture.route, capture.runId, interaction, snapshot)
}

internal fun snapshotForTest(state: MenuReversibleOpaqueState): MenuReversibleStateSnapshot {
    val companion = MenuReversibleStateSnapshot::class.java.getField("Companion").get(null)
    return companion.javaClass.getMethod("capture", MenuReversibleOpaqueState::class.java)
        .invoke(companion, state) as MenuReversibleStateSnapshot
}
