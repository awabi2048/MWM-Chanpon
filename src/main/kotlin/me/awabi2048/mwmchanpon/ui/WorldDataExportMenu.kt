package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.WorldDataExportService
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class WorldDataExportMenu(
    private val plugin: MWMChanponPlugin,
    private val exportService: WorldDataExportService
) : Listener {
    private var parentMenuOpener: ((Player) -> Unit)? = null

    fun setParentMenuOpener(opener: (Player) -> Unit) {
        parentMenuOpener = opener
    }

    fun open(player: Player, page: Int = 0) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        val worlds = exportService.productionWorlds()
        val pages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, pages - 1)
        val holder = Holder(safePage)
        val inv = Bukkit.createInventory(holder, 54, i18nComponent(player, "export_menu.title"))
        holder.inv = inv
        applyFrame(inv)
        if (safePage > 0) inv.setItem(0, navItem(player, false, safePage - 1))
        if (safePage < pages - 1) inv.setItem(8, navItem(player, true, safePage + 1))
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            inv.setItem(9 + index, worldItem(player, world))
        }
        inv.setItem(45, backItem(player))
        inv.setItem(49, infoItem(player, worlds.size, safePage + 1, pages))
        inv.setItem(51, bulkExportItem(player))
        fill(inv)
        player.openInventory(inv)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.cancelWithDebug("WorldDataExportMenu.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    parentMenuOpener?.invoke(player) ?: player.closeInventory()
                }
            }
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> open(player, ItemTag.getTargetPage(item) ?: holder.page)
            ItemTag.TYPE_GUI_WORLD_ITEM -> {
                val uuid = ItemTag.getWorldUuid(item) ?: return
                player.closeInventory()
                exportService.exportWorld(uuid).thenAccept { result ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(resultMessage(player, result.success, result.file?.name))
                    })
                }
            }
            TYPE_CONTROL -> if (ItemTag.getString(item, ACTION_KEY) == ACTION_BULK_EXPORT) {
                player.closeInventory()
                exportService.exportAll().thenAccept { result ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(resultMessage(player, result.success, result.file?.name))
                    })
                }
            }
        }
    }

    private fun worldItem(player: Player, world: WorldData): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.world.display", mapOf("world" to world.name)))
            meta.lore(lore(i18nList(player, "export_menu.world.lore", mapOf(
                "owner" to (Bukkit.getOfflinePlayer(world.owner).name ?: "-"),
                "created" to world.createdAt
            ))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun bulkExportItem(player: Player): ItemStack {
        val item = ItemStack(Material.DISPENSER)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.bulk.display"))
            meta.lore(lore(i18nList(player, "export_menu.bulk.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, ACTION_BULK_EXPORT)
        return item
    }

    private fun infoItem(player: Player, count: Int, page: Int, pages: Int): ItemStack {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.info.display"))
            meta.lore(lore(i18nList(player, "export_menu.info.lore", mapOf("count" to count, "page" to page, "pages" to pages))))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun navItem(player: Player, next: Boolean, page: Int): ItemStack {
        val item = ItemStack(if (next) Material.ARROW else Material.SPECTRAL_ARROW)
        item.editMeta { it.displayName(i18nComponent(player, if (next) "common.next" else "common.prev")) }
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        ItemTag.setTargetPage(item, page)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { it.displayName(i18nComponent(player, "common.back")) }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun applyFrame(inv: Inventory) {
        val black = decoration(Material.BLACK_STAINED_GLASS_PANE)
        for (slot in 0..8) inv.setItem(slot, black)
        for (slot in inv.size - 9 until inv.size) inv.setItem(slot, black)
    }

    private fun fill(inv: Inventory) {
        val gray = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0 until inv.size) if (inv.getItem(slot) == null) inv.setItem(slot, gray)
    }

    private fun decoration(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun resultMessage(player: Player, success: Boolean, fileName: String?): Component {
        return i18nComponent(player, if (success) "message.export_success" else "message.export_failed", mapOf("file" to (fileName ?: "-")))
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    private fun lore(lines: List<String>) = CCSystem.getAPI().buildLore(lines, closingSeparator = true)

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)

    private class Holder(val page: Int) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val ITEMS_PER_PAGE = 36
        private const val TYPE_CONTROL = "chanpon_export_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_BULK_EXPORT = "bulk_export"
    }
}
