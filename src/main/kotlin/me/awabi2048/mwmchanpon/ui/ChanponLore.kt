package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import net.kyori.adventure.text.Component

internal object ChanponLore {
    fun build(lines: List<GuiLoreLine>, closingSeparator: Boolean = true): List<Component> {
        return CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(
                lines,
                if (closingSeparator) GuiLoreFrame.BOTH else GuiLoreFrame.TOP
            )
        )
    }
}
