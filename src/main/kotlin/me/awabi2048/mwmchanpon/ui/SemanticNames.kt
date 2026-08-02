package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec

internal fun fixedName(text: String, style: GuiNameStyle = GuiNameStyle.DEFAULT): GuiNameSpec =
    GuiNameSpec.FixedLabel(CCSystem.getAPI().getGuiElementService().name(text, style))

internal fun targetIdentityName(text: String, style: GuiNameStyle = GuiNameStyle.DEFAULT): GuiNameSpec =
    GuiNameSpec.TargetIdentity(CCSystem.getAPI().getGuiElementService().name(text, style))

internal fun opaqueName(text: String, style: GuiNameStyle = GuiNameStyle.DEFAULT): GuiNameSpec =
    GuiNameSpec.Opaque(CCSystem.getAPI().getGuiElementService().name(text, style))

internal fun framedLore(lines: List<GuiLoreLine>, frame: GuiLoreFrame): GuiLoreSpec =
    GuiLoreSpec.FramedBlocks(listOf(GuiLoreBlock(lines)), frame)
