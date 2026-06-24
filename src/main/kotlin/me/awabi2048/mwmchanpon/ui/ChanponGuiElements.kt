package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiConfirmationLayout
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import me.awabi2048.myworldmanager.util.ItemTag
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

    fun fill(inventory: Inventory, material: Material = Material.GRAY_STAINED_GLASS_PANE) {
        elements.fillEmpty(inventory, decorationSpec(material))
    }

    fun decoration(material: Material): ItemStack {
        val item = elements.item(decorationSpec(material))
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

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
