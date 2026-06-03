package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.ui.SubmissionAdminMenu
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.util.ItemTag
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class SubmissionAdminMenuExtension(
    private val menu: SubmissionAdminMenu
) : MenuExtension {
    override fun getId(): String = SubmissionAdminMenu.EXTENSION_ID

    override fun onRender(inventory: Inventory, player: Player, context: MenuExtensionContext) {
        if (context.menuType != "admin_menu") return
        if (mwm().playerPlatformResolver.isBedrock(player)) return
        inventory.setItem(MENU_SLOT, createItem(player))
    }

    override fun onClick(event: InventoryClickEvent, player: Player, context: MenuExtensionContext): Boolean {
        if (context.menuType != "admin_menu") return false
        val item = event.currentItem ?: return false
        if (ItemTag.getExtensionId(item) != getId()) return false
        menu.open(player)
        return true
    }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private fun createItem(player: Player): ItemStack {
        val item = ItemStack(Material.LIME_BANNER)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.entry.display"))
            meta.lore(CCSystem.getAPI().buildLore(i18nList(player, "admin_menu.entry.lore"), closingSeparator = true))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, getId())
        return item
    }

    private fun i18nComponent(player: Player?, key: String) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.submission.$key")

    private fun i18nList(player: Player?, key: String) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.submission.$key")

    companion object {
        private const val MENU_SLOT = 21
    }
}
