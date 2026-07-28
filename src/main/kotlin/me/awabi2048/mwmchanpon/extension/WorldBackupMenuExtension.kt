package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.backup.BackupEntry
import me.awabi2048.mwmchanpon.backup.BackupDisplay
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.backup.RestoreOperationResult
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ManualRollbackService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.ui.ChanponLore
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class WorldBackupMenuExtension(
    private val plugin: Plugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService,
    private val automationService: BackupAutomationService,
    private val manualRollbackService: ManualRollbackService
) : MenuExtension {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_OWNER_BACKUPS,
                renderer = { context -> render(context.player, source(context.route)) },
                actions = mapOf(
                    ACTION_RUNTIME_BACK to MenuActionHandler(::back),
                    ACTION_RUNTIME_PAGE to MenuActionHandler(::changePage),
                    ACTION_RUNTIME_RESTORE to MenuActionHandler(::selectBackup),
                    ACTION_RUNTIME_CREATE to MenuActionHandler(::createBackup),
                ),
            ),
        )
    }

    override fun getId(): String = EXTENSION_ID

    override fun onRender(items: Map<Int, ItemStack>, player: Player, context: MenuExtensionContext): Map<Int, ItemStack> {
        if (context.menuType != "world_settings") return items
        if (context.get<Boolean>("isProtected") == true) return items
        val worldData = context.get<WorldData>("worldData") ?: return items
        if (!canManage(player, worldData)) return items
        return items + (WORLD_MENU_SLOT to entryItem(player, worldData.uuid))
    }

    override fun onClick(click: ClickType, item: ItemStack, player: Player, context: MenuExtensionContext): Boolean {
        if (ItemTag.getExtensionId(item) != EXTENSION_ID) return false
        val worldUuid = ItemTag.getWorldUuid(item) ?: return false
        if (ownedProductionWorld(player, worldUuid) == null) return true
        return open(player, worldUuid)
    }

    fun open(player: Player, worldUuid: UUID, page: Int = 0): Boolean {
        if (ownedProductionWorld(player, worldUuid) == null) return false
        return runtime.navigate(player, route(worldUuid, page, currentRequest(player)))
    }

    private fun render(player: Player, source: Holder): InventoryMenuView {
        val world = ownedProductionWorld(player, source.worldUuid)
            ?: return InventoryMenuView(9, i18nComponent(player, "owner_backup.title"), emptyList())
        val backups = backupService.listBackupsForWorld(source.worldUuid)
        val pages = if (backups.isEmpty()) 1 else (backups.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = source.page.coerceIn(0, pages - 1)
        val layout = me.awabi2048.mwmchanpon.ui.ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += MenuElement(
                layout.previousPageSlot,
                navItem(player, false, safePage - 1),
                GuiElementRole.NAVIGATION,
                ACTION_RUNTIME_PAGE,
                payload(source.copy(page = safePage - 1)),
            )
        }
        if (safePage < pages - 1) {
            elements += MenuElement(
                layout.nextPageSlot,
                navItem(player, true, safePage + 1),
                GuiElementRole.NAVIGATION,
                ACTION_RUNTIME_PAGE,
                payload(source.copy(page = safePage + 1)),
            )
        }
        elements += MenuElement(
            layout.backSlot,
            backItem(player),
            GuiElementRole.BACK,
            ACTION_RUNTIME_BACK,
            payload(source),
        )
        elements += MenuElement(
            layout.infoSlot,
            infoItem(player, world, backups.size, safePage + 1, pages),
            GuiElementRole.CONTENT,
        )
        backups.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, backup ->
            elements += MenuElement(
                layout.itemSlots[index],
                backupItem(player, backup),
                GuiElementRole.ACTION,
                ACTION_RUNTIME_RESTORE,
                payload(source.copy(page = safePage)) + (BACKUP_ID_KEY to backup.id),
            )
        }
        elements += MenuElement(
            47,
            backupControlItem(player, source.worldUuid),
            GuiElementRole.ACTION,
            ACTION_RUNTIME_CREATE,
            payload(source.copy(page = safePage)),
        )
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "owner_backup.title"),
            elements,
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun changePage(context: MenuActionContext): MenuActionResult {
        val source = source(context.payload)
        return MenuActionResult.Success(
            MenuUpdate.Replace(route(source.worldUuid, source.page, source.request)),
        )
    }

    private fun selectBackup(context: MenuActionContext): MenuActionResult {
        if (!context.click.isLeftClick) return MenuActionResult.Ignored
        val source = source(context.payload)
        val backupId = context.payload[BACKUP_ID_KEY] ?: return MenuActionResult.Rejected()
        val backup = backupService.listBackupsForWorld(source.worldUuid)
            .firstOrNull { it.id == backupId }
            ?: return MenuActionResult.Rejected()
        openRestoreConfirm(context.player, backup, source)
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun createBackup(context: MenuActionContext): MenuActionResult {
        val source = source(context.payload)
        if (automationService.isManualOperationBlocked(context.player)) {
            context.player.sendMessage(i18nComponent(context.player, "message.manual_operation_blocked"))
            return MenuActionResult.Rejected()
        }
        val remaining = backupService.ownerBackupCooldownRemainingSeconds(source.worldUuid)
        if (remaining > 0) {
            context.player.sendMessage(
                i18nComponent(
                    context.player,
                    "message.owner_backup_cooldown",
                    mapOf("remaining" to BackupDisplay.formatDuration(remaining)),
                ),
            )
            return MenuActionResult.Rejected()
        }
        openBackupNameInput(context.player, source)
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun executeOwnerBackup(player: Player, source: Holder, backupName: String) {
        if (ownedProductionWorld(player, source.worldUuid) == null) return
        if (automationService.isManualOperationBlocked(player)) {
            player.sendMessage(i18nComponent(player, "message.manual_operation_blocked"))
            open(player, source.worldUuid, source.page)
            return
        }
        val remaining = backupService.ownerBackupCooldownRemainingSeconds(source.worldUuid)
        if (remaining > 0) {
            player.sendMessage(
                i18nComponent(
                    player,
                    "message.owner_backup_cooldown",
                    mapOf("remaining" to BackupDisplay.formatDuration(remaining)),
                ),
            )
            open(player, source.worldUuid, source.page)
            return
        }
        backupService.createOwnerWorldBackup(
            source.worldUuid,
            player.uniqueId,
            player.name,
            "owner_manual",
            backupName,
        ).thenAccept { result ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (result.status == BackupOperationStatus.SUCCESS) {
                    player.sendMessage(i18nComponent(player, "message.backup_success"))
                } else {
                    val cooldown = result.message.removePrefix("cooldown:").toLongOrNull()
                    if (cooldown != null) {
                        player.sendMessage(
                            i18nComponent(
                                player,
                                "message.owner_backup_cooldown",
                                mapOf("remaining" to BackupDisplay.formatDuration(cooldown)),
                            ),
                        )
                    } else {
                        player.sendMessage(i18nComponent(player, "message.backup_failed"))
                    }
                }
            })
        }
    }

    private fun executeRestore(player: Player, backup: BackupEntry, source: Holder) {
        if (ownedProductionWorld(player, source.worldUuid) == null) return
        if (automationService.isManualOperationBlocked(player)) {
            player.sendMessage(i18nComponent(player, "message.manual_operation_blocked"))
            return
        }
        val rollbackCooldown =
            backupService.manualRollbackCooldownRemainingSeconds(listOf(source.worldUuid))
        if (rollbackCooldown > 0L) {
            player.sendMessage(
                i18nComponent(
                    player,
                    "message.rollback_cooldown",
                    mapOf("remaining" to BackupDisplay.formatDuration(rollbackCooldown)),
                ),
            )
            return
        }
        val preflightFailure = backupService.restorePreflightFailure(backup, listOf(source.worldUuid))
        if (preflightFailure != null) {
            player.sendMessage(
                Component.text("理由: ${restoreReason(preflightFailure)}", NamedTextColor.RED),
            )
            return
        }
        manualRollbackService.schedule(
            player,
            { backupService.restoreWorldBackup(backup, source.worldUuid) },
            { result ->
                if (result.status == BackupOperationStatus.SUCCESS ||
                    result.status == BackupOperationStatus.RECOVERED
                ) {
                    backupService.markManualRollbackExecuted(result.restoredWorlds)
                }
                sendRestoreResult(player, result)
            },
        )
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
        "world is not a production world" -> "対象ワールドは本番用ワールドではありません。"
        "world data missing" -> "対象ワールドの管理データが見つかりません。"
        "backup world archive is missing or unreadable" -> "バックアップのワールドデータが存在しないか、読み取れません。"
        "world directory is not writable" -> "復元先のワールドディレクトリへ書き込めません。"
        "failed to unload worlds" -> "対象ワールドのアンロードに失敗しました。"
        "failed to create pre rollback backup" -> "ロールバック前の退避バックアップ作成に失敗しました。"
        "restore failed" -> "ワールドデータの復元に失敗しました。"
        "restore failed; recovered original state" -> "復元に失敗しましたが、実行前の状態へ戻しました。"
        "restore failed; recovery also failed" -> "復元に失敗し、実行前の状態への復旧にも失敗しました。"
        else -> message
    }

    private fun openBackupNameInput(player: Player, source: Holder) {
        val sourceSnapshot = source.copy()
        CCSystem.getAPI().getMenuDialogService().show(
            player,
            MenuDialogRequest(
                owner = "mwmchanpon",
                id = "owner-backup-name",
                title = i18nComponent(player, "owner_backup.name_input.title"),
                body = listOf(i18nComponent(player, "owner_backup.name_input.body")),
                inputs = listOf(
                    MenuDialogInput.Text(
                        "backup_name",
                        i18nComponent(player, "owner_backup.name_input.label"),
                        maxLength = MAX_BACKUP_NAME_LENGTH,
                    ),
                ),
                confirm = MenuDialogButton(
                    i18nComponent(player, "owner_backup.name_input.submit"),
                    MenuDialogHandler { target, response ->
                        val name = BackupDisplay.normalizeManualName(response.textValue("backup_name"))
                        openBackupConfirm(target, sourceSnapshot, name)
                        MenuActionResult.Success(MenuUpdate.Close)
                    },
                ),
                cancel = MenuDialogButton(
                    i18nComponent(player, "owner_backup.name_input.cancel"),
                    MenuDialogHandler { target, _ ->
                        open(target, sourceSnapshot.worldUuid, sourceSnapshot.page)
                        MenuActionResult.Success(MenuUpdate.Close)
                    },
                ),
            ),
        )
    }

    private fun openBackupConfirm(player: Player, source: Holder, backupName: String) {
        val sourceSnapshot = source.copy()
        mwm().confirmationMenuGui.openSimple(
            player = player,
            menuId = "owner-backup-confirmation",
            title = i18nComponent(player, "confirm.owner_backup.title"),
            bodyLines = i18nList(
                player,
                "confirm.owner_backup.lore",
                mapOf("name" to backupName),
            ).map(Component::text),
            confirmLabel = Component.text(i18n(player, "confirm.button.confirm")),
            cancelLabel = Component.text(i18n(player, "confirm.button.cancel")),
            onConfirm = { executeOwnerBackup(player, sourceSnapshot, backupName) },
        )
    }

    private fun openRestoreConfirm(player: Player, backup: BackupEntry, source: Holder) {
        val sourceSnapshot = source.copy()
        mwm().confirmationMenuGui.openSimple(
            player = player,
            menuId = "restore-confirmation",
            title = i18nComponent(player, "confirm.restore.title"),
            bodyLines = i18nList(player, "confirm.restore.lore").map(Component::text),
            confirmLabel = Component.text(i18n(player, "confirm.button.confirm")),
            cancelLabel = Component.text(i18n(player, "confirm.button.cancel")),
            onConfirm = { executeRestore(player, backup, sourceSnapshot) },
        )
    }

    private fun entryItem(player: Player, worldUuid: UUID): ItemStack {
        val item = ItemStack(Material.CHEST)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "owner_backup.display"))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "owner_backup.description")))),
                GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "owner_backup.action"))))
            )))
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
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(
                    GuiLoreLine.Data(i18n(player, "owner_backup.item.created_label"), BackupDisplay.formatCreatedAt(backup.createdAt), "§f"),
                    GuiLoreLine.Data(i18n(player, "owner_backup.item.actor_label"), BackupDisplay.actorName(backup), "§f")
                )),
                GuiLoreBlock(listOf(
                    GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.left"), i18n(player, "owner_backup.item.rollback_action"))
                ))
            )))
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
            meta.lore(if (remaining > 0) {
                structuredBlocks(listOf(GuiLoreBlock(listOf(
                    GuiLoreLine.Text(i18n(player, "$key.description", mapOf("remaining" to BackupDisplay.formatDuration(remaining))))
                ))))
            } else {
                structuredBlocks(listOf(
                    GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "$key.description")))),
                    GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "$key.action"))))
                ))
            })
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, ACTION_BACKUP)
        return item
    }

    private fun infoItem(player: Player, world: WorldData, count: Int, page: Int, pages: Int): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "owner_backup.info.display", mapOf("world" to world.name)))
            meta.lore(ChanponLore.build(i18nList(player, "owner_backup.info.lore", mapOf(
                "count" to count,
                "page" to page,
                "pages" to pages
            )).map(GuiLoreLine::Text)))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun navItem(player: Player, next: Boolean, page: Int): ItemStack {
        val item = me.awabi2048.mwmchanpon.ui.ChanponGuiElements.navigation(
            i18n(player, if (next) "common.next" else "common.prev"),
            next
        )
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        ItemTag.setTargetPage(item, page)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = me.awabi2048.mwmchanpon.ui.ChanponGuiElements.back(i18n(player, "common.back"))
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun decoration(material: Material): ItemStack {
        return me.awabi2048.mwmchanpon.ui.ChanponGuiElements.decoration(material)
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

    private fun route(worldUuid: UUID, page: Int, request: WorldSettingsMenuRequest): MenuRoute {
        return MenuRoute(
            RUNTIME_OWNER,
            ROUTE_OWNER_BACKUPS,
            payload(Holder(worldUuid, page, request)),
        )
    }

    private fun payload(source: Holder): Map<String, String> = mapOf(
        PAYLOAD_WORLD_UUID to source.worldUuid.toString(),
        PAYLOAD_PAGE to source.page.toString(),
        PAYLOAD_SHOW_BACK to source.request.showBackButton.toString(),
        PAYLOAD_PLAYER_FLOW to source.request.isPlayerWorldFlow?.toString().orEmpty(),
        PAYLOAD_PARENT_BACK to source.request.parentShowBackButton?.toString().orEmpty(),
    )

    private fun source(route: MenuRoute): Holder = source(route.payload)

    private fun source(payload: Map<String, String>): Holder {
        val worldUuid = payload[PAYLOAD_WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID(0L, 0L)
        return Holder(
            worldUuid,
            payload[PAYLOAD_PAGE]?.toIntOrNull() ?: 0,
            WorldSettingsMenuRequest(
                payload[PAYLOAD_SHOW_BACK].toBoolean(),
                payload[PAYLOAD_PLAYER_FLOW]?.takeIf(String::isNotEmpty)?.toBoolean(),
                payload[PAYLOAD_PARENT_BACK]?.takeIf(String::isNotEmpty)?.toBoolean(),
            ),
        )
    }

    private fun currentRequest(player: Player): WorldSettingsMenuRequest {
        val session = org.bukkit.plugin.java.JavaPlugin.getPlugin(MyWorldManager::class.java)
            .settingsSessionManager
            .getSession(player)
        return WorldSettingsMenuRequest(
            session?.showBackButton ?: false,
            session?.isPlayerWorldFlow,
            session?.parentShowBackButton
        )
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)
    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)
    private fun structuredBlocks(blocks: List<GuiLoreBlock>): List<Component> =
        CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))
    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    data class Holder(val worldUuid: UUID, val page: Int, val request: WorldSettingsMenuRequest)

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
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val ROUTE_OWNER_BACKUPS = "owner-backups"
        private const val ACTION_RUNTIME_BACK = "back"
        private const val ACTION_RUNTIME_PAGE = "page"
        private const val ACTION_RUNTIME_RESTORE = "restore"
        private const val ACTION_RUNTIME_CREATE = "create"
        private const val PAYLOAD_WORLD_UUID = "world_uuid"
        private const val PAYLOAD_PAGE = "page"
        private const val PAYLOAD_SHOW_BACK = "show_back"
        private const val PAYLOAD_PLAYER_FLOW = "player_flow"
        private const val PAYLOAD_PARENT_BACK = "parent_back"
    }
}
