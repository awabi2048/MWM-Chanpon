package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuActionSoundPolicy
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailabilityProvider
import com.awabi2048.ccsystem.api.gui.MenuAvailabilityResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import net.kyori.adventure.text.Component
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.MenuReversibleContract
import com.awabi2048.ccsystem.api.gui.ResolvedMenuCapability
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification

/** ChanponがRuntimeへ渡す通常操作に安全区分の省略を許さない境界です。 */
internal fun chanponGestureAction(
    actionId: String,
    gesture: MenuGesture,
    label: String,
    payload: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
    safety: MenuActionSafety,
    reversibleContract: MenuReversibleContract? = null,
    classification: ChanponReversibleActionClassification.Operation? = null,
): GuiMenuActionIntent.GestureAction {
    require(safety != MenuActionSafety.UNSPECIFIED) {
        "Chanpon GUI action must declare MenuActionSafety: $actionId/$gesture"
    }
    require((safety == MenuActionSafety.REVERSIBLE) == (reversibleContract != null)) {
        "Chanpon REVERSIBLE action must have exactly one restore contract: $actionId/$gesture"
    }
    if (safety == MenuActionSafety.REVERSIBLE) {
        requireNotNull(classification) { "Chanpon REVERSIBLE action must declare a classified operation: $actionId/$gesture" }
    }
    classification?.let { operation ->
        ChanponReversibleActionClassification.validateActionContract(operation, reversibleContract?.providerId, safety)
    }
    return GuiMenuActionIntent.GestureAction(
        actionId = actionId,
        gesture = gesture,
        label = label,
        payload = payload,
        enabled = enabled,
        safety = safety,
        reversibleContract = reversibleContract,
    )
}

internal fun chanponCycleAction(
    actionId: String,
    label: String,
    payload: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
    safety: MenuActionSafety,
    reversibleContract: MenuReversibleContract? = null,
    classification: ChanponReversibleActionClassification.Operation? = null,
): GuiMenuActionIntent.PlainLeftRight {
    chanponGestureAction(
        actionId, MenuGesture.PLAIN_LEFT_RIGHT, label, payload, enabled,
        safety, reversibleContract, classification,
    )
    return GuiMenuActionIntent.PlainLeftRight(
        actionId, label, payload, enabled, safety, reversibleContract,
    )
}

/** Chanpon公開Capabilityの操作にも安全区分を必須化します。 */
internal fun chanponCapabilityAction(
    id: String,
    trigger: MenuCapabilityTrigger,
    textProvider: MenuCapabilityActionTextProvider,
    availability: MenuCapabilityAvailability = optionalAvailability { true },
    sounds: MenuActionSoundPolicy = MenuActionSoundPolicy(),
    handler: MenuCapabilityActionHandler,
    safety: MenuActionSafety,
    reversibleContract: MenuReversibleContract? = null,
    classification: ChanponReversibleActionClassification.Operation? = null,
): MenuCapabilityAction {
    require(safety != MenuActionSafety.UNSPECIFIED) {
        "Chanpon capability action must declare MenuActionSafety: $id/$trigger"
    }
    require((safety == MenuActionSafety.REVERSIBLE) == (reversibleContract != null)) {
        "Chanpon REVERSIBLE capability must have exactly one restore contract: $id/$trigger"
    }
    if (safety == MenuActionSafety.REVERSIBLE) {
        requireNotNull(classification) { "Chanpon REVERSIBLE capability must declare a classified operation: $id/$trigger" }
    }
    classification?.let { operation ->
        ChanponReversibleActionClassification.validateActionContract(operation, reversibleContract?.providerId, safety)
    }
    return MenuCapabilityAction(
        id = id,
        trigger = trigger,
        textProvider = textProvider,
        availability = availability,
        sounds = sounds,
        handler = handler,
        safety = safety,
        reversibleContract = reversibleContract,
    )
}

internal fun optionalAvailability(
    predicate: (MenuCapabilityContext) -> Boolean,
): MenuCapabilityAvailability = MenuCapabilityAvailability.reasoned(
    MenuCapabilityAvailabilityProvider { context ->
        if (predicate(context)) MenuAvailabilityResult.Available else MenuAvailabilityResult.UnavailableUnknown
    },
)

internal fun reasonedAvailability(
    reason: (MenuCapabilityContext) -> Component,
    predicate: (MenuCapabilityContext) -> Boolean,
): MenuCapabilityAvailability = MenuCapabilityAvailability.reasoned(
    MenuCapabilityAvailabilityProvider { context ->
        if (predicate(context)) MenuAvailabilityResult.Available else MenuAvailabilityResult.Unavailable(reason(context))
    },
)

/** 外部Capabilityを表示へ接続する直前にも未分類操作を拒否します。 */
internal fun ResolvedMenuCapability.requireExplicitActionSafety(): ResolvedMenuCapability {
    require(actions.all { it.safety != MenuActionSafety.UNSPECIFIED }) {
        "Chanpon GUI capability must declare MenuActionSafety: $capabilityId"
    }
    return this
}
