package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiConfirmationLayout
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiPagedListLayout
import com.awabi2048.ccsystem.api.gui.GuiSettingsLayout
import com.awabi2048.ccsystem.api.gui.GuiThreeChoiceLayout
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Keeps Chanpon UI calls local while delegating shared layout and visual construction to CC-System. */
internal object ChanponGuiElements {
    private val elements
        get() = CCSystem.getAPI().getGuiElementService()
    private val layouts
        get() = CCSystem.getAPI().getGuiLayoutService()

    fun applyStandardFrame(inventory: Inventory) {
        layouts.applyStandardFrame(inventory)
    }

    fun confirmationLayout(): GuiConfirmationLayout = layouts.confirmation45()

    fun pagedListLayout(): GuiPagedListLayout = layouts.pagedList54()

    fun settingsLayout(): GuiSettingsLayout = layouts.settings54()

    fun threeChoiceLayout(): GuiThreeChoiceLayout = layouts.threeChoice45()

    fun backSlot(inventorySize: Int): Int = when (inventorySize) {
        45 -> layouts.backSlot45()
        54 -> layouts.backSlot54()
        else -> error("Unsupported inventory size for a centered back button: $inventorySize")
    }

    fun applyConfirmationFrame(inventory: Inventory) {
        applyStandardFrame(inventory)
    }

    fun setConfirmationItems(
        inventory: Inventory,
        centerItem: ItemStack,
        confirmItem: ItemStack,
        cancelItem: ItemStack
    ) {
        val layout = confirmationLayout()
        inventory.setItem(layout.previewSlot, centerItem)
        inventory.setItem(layout.confirmSlot, confirmItem)
        inventory.setItem(layout.cancelSlot, cancelItem)
    }

    fun setPagedListNavigation(
        inventory: Inventory,
        previousItem: ItemStack?,
        nextItem: ItemStack?,
        backItem: ItemStack?,
        infoItem: ItemStack?
    ) {
        val layout = pagedListLayout()
        previousItem?.let { inventory.setItem(layout.previousPageSlot, it) }
        nextItem?.let { inventory.setItem(layout.nextPageSlot, it) }
        backItem?.let { inventory.setItem(layout.backSlot, it) }
        infoItem?.let { inventory.setItem(layout.infoSlot, it) }
    }

    fun setSettingsFooter(inventory: Inventory, backItem: ItemStack?, infoItem: ItemStack?) {
        val layout = settingsLayout()
        backItem?.let { inventory.setItem(layout.backSlot, it) }
        infoItem?.let { inventory.setItem(layout.infoSlot, it) }
    }

    fun fill(inventory: Inventory, material: Material = Material.GRAY_STAINED_GLASS_PANE) {
        elements.fillEmpty(inventory, decorationSpec(material))
    }

    fun decoration(material: Material): ItemStack = elements.item(decorationSpec(material))

    fun back(name: String): ItemStack = elements.backItem(name)

    fun confirm(name: String, confirm: Boolean): ItemStack = elements.confirmItem(name, confirm)

    fun navigation(name: String, next: Boolean): ItemStack = elements.item(
        GuiItemSpec(
            material = if (next) Material.ARROW else Material.SPECTRAL_ARROW,
            name = GuiNameSpec.Text(name, GuiNameStyle.DEFAULT),
            lore = GuiLoreSpec.None,
            role = GuiElementRole.NAVIGATION,
            amount = 1
        )
    )

    private fun decorationSpec(material: Material): GuiItemSpec = GuiItemSpec(
        material = material,
        name = GuiNameSpec.Empty,
        lore = GuiLoreSpec.None,
        role = GuiElementRole.DECORATION,
        amount = 1
    )
}
