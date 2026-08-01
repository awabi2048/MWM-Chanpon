package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuActionSoundPolicy
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.ResolvedMenuCapability

/** ChanponがRuntimeへ渡す通常操作に安全区分の省略を許さない境界です。 */
internal fun chanponGestureAction(
    actionId: String,
    gesture: MenuGesture,
    label: String,
    payload: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
    safety: MenuActionSafety,
): GuiMenuActionIntent.GestureAction {
    require(safety != MenuActionSafety.UNSPECIFIED) {
        "Chanpon GUI action must declare MenuActionSafety: $actionId/$gesture"
    }
    return GuiMenuActionIntent.GestureAction(
        actionId = actionId,
        gesture = gesture,
        label = label,
        payload = payload,
        enabled = enabled,
        safety = safety,
    )
}

/** Chanpon公開Capabilityの操作にも安全区分を必須化します。 */
internal fun chanponCapabilityAction(
    id: String,
    trigger: MenuCapabilityTrigger,
    textProvider: MenuCapabilityActionTextProvider,
    availability: MenuCapabilityAvailability = MenuCapabilityAvailability { true },
    sounds: MenuActionSoundPolicy = MenuActionSoundPolicy(),
    handler: MenuCapabilityActionHandler,
    safety: MenuActionSafety,
): MenuCapabilityAction {
    require(safety != MenuActionSafety.UNSPECIFIED) {
        "Chanpon capability action must declare MenuActionSafety: $id/$trigger"
    }
    return MenuCapabilityAction(
        id = id,
        trigger = trigger,
        textProvider = textProvider,
        availability = availability,
        sounds = sounds,
        handler = handler,
        safety = safety,
    )
}

/** 外部Capabilityを表示へ接続する直前にも未分類操作を拒否します。 */
internal fun ResolvedMenuCapability.requireExplicitActionSafety(): ResolvedMenuCapability {
    require(actions.all { it.safety != MenuActionSafety.UNSPECIFIED }) {
        "Chanpon GUI capability must declare MenuActionSafety: $capabilityId"
    }
    return this
}
