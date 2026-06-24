package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupEntry
import me.awabi2048.mwmchanpon.backup.BackupDisplay
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupScope
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.backup.RestoreOperationResult
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ManualRollbackService
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.util.UUID

class BackupListMenu(
    private val plugin: MWMChanponPlugin,
    private val backupService: BackupService
) : Listener {
    private var parentMenuOpener: ((Player) -> Unit)? = null
    private var automationService: BackupAutomationService? = null
    private var manualRollbackService: ManualRollbackService? = null

    fun setParentMenuOpener(opener: (Player) -> Unit) {
        parentMenuOpener = opener
    }

    fun setAutomationService(service: BackupAutomationService) {
        automationService = service
    }

    fun setManualRollbackService(service: ManualRollbackService) {
        manualRollbackService = service
    }

    fun openAll(player: Player, page: Int = 0) {
        open(player, BackupListMode.ALL, null, page)
    }

    fun openWorld(player: Player, worldUuid: UUID, page: Int = 0) {
        open(player, BackupListMode.WORLD, worldUuid, page)
    }

    private fun open(player: Player, mode: BackupListMode, worldUuid: UUID?, page: Int) {
        val backups = when (mode) {
            BackupListMode.ALL -> backupService.listBackups().filter { it.scope == BackupScope.ALL }
            BackupListMode.WORLD -> worldUuid?.let(backupService::listBackupsForWorld).orEmpty()
        }
        val pages = if (backups.isEmpty()) 1 else (backups.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, pages - 1)
        val holder = Holder(mode, worldUuid, safePage)
        val inv = Bukkit.createInventory(holder, 54, i18nComponent(player, "backup_list.title"))
        holder.inv = inv
        applyFrame(inv)
        if (safePage > 0) inv.setItem(0, navItem(player, false, safePage - 1))
        if (safePage < pages - 1) inv.setItem(8, navItem(player, true, safePage + 1))
        backups.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, backup ->
            inv.setItem(9 + index, backupItem(player, backup))
        }
        inv.setItem(45, backItem(player))
        inv.setItem(49, infoItem(player, backups.size, safePage + 1, pages))
        fill(inv)
        player.openInventory(inv)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.cancelWithDebug("BackupListMenu.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    parentMenuOpener?.invoke(player) ?: player.closeInventory()
                }
            }
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> open(player, holder.mode, holder.worldUuid, ItemTag.getTargetPage(item) ?: holder.page)
            TYPE_BACKUP_ITEM -> {
                val id = ItemTag.getString(item, BACKUP_ID_KEY) ?: return
                val backup = backupService.listBackups().firstOrNull { it.id == id } ?: return
                when (event.click) {
                    ClickType.MIDDLE -> openDeleteConfirm(player, backup, 1, holder)
                    else -> if (event.click.isLeftClick) openRestoreConfirm(player, backup, holder)
                }
            }
        }
    }

    @EventHandler
    fun onRestoreConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? RestoreConfirmHolder ?: return
        event.cancelWithDebug("BackupListMenu.onRestoreConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.source.mode, holder.source.worldUuid, holder.source.page)
            ItemTag.TYPE_GUI_CONFIRM -> {
                player.closeInventory()
                if (automationService?.isManualOperationBlocked(player) == true) {
                    player.sendMessage(i18nComponent(player, "message.manual_operation_blocked"))
                    return
                }
                val backup = backupService.listBackups().firstOrNull { it.id == holder.backupId } ?: return
                val targetWorlds = restoreTargets(backup, holder.source)
                val rollbackCooldown = backupService.manualRollbackCooldownRemainingSeconds(targetWorlds)
                if (rollbackCooldown > 0L) {
                    player.sendMessage(i18nComponent(player, "message.rollback_cooldown", mapOf("remaining" to BackupDisplay.formatDuration(rollbackCooldown))))
                    return
                }
                val rollbackService = manualRollbackService
                if (rollbackService == null) {
                    executeRestore(player, backup, holder.source)
                    return
                }
                rollbackService.schedule(
                    player,
                    {
                        if (holder.source.mode == BackupListMode.WORLD && holder.source.worldUuid != null) {
                            backupService.restoreWorldBackup(backup, holder.source.worldUuid)
                        } else {
                            backupService.restoreBackup(backup)
                        }
                    },
                    { result ->
                        if (result.status == BackupOperationStatus.SUCCESS || result.status == BackupOperationStatus.RECOVERED) {
                            backupService.markManualRollbackExecuted(result.restoredWorlds)
                        }
                        sendRestoreResult(player, result)
                    }
                )
            }
        }
    }

    private fun executeRestore(player: Player, backup: BackupEntry, source: Holder) {
        val targetWorlds = restoreTargets(backup, source)
        val rollbackCooldown = backupService.manualRollbackCooldownRemainingSeconds(targetWorlds)
        if (rollbackCooldown > 0L) {
            player.sendMessage(i18nComponent(player, "message.rollback_cooldown", mapOf("remaining" to BackupDisplay.formatDuration(rollbackCooldown))))
            return
        }
        val future = if (source.mode == BackupListMode.WORLD && source.worldUuid != null) {
            backupService.restoreWorldBackup(backup, source.worldUuid)
        } else {
            backupService.restoreBackup(backup)
        }
        future.thenAccept { result ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (result.status == BackupOperationStatus.SUCCESS || result.status == BackupOperationStatus.RECOVERED) {
                    backupService.markManualRollbackExecuted(result.restoredWorlds)
                }
                sendRestoreResult(player, result)
            })
        }
    }

    private fun sendRestoreResult(player: Player, result: RestoreOperationResult) {
        player.sendMessage(i18nComponent(player, "message.restore_${result.status.name.lowercase()}"))
        if (result.status == BackupOperationStatus.SUCCESS) return
        player.sendMessage(Component.text("要因: ${restoreReason(result.message)}", NamedTextColor.RED))
    }

    private fun restoreReason(message: String): String = when (message) {
        "world is not included in backup" -> "このバックアップに対象ワールドが含まれていません。"
        "backup is locked" -> "このバックアップは別の処理で使用中です。"
        "no production worlds" -> "対象となる本番用ワールドがありません。"
        "world is locked" -> "対象ワールドは別のバックアップまたはロールバック処理中です。"
        "core api unavailable" -> "MyWorldManagerのAPIを取得できません。"
        "spawn world is not configured or unavailable" -> "退避先のスポーンワールドが未設定、または利用できません。"
        "failed to unload worlds" -> "対象ワールドのアンロードに失敗しました。"
        "failed to create pre rollback backup" -> "ロールバック前の退避バックアップ作成に失敗しました。"
        "restore failed" -> "ワールドデータの復元に失敗しました。"
        "restore failed; recovered original state" -> "復元に失敗しましたが、実行前の状態へ戻しました。"
        "restore failed; recovery also failed" -> "復元に失敗し、実行前の状態への復旧にも失敗しました。"
        else -> message
    }

    private fun restoreTargets(backup: BackupEntry, source: Holder): List<UUID> {
        return if (source.mode == BackupListMode.WORLD && source.worldUuid != null) {
            listOf(source.worldUuid)
        } else {
            backup.worldUuids
        }
    }

    @EventHandler
    fun onDeleteConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? DeleteConfirmHolder ?: return
        event.cancelWithDebug("BackupListMenu.onDeleteConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.source.mode, holder.source.worldUuid, holder.source.page)
            ItemTag.TYPE_GUI_CONFIRM -> {
                val backup = backupService.listBackups().firstOrNull { it.id == holder.backupId } ?: return
                if (holder.stage == 1) {
                    openDeleteConfirm(player, backup, 2, holder.source)
                } else {
                    val deleted = backupService.deleteBackup(backup)
                    player.sendMessage(i18nComponent(player, if (deleted) "message.delete_success" else "message.delete_failed"))
                    open(player, holder.source.mode, holder.source.worldUuid, holder.source.page)
                }
            }
        }
    }

    private fun backupItem(player: Player, backup: BackupEntry): ItemStack {
        val item = ItemStack(materialFor(backup.kind))
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "backup_list.item.display", mapOf("name" to BackupDisplay.displayName(backup))))
            meta.lore(lore(i18nList(player, "backup_list.item.lore", mapOf(
                "kind" to i18n(player, "kind.${backup.kind.i18nKey}"),
                "created" to BackupDisplay.formatCreatedAt(backup.createdAt),
                "worlds" to backup.worldUuids.size,
                "size" to formatBytes(backup.sizeBytes),
                "actor" to BackupDisplay.actorName(backup)
            ))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_BACKUP_ITEM)
        ItemTag.setString(item, BACKUP_ID_KEY, backup.id)
        return item
    }

    private fun openRestoreConfirm(player: Player, backup: BackupEntry, source: Holder) {
        val holder = RestoreConfirmHolder(backup.id, source.copy())
        val layout = ChanponGuiElements.confirmationLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "confirm.restore.title"))
        holder.inv = inv
        applyFrame(inv)
        ChanponGuiElements.setConfirmationItems(
            inv,
            centerItem(player, Material.CHEST, "confirm.restore"),
            button(player, true),
            button(player, false)
        )
        fill(inv)
        player.openInventory(inv)
    }

    private fun openDeleteConfirm(player: Player, backup: BackupEntry, stage: Int, source: Holder) {
        val holder = DeleteConfirmHolder(backup.id, stage, source.copy())
        val layout = ChanponGuiElements.confirmationLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "confirm.delete$stage.title"))
        holder.inv = inv
        applyFrame(inv)
        ChanponGuiElements.setConfirmationItems(
            inv,
            centerItem(player, Material.BARRIER, "confirm.delete$stage"),
            button(player, true),
            button(player, false)
        )
        fill(inv)
        player.openInventory(inv)
    }

    private fun materialFor(kind: BackupKind): Material = when (kind) {
        BackupKind.USER_MANUAL -> Material.PLAYER_HEAD
        BackupKind.ADMIN_MANUAL -> Material.GOLDEN_HELMET
        BackupKind.SCHEDULED -> Material.CLOCK
        BackupKind.RESTORE_POINT -> Material.END_CRYSTAL
        BackupKind.PRE_ROLLBACK -> Material.RECOVERY_COMPASS
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
            meta.displayName(i18nComponent(player, "backup_list.info.display"))
            meta.lore(lore(i18nList(player, "backup_list.info.lore", mapOf("count" to count, "page" to page, "pages" to pages))))
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
        val item = ChanponGuiElements.confirm(i18n(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel"), confirm)
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1fKB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1fMB".format(mb)
        return "%.1fGB".format(mb / 1024.0)
    }

    private fun i18n(player: Player?, key: String) = CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")
    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)
    private fun lore(lines: List<String>) = ChanponLore.build(lines)

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)

    enum class BackupListMode { ALL, WORLD }

    data class Holder(val mode: BackupListMode, val worldUuid: UUID?, val page: Int) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class RestoreConfirmHolder(val backupId: String, val source: Holder) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class DeleteConfirmHolder(val backupId: String, val stage: Int, val source: Holder) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val ITEMS_PER_PAGE = 36
        private const val TYPE_BACKUP_ITEM = "chanpon_backup_item"
        private const val BACKUP_ID_KEY = "chanpon_backup_id"
    }
}
