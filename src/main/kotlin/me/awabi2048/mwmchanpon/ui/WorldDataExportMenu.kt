package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import java.util.UUID
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

    fun open(player: Player, page: Int = 0, selected: Set<UUID> = emptySet()) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        val worlds = exportService.productionWorlds()
        val pages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, pages - 1)
        val exportableIds = worlds.mapTo(mutableSetOf()) { it.uuid }
        val safeSelected = selected.intersect(exportableIds)
        val holder = Holder(safePage, safeSelected)
        val layout = ChanponGuiElements.pagedListLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "export_menu.title"))
        holder.inv = inventory
        ChanponGuiElements.applyStandardFrame(inventory)
        ChanponGuiElements.setPagedListNavigation(
            inventory,
            if (safePage > 0) navItem(player, false, safePage - 1) else null,
            if (safePage < pages - 1) navItem(player, true, safePage + 1) else null,
            backItem(player),
            infoItem(player, worlds.size, safeSelected.size, safePage + 1, pages)
        )
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            inventory.setItem(layout.itemSlots[index], worldItem(player, world, world.uuid in safeSelected))
        }
        inventory.setItem(47, selectAllItem(player))
        inventory.setItem(48, clearSelectionItem(player))
        inventory.setItem(51, exportSelectionItem(player, safeSelected.size))
        ChanponGuiElements.fill(inventory)
        ManagedMenuPresenter.open(player, inventory)
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
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT ->
                open(player, ItemTag.getTargetPage(item) ?: holder.page, holder.selected)
            ItemTag.TYPE_GUI_WORLD_ITEM -> {
                val uuid = ItemTag.getWorldUuid(item) ?: return
                val updated = holder.selected.toMutableSet()
                if (!updated.add(uuid)) updated.remove(uuid)
                open(player, holder.page, updated)
            }
            TYPE_CONTROL -> when (ItemTag.getString(item, ACTION_KEY)) {
                ACTION_SELECT_ALL -> open(
                    player,
                    holder.page,
                    exportService.productionWorlds().mapTo(mutableSetOf()) { it.uuid }
                )
                ACTION_CLEAR_SELECTION -> open(player, holder.page, emptySet())
                ACTION_EXPORT_SELECTION -> {
                    if (holder.selected.isEmpty()) return
                    ManagedMenuPresenter.close(player)
                    exportService.exportStandaloneWorld(holder.selected).thenAccept { result ->
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.sendMessage(resultMessage(player, result.success, result.file?.name))
                        })
                    }
                }
            }
        }
    }

    private fun worldItem(player: Player, world: WorldData, selected: Boolean): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.world.display", mapOf("world" to world.name)))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(
                    GuiLoreLine.Data(
                        i18n(player, "export_menu.world.owner_label"),
                        Bukkit.getOfflinePlayer(world.owner).name ?: "-",
                        "§f"
                    ),
                    GuiLoreLine.Data(i18n(player, "export_menu.world.created_label"), world.createdAt, "§f")
                )),
                GuiLoreBlock(listOf(
                    GuiLoreLine.Option(
                        i18n(player, if (selected) "export_menu.world.selected" else "export_menu.world.not_selected"),
                        selected,
                        "§a",
                        "§7"
                    )
                )),
                GuiLoreBlock(listOf(
                    GuiLoreActions.singleClick(
                        mwm().languageManager,
                        player,
                        i18n(player, "export_menu.world.action")
                    )
                ))
            )))
            meta.setEnchantmentGlintOverride(selected)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun selectAllItem(player: Player): ItemStack =
        controlItem(
            player,
            Material.CHEST,
            "export_menu.select_all",
            ACTION_SELECT_ALL
        )

    private fun clearSelectionItem(player: Player): ItemStack =
        controlItem(
            player,
            Material.BARRIER,
            "export_menu.clear",
            ACTION_CLEAR_SELECTION
        )

    private fun controlItem(
        player: Player,
        material: Material,
        key: String,
        action: String
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "$key.description")))),
                GuiLoreBlock(listOf(
                    GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "$key.action"))
                ))
            )))
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun exportSelectionItem(player: Player, selectedCount: Int): ItemStack {
        val enabled = selectedCount > 0
        val item = ItemStack(if (enabled) Material.DISPENSER else Material.GRAY_DYE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.execute.display"))
            val status = mutableListOf<GuiLoreLine>(
                GuiLoreLine.Data(
                    i18n(player, "export_menu.execute.selected_label"),
                    selectedCount,
                    "§f"
                )
            )
            if (!enabled) status += GuiLoreLine.Warning(i18n(player, "export_menu.execute.disabled"))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(status),
                GuiLoreBlock(listOf(
                    GuiLoreActions.singleClick(
                        mwm().languageManager,
                        player,
                        i18n(player, "export_menu.execute.action")
                    )
                ))
            )))
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, ACTION_EXPORT_SELECTION)
        return item
    }

    private fun infoItem(
        player: Player,
        count: Int,
        selectedCount: Int,
        page: Int,
        pages: Int
    ): ItemStack {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.info.display"))
            meta.lore(
                ChanponLore.build(
                    i18nList(
                        player,
                        "export_menu.info.lore",
                        mapOf("count" to count, "selected" to selectedCount, "page" to page, "pages" to pages)
                    ).map(GuiLoreLine::Text)
                )
            )
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

    private fun resultMessage(player: Player, success: Boolean, fileName: String?): Component =
        i18nComponent(
            player,
            if (success) "message.export_success" else "message.export_failed",
            mapOf("file" to (fileName ?: "-"))
        )

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

    private class Holder(val page: Int, val selected: Set<UUID>) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val ITEMS_PER_PAGE = 36
        private const val TYPE_CONTROL = "chanpon_export_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_SELECT_ALL = "select_all"
        private const val ACTION_CLEAR_SELECTION = "clear_selection"
        private const val ACTION_EXPORT_SELECTION = "export_selection"
    }
}
