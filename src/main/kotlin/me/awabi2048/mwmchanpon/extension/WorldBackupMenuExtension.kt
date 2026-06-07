package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import me.awabi2048.mwmchanpon.backup.BackupEntry
import me.awabi2048.mwmchanpon.backup.BackupDisplay
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.backup.RestoreOperationResult
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ManualRollbackService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WorldBackupMenuExtension(
    private val plugin: Plugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService,
    private val automationService: BackupAutomationService,
    private val manualRollbackService: ManualRollbackService
) : MenuExtension, Listener {
    private val pendingBackupNameInputs = ConcurrentHashMap<UUID, Holder>()

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
            ItemTag.TYPE_GUI_RETURN -> returnToWorldSettings(player, holder.worldUuid)
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> open(player, holder.worldUuid, ItemTag.getTargetPage(item) ?: holder.page)
            TYPE_BACKUP_ITEM -> {
                val backup = findBackup(item) ?: return
                if (event.click.isLeftClick) openRestoreConfirm(player, backup, holder)
            }
            TYPE_CONTROL -> if (ItemTag.getString(item, ACTION_KEY) == ACTION_BACKUP) {
                if (automationService.isManualOperationBlocked(player)) {
                    player.sendMessage(i18nComponent(player, "message.manual_operation_blocked"))
                    open(player, holder.worldUuid, holder.page)
                    return
                }
                val remaining = backupService.ownerBackupCooldownRemainingSeconds(holder.worldUuid)
                if (remaining > 0) {
                    player.sendMessage(i18nComponent(player, "message.owner_backup_cooldown", mapOf("remaining" to BackupDisplay.formatDuration(remaining))))
                    open(player, holder.worldUuid, holder.page)
                } else {
                    openBackupNameInput(player, holder)
                }
            }
        }
    }

    @Suppress("UnstableApiUsage")
    @EventHandler
    fun onBackupNameInput(event: PlayerCustomClickEvent) {
        val identifier = event.identifier
        if (identifier != Key.key("mwmchanpon:backup/owner_name_submit") &&
            identifier != Key.key("mwmchanpon:backup/owner_name_cancel")
        ) {
            return
        }
        val player = (event.commonConnection as? PlayerGameConnection)?.player ?: return
        val source = pendingBackupNameInputs.remove(player.uniqueId) ?: return

        if (identifier == Key.key("mwmchanpon:backup/owner_name_cancel")) {
            open(player, source.worldUuid, source.page)
            return
        }

        val name = BackupDisplay.normalizeManualName(event.getDialogResponseView()?.getText("backup_name").orEmpty())
        openBackupConfirm(player, source, name)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingBackupNameInputs.remove(event.player.uniqueId)
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
                if (automationService.isManualOperationBlocked(player)) {
                    player.sendMessage(i18nComponent(player, "message.manual_operation_blocked"))
                    open(player, holder.source.worldUuid, holder.source.page)
                    return
                }
                val remaining = backupService.ownerBackupCooldownRemainingSeconds(holder.source.worldUuid)
                if (remaining > 0) {
                    player.sendMessage(i18nComponent(player, "message.owner_backup_cooldown", mapOf("remaining" to BackupDisplay.formatDuration(remaining))))
                    open(player, holder.source.worldUuid, holder.source.page)
                    return
                }
                player.closeInventory()
                backupService.createOwnerWorldBackup(holder.source.worldUuid, player.uniqueId, player.name, "owner_manual", holder.backupName)
                    .thenAccept { result ->
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            if (result.status == BackupOperationStatus.SUCCESS) {
                                player.sendMessage(i18nComponent(player, "message.backup_success"))
                            } else {
                                val cooldown = result.message.removePrefix("cooldown:").toLongOrNull()
                                if (cooldown != null) {
                                    player.sendMessage(i18nComponent(player, "message.owner_backup_cooldown", mapOf("remaining" to BackupDisplay.formatDuration(cooldown))))
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
                if (automationService.isManualOperationBlocked(player)) {
                    player.sendMessage(i18nComponent(player, "message.manual_operation_blocked"))
                    return
                }
                val rollbackCooldown = backupService.manualRollbackCooldownRemainingSeconds(listOf(holder.source.worldUuid))
                if (rollbackCooldown > 0L) {
                    player.sendMessage(i18nComponent(player, "message.rollback_cooldown", mapOf("remaining" to BackupDisplay.formatDuration(rollbackCooldown))))
                    return
                }
                manualRollbackService.schedule(
                    player,
                    { backupService.restoreWorldBackup(backup, holder.source.worldUuid) },
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

    @Suppress("UnstableApiUsage")
    private fun openBackupNameInput(player: Player, source: Holder) {
        pendingBackupNameInputs[player.uniqueId] = source.copy()
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(i18nComponent(player, "owner_backup.name_input.title"))
                        .body(listOf(DialogBody.plainMessage(i18nComponent(player, "owner_backup.name_input.body"))))
                        .inputs(
                            listOf(
                                DialogInput.text("backup_name", i18nComponent(player, "owner_backup.name_input.label"))
                                    .maxLength(MAX_BACKUP_NAME_LENGTH)
                                    .initial("")
                                    .build()
                            )
                        )
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        ActionButton.create(
                            i18nComponent(player, "owner_backup.name_input.submit"),
                            null,
                            100,
                            DialogAction.customClick(Key.key("mwmchanpon:backup/owner_name_submit"), null)
                        ),
                        ActionButton.create(
                            i18nComponent(player, "owner_backup.name_input.cancel"),
                            null,
                            200,
                            DialogAction.customClick(Key.key("mwmchanpon:backup/owner_name_cancel"), null)
                        )
                    )
                )
        }
        player.showDialog(dialog)
    }

    private fun openBackupConfirm(player: Player, source: Holder, backupName: String) {
        val holder = BackupConfirmHolder(source.copy(), backupName)
        val inv = Bukkit.createInventory(holder, 45, i18nComponent(player, "confirm.owner_backup.title"))
        holder.inv = inv
        applyFrame(inv)
        inv.setItem(22, centerItem(player, Material.CHEST, "confirm.owner_backup", mapOf("name" to backupName)))
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
            meta.displayName(i18nComponent(player, "owner_backup.item.display", mapOf("name" to BackupDisplay.displayName(backup))))
            meta.lore(lore(i18nList(player, "owner_backup.item.lore", mapOf(
                "created" to BackupDisplay.formatCreatedAt(backup.createdAt),
                "actor" to BackupDisplay.actorName(backup)
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
            meta.displayName(i18nComponent(player, "$key.display", mapOf("remaining" to BackupDisplay.formatDuration(remaining))))
            meta.lore(lore(i18nList(player, "$key.lore", mapOf("remaining" to BackupDisplay.formatDuration(remaining)))))
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

    private fun centerItem(player: Player, material: Material, key: String, placeholders: Map<String, Any> = emptyMap()): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display", placeholders))
            meta.lore(lore(i18nList(player, "$key.lore", placeholders)))
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

    private fun returnToWorldSettings(player: Player, worldUuid: UUID) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
        if (worldData == null) {
            player.closeInventory()
            return
        }
        val mwm = org.bukkit.plugin.java.JavaPlugin.getPlugin(MyWorldManager::class.java)
        val session = mwm.settingsSessionManager.getSession(player)
        mwm.worldSettingsGui.open(
            player,
            worldData,
            session?.showBackButton,
            session?.isPlayerWorldFlow,
            session?.parentShowBackButton
        )
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)
    private fun lore(lines: List<String>) = CCSystem.getAPI().buildLore(lines, closingSeparator = true)

    data class Holder(val worldUuid: UUID, val page: Int) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class BackupConfirmHolder(val source: Holder, val backupName: String) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class RestoreConfirmHolder(val backupId: String, val source: Holder) : WorldSettingsInventoryHolder {
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
        private const val MAX_BACKUP_NAME_LENGTH = 32
    }
}
