package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.service.ArchiveService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
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
import java.util.UUID

class WorldDataManagementMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService,
    private val backupListMenu: BackupListMenu,
    private val archiveService: ArchiveService
) : Listener {
    private var parentMenuOpener: ((Player) -> Unit)? = null

    fun setParentMenuOpener(opener: (Player) -> Unit) {
        parentMenuOpener = opener
    }

    fun open(player: Player, page: Int = 0) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        val worlds = productionWorlds()
        val pages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, pages - 1)
        val holder = Holder(safePage)
        val layout = ChanponGuiElements.pagedListLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "world_data.title"))
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
        inv.setItem(47, controlItem(player, Material.CHEST, "world_data.bulk_backup", ACTION_BULK_BACKUP))
        inv.setItem(51, controlItem(player, Material.RECOVERY_COMPASS, "world_data.bulk_rollback", ACTION_BULK_ROLLBACK))
        inv.setItem(53, controlItem(player, Material.ENDER_CHEST, "world_data.annual_archive", ACTION_ANNUAL_ARCHIVE))
        fill(inv)
        player.openInventory(inv)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.cancelWithDebug("WorldDataManagementMenu.onClick: menu_click")
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
                if (event.click.isLeftClick) openBackupConfirm(player, uuid, holder.page)
                if (event.click.isRightClick) {
                    pushSelfRoute(player, holder.page)
                    backupListMenu.openWorld(player, uuid)
                }
            }
            TYPE_CONTROL -> when (ItemTag.getString(item, ACTION_KEY)) {
                ACTION_BULK_BACKUP -> openBulkBackupConfirm(player, holder.page)
                ACTION_BULK_ROLLBACK -> openBulkRollbackConfirm(player, holder.page)
                ACTION_ANNUAL_ARCHIVE -> {
                    player.closeInventory()
                    AnnualArchiveFlow(plugin, archiveService).start(player)
                }
            }
        }
    }

    @EventHandler
    fun onBackupConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? BackupConfirmHolder ?: return
        event.cancelWithDebug("WorldDataManagementMenu.onBackupConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.page)
            ItemTag.TYPE_GUI_CONFIRM -> {
                player.closeInventory()
                val future = if (holder.worldUuid == null) {
                    backupService.createAllBackup(productionWorlds().map { it.uuid }, BackupKind.ADMIN_MANUAL, player.uniqueId, player.name, "admin_bulk")
                } else {
                    backupService.createWorldBackup(holder.worldUuid, BackupKind.ADMIN_MANUAL, player.uniqueId, player.name, "admin_world")
                }
                future.thenAccept { result ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(i18nComponent(player, if (result.status == BackupOperationStatus.SUCCESS) "message.backup_success" else "message.backup_failed"))
                    })
                }
            }
        }
    }

    @EventHandler
    fun onBulkRollbackConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? BulkRollbackConfirmHolder ?: return
        event.cancelWithDebug("WorldDataManagementMenu.onBulkRollbackConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.page)
            ItemTag.TYPE_GUI_CONFIRM -> {
                pushSelfRoute(player, holder.page)
                backupListMenu.openAll(player)
            }
        }
    }

    private fun pushSelfRoute(player: Player, page: Int) {
        mwm().menuRouteHistory.pushCustom(player, "chanpon.world_data:$page") { target ->
            open(target, page)
            true
        }
    }

    private fun openBackupConfirm(player: Player, worldUuid: UUID, page: Int) {
        openConfirm(player, worldUuid, page, "confirm.backup_world")
    }

    private fun openBulkBackupConfirm(player: Player, page: Int) {
        openConfirm(player, null, page, "confirm.backup_all")
    }

    private fun openBulkRollbackConfirm(player: Player, page: Int) {
        val holder = BulkRollbackConfirmHolder(page)
        val layout = ChanponGuiElements.confirmationLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "confirm.rollback_all.title"))
        holder.inv = inv
        applyFrame(inv)
        ChanponGuiElements.setConfirmationItems(
            inv,
            centerItem(player, Material.RECOVERY_COMPASS, "confirm.rollback_all"),
            button(player, true),
            button(player, false)
        )
        fill(inv)
        player.openInventory(inv)
    }

    private fun openConfirm(player: Player, worldUuid: UUID?, page: Int, key: String) {
        val holder = BackupConfirmHolder(worldUuid, page)
        val layout = ChanponGuiElements.confirmationLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "$key.title"))
        holder.inv = inv
        applyFrame(inv)
        ChanponGuiElements.setConfirmationItems(
            inv,
            centerItem(player, Material.CHEST, key),
            button(player, true),
            button(player, false)
        )
        fill(inv)
        player.openInventory(inv)
    }

    private fun productionWorlds(): List<WorldData> {
        return MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
            .sortedByDescending { it.createdAt }
    }

    private fun worldItem(player: Player, world: WorldData): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "world_data.world.display", mapOf("world" to world.name)))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(
                    GuiLoreLine.Data(i18n(player, "world_data.world.owner_label"), Bukkit.getOfflinePlayer(world.owner).name ?: "-", "§f"),
                    GuiLoreLine.Data(i18n(player, "world_data.world.created_label"), world.createdAt, "§f"),
                    GuiLoreLine.Data(i18n(player, "world_data.world.backups_label"), backupService.listBackupsForWorld(world.uuid).size, "§f")
                )),
                GuiLoreBlock(listOf(
                    GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.left"), i18n(player, "world_data.world.backup_action")),
                    GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.right"), i18n(player, "world_data.world.list_action"))
                ))
            )))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun applyFrame(inv: Inventory) {
        ChanponGuiElements.applyStandardFrame(inv)
    }

    private fun fill(inv: Inventory) {
        ChanponGuiElements.fill(inv)
    }

    private fun controlItem(player: Player, material: Material, key: String, action: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(structuredBlocks(listOf(GuiLoreBlock(buildList {
                add(GuiLoreLine.Text(i18n(player, "$key.description")))
                if (action == ACTION_ANNUAL_ARCHIVE) {
                    add(GuiLoreLine.Warning(i18n(player, "$key.warning")))
                }
            }))))
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun centerItem(player: Player, material: Material, key: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore").map(GuiLoreLine::Text)))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun button(player: Player, confirm: Boolean): ItemStack {
        val item = ChanponGuiElements.confirm(i18n(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel"), confirm)
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
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

    private fun infoItem(player: Player, count: Int, page: Int, pages: Int): ItemStack {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "world_data.info.display"))
            meta.lore(ChanponLore.build(i18nList(player, "world_data.info.lore", mapOf("count" to count, "page" to page, "pages" to pages)).map(GuiLoreLine::Text)))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
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

    private class BackupConfirmHolder(val worldUuid: UUID?, val page: Int) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class BulkRollbackConfirmHolder(val page: Int) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val ITEMS_PER_PAGE = 36
        private const val TYPE_CONTROL = "chanpon_world_data_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_BULK_BACKUP = "bulk_backup"
        private const val ACTION_BULK_ROLLBACK = "bulk_rollback"
        private const val ACTION_ANNUAL_ARCHIVE = "annual_archive"
    }
}
