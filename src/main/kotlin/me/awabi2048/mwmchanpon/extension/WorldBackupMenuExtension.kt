package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.backup.BackupEntry
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
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
import org.bukkit.plugin.Plugin
import java.time.format.DateTimeFormatter
import java.util.UUID

class WorldBackupMenuExtension(
    private val plugin: Plugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService
) : MenuExtension, Listener {
    override fun getId(): String = EXTENSION_ID

    override fun onRender(inventory: Inventory, player: Player, context: MenuExtensionContext) {
        if (context.menuType != "world_settings") return
        val worldData = context.get<WorldData>("worldData") ?: return
        if (!canManage(player, worldData)) return
        inventory.setItem(WORLD_MENU_SLOT, entryItem(player, worldData.uuid))
    }

    override fun onClick(event: InventoryClickEvent, player: Player, context: MenuExtensionContext): Boolean {
        val item = event.currentItem ?: return false
        if (ItemTag.getExtensionId(item) != EXTENSION_ID) return false
        val worldUuid = ItemTag.getWorldUuid(item) ?: return false
        if (ownedProductionWorld(player, worldUuid) == null) return true
        open(player, worldUuid)
        return true
    }

    fun open(player: Player, worldUuid: UUID, page: Int = 0) {
        val world = ownedProductionWorld(player, worldUuid) ?: return
        val backups = backupService.listBackupsForWorld(worldUuid)
        val pages = if (backups.isEmpty()) 1 else (backups.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, pages - 1)
        val holder = Holder(worldUuid, safePage)
        val inv = Bukkit.createInventory(holder, 54, i18nComponent(player, "owner_backup.title"))
        holder.inv = inv
        applyFrame(inv)
        if (safePage > 0) inv.setItem(0, navItem(player, false, safePage - 1))
        if (safePage < pages - 1) inv.setItem(8, navItem(player, true, safePage + 1))
        backups.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, backup ->
            inv.setItem(9 + index, backupItem(player, backup))
        }
        inv.setItem(45, backItem(player))
        inv.setItem(47, backupControlItem(player, worldUuid))
        inv.setItem(49, infoItem(player, world, backups.size, safePage + 1, pages))
        fill(inv)
        player.openInventory(inv)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        if (ownedProductionWorld(player, holder.worldUuid) == null) {
            player.closeInventory()
            return
        }
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> player.closeInventory()
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> open(player, holder.worldUuid, ItemTag.getTargetPage(item) ?: holder.page)
            TYPE_BACKUP_ITEM -> {
                val backup = findBackup(item) ?: return
                if (event.click.isLeftClick) openRestoreConfirm(player, backup, holder)
            }
            TYPE_CONTROL -> if (ItemTag.getString(item, ACTION_KEY) == ACTION_BACKUP) {
                val remaining = backupService.ownerBackupCooldownRemainingSeconds(holder.worldUuid)
                if (remaining > 0) {
                    player.sendMessage(i18nComponent(player, "message.owner_backup_cooldown", mapOf("remaining" to formatDuration(remaining))))
                    open(player, holder.worldUuid, holder.page)
                } else {
                    openBackupConfirm(player, holder)
                }
            }
        }
    }

    @EventHandler
    fun onBackupConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? BackupConfirmHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.source.worldUuid, holder.source.page)
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (ownedProductionWorld(player, holder.source.worldUuid) == null) {
                    player.closeInventory()
                    return
                }
                val remaining = backupService.ownerBackupCooldownRemainingSeconds(holder.source.worldUuid)
                if (remaining > 0) {
                    player.sendMessage(i18nComponent(player, "message.owner_backup_cooldown", mapOf("remaining" to formatDuration(remaining))))
                    open(player, holder.source.worldUuid, holder.source.page)
                    return
                }
                player.closeInventory()
                backupService.createOwnerWorldBackup(holder.source.worldUuid, player.uniqueId, player.name, "owner_manual")
                    .thenAccept { result ->
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            if (result.status == BackupOperationStatus.SUCCESS) {
                                player.sendMessage(i18nComponent(player, "message.backup_success"))
                            } else {
                                val cooldown = result.message.removePrefix("cooldown:").toLongOrNull()
                                if (cooldown != null) {
                                    player.sendMessage(i18nComponent(player, "message.owner_backup_cooldown", mapOf("remaining" to formatDuration(cooldown))))
                                } else {
                                    player.sendMessage(i18nComponent(player, "message.backup_failed"))
                                }
                            }
                        })
                    }
            }
        }
    }

    @EventHandler
    fun onRestoreConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? RestoreConfirmHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.source.worldUuid, holder.source.page)
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (ownedProductionWorld(player, holder.source.worldUuid) == null) {
                    player.closeInventory()
                    return
                }
                val backup = backupService.listBackupsForWorld(holder.source.worldUuid).firstOrNull { it.id == holder.backupId } ?: return
                player.closeInventory()
                backupService.restoreWorldBackup(backup, holder.source.worldUuid).thenAccept { result ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(i18nComponent(player, "message.restore_${result.status.name.lowercase()}"))
                    })
                }
            }
        }
    }

    private fun openBackupConfirm(player: Player, source: Holder) {
        val holder = BackupConfirmHolder(source.copy())
        val inv = Bukkit.createInventory(holder, 45, i18nComponent(player, "confirm.owner_backup.title"))
        holder.inv = inv
        applyFrame(inv)
        inv.setItem(22, centerItem(player, Material.CHEST, "confirm.owner_backup"))
        inv.setItem(20, button(player, true))
        inv.setItem(24, button(player, false))
        fill(inv)
        player.openInventory(inv)
    }

    private fun openRestoreConfirm(player: Player, backup: BackupEntry, source: Holder) {
        val holder = RestoreConfirmHolder(backup.id, source.copy())
        val inv = Bukkit.createInventory(holder, 45, i18nComponent(player, "confirm.restore.title"))
        holder.inv = inv
        applyFrame(inv)
        inv.setItem(22, centerItem(player, Material.RECOVERY_COMPASS, "confirm.restore"))
        inv.setItem(20, button(player, true))
        inv.setItem(24, button(player, false))
        fill(inv)
        player.openInventory(inv)
    }

    private fun entryItem(player: Player, worldUuid: UUID): ItemStack {
        val item = ItemStack(Material.CHEST)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "owner_backup.display"))
            meta.lore(lore(i18nList(player, "owner_backup.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, EXTENSION_ID)
        ItemTag.setWorldUuid(item, worldUuid)
        return item
    }

    private fun backupItem(player: Player, backup: BackupEntry): ItemStack {
        val item = ItemStack(materialFor(backup))
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "owner_backup.item.display", mapOf("id" to backup.id.take(18))))
            meta.lore(lore(i18nList(player, "owner_backup.item.lore", mapOf(
                "kind" to i18n(player, "kind.${backup.kind.i18nKey}"),
                "created" to backup.createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                "size" to formatBytes(backup.sizeBytes),
                "actor" to (backup.actorName ?: "system")
            ))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_BACKUP_ITEM)
        ItemTag.setString(item, BACKUP_ID_KEY, backup.id)
        return item
    }

    private fun backupControlItem(player: Player, worldUuid: UUID): ItemStack {
        val remaining = backupService.ownerBackupCooldownRemainingSeconds(worldUuid)
        val item = ItemStack(if (remaining > 0) Material.CLOCK else Material.CHEST)
        val key = if (remaining > 0) "owner_backup.cooldown" else "owner_backup.create"
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display", mapOf("remaining" to formatDuration(remaining))))
            meta.lore(lore(i18nList(player, "$key.lore", mapOf("remaining" to formatDuration(remaining)))))
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, ACTION_BACKUP)
        return item
    }

    private fun infoItem(player: Player, world: WorldData, count: Int, page: Int, pages: Int): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "owner_backup.info.display", mapOf("world" to world.name)))
            meta.lore(lore(i18nList(player, "owner_backup.info.lore", mapOf(
                "count" to count,
                "page" to page,
                "pages" to pages
            ))))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun centerItem(player: Player, material: Material, key: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(lore(i18nList(player, "$key.lore")))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun button(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        item.editMeta { it.displayName(i18nComponent(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel")) }
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
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
        item.editMeta { meta -> meta.displayName(Component.empty()); meta.isHideTooltip = true }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun findBackup(item: ItemStack): BackupEntry? {
        val id = ItemTag.getString(item, BACKUP_ID_KEY) ?: return null
        return backupService.listBackups().firstOrNull { it.id == id }
    }

    private fun ownedProductionWorld(player: Player, worldUuid: UUID): WorldData? {
        val world = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return null
        return if (canManage(player, world)) world else null
    }

    private fun canManage(player: Player, world: WorldData): Boolean {
        return storage.isProductionWorld(world.uuid) && world.owner == player.uniqueId
    }

    private fun materialFor(backup: BackupEntry): Material = when (backup.kind.storageValue) {
        "user_manual" -> Material.PLAYER_HEAD
        "admin_manual" -> Material.GOLDEN_HELMET
        "scheduled" -> Material.CLOCK
        "restore_point" -> Material.END_CRYSTAL
        else -> Material.RECOVERY_COMPASS
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1fKB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1fMB".format(mb)
        return "%.1fGB".format(mb / 1024.0)
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L + 59L) / 60L
        return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes.coerceAtLeast(1L)}m"
    }

    private fun i18n(player: Player?, key: String) = CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")
    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)
    private fun lore(lines: List<String>) = CCSystem.getAPI().buildLore(lines, closingSeparator = true)

    data class Holder(val worldUuid: UUID, val page: Int) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class BackupConfirmHolder(val source: Holder) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class RestoreConfirmHolder(val backupId: String, val source: Holder) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val ITEMS_PER_PAGE = 36
        private const val EXTENSION_ID = "chanpon.world_backup"
        private const val TYPE_BACKUP_ITEM = "chanpon_owner_backup_item"
        private const val TYPE_CONTROL = "chanpon_owner_backup_control"
        private const val BACKUP_ID_KEY = "chanpon_backup_id"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_BACKUP = "backup"
        private const val WORLD_MENU_SLOT = 52
    }
}
