package me.awabi2048.mwmchanpon.ui
import me.awabi2048.mwmchanpon.ui.ManagedMenuPresenter

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.WorldDataExportService
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.GuiLoreActions
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
        val layout = ChanponGuiElements.pagedListLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "export_menu.title"))
        holder.inv = inv
        applyFrame(inv)
        ChanponGuiElements.setPagedListNavigation(
            inv,
            if (safePage > 0) navItem(player, false, safePage - 1) else null,
            if (safePage < pages - 1) navItem(player, true, safePage + 1) else null,
            backItem(player),
            infoItem(player, worlds.size, safePage + 1, pages)
        )
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            inv.setItem(layout.itemSlots[index], worldItem(player, world))
        }
        inv.setItem(51, bulkExportItem(player))
        fill(inv)
        ManagedMenuPresenter.open(player, inv)
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
                    parentMenuOpener?.invoke(player) ?: ManagedMenuPresenter.close(player)
                }
            }
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> open(player, ItemTag.getTargetPage(item) ?: holder.page)
            ItemTag.TYPE_GUI_WORLD_ITEM -> {
                val uuid = ItemTag.getWorldUuid(item) ?: return
                ManagedMenuPresenter.close(player)
                exportService.exportWorld(uuid).thenAccept { result ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(resultMessage(player, result.success, result.file?.name))
                    })
                }
            }
            TYPE_CONTROL -> if (ItemTag.getString(item, ACTION_KEY) == ACTION_BULK_EXPORT) {
                ManagedMenuPresenter.close(player)
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
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(
                    GuiLoreLine.Data(i18n(player, "export_menu.world.owner_label"), Bukkit.getOfflinePlayer(world.owner).name ?: "-", "§f"),
                    GuiLoreLine.Data(i18n(player, "export_menu.world.created_label"), world.createdAt, "§f")
                )),
                GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "export_menu.world.action"))))
            )))
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
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "export_menu.bulk.description")))),
                GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "export_menu.bulk.action"))))
            )))
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
            meta.lore(ChanponLore.build(i18nList(player, "export_menu.info.lore", mapOf("count" to count, "page" to page, "pages" to pages)).map(GuiLoreLine::Text)))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun navItem(player: Player, next: Boolean, page: Int): ItemStack {
        val item = ChanponGuiElements.navigation(i18n(player, if (next) "common.next" else "common.prev"), next)
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        ItemTag.setTargetPage(item, page)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ChanponGuiElements.back(i18n(player, "common.back"))
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun applyFrame(inv: Inventory) {
        ChanponGuiElements.applyStandardFrame(inv)
    }

    private fun fill(inv: Inventory) {
        ChanponGuiElements.fill(inv)
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun resultMessage(player: Player, success: Boolean, fileName: String?): Component {
        return i18nComponent(player, if (success) "message.export_success" else "message.export_failed", mapOf("file" to (fileName ?: "-")))
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    private fun structuredBlocks(blocks: List<GuiLoreBlock>): List<Component> =
        CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))

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
