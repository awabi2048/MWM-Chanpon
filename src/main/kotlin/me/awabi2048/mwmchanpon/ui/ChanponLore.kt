package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import net.kyori.adventure.text.Component

internal object ChanponLore {
    fun build(lines: List<String>, closingSeparator: Boolean = true): List<Component> {
        return CCSystem.getAPI().getGuiElementService().autoLore(lines, closingSeparator)
    }
}
