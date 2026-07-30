package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiConfirmationLayout
import com.awabi2048.ccsystem.api.gui.GuiPagedListLayout
import com.awabi2048.ccsystem.api.gui.GuiSettingsLayout
import com.awabi2048.ccsystem.api.gui.GuiThreeChoiceLayout

/** Keeps Chanpon UI calls local while delegating shared layout and visual construction to CC-System. */
internal object ChanponGuiElements {
    private val layouts
        get() = CCSystem.getAPI().getGuiLayoutService()

    fun confirmationLayout(): GuiConfirmationLayout = layouts.confirmation45()

    fun pagedListLayout(): GuiPagedListLayout = layouts.pagedList54()

    fun settingsLayout(): GuiSettingsLayout = layouts.settings54()

    fun threeChoiceLayout(): GuiThreeChoiceLayout = layouts.threeChoice45()

    fun backSlot(inventorySize: Int): Int = when (inventorySize) {
        45 -> layouts.backSlot45()
        54 -> layouts.backSlot54()
        else -> error("Unsupported inventory size for a centered back button: $inventorySize")
    }
}
