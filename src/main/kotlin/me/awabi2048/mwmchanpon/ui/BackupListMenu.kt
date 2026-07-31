package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupDisplay
import me.awabi2048.mwmchanpon.backup.BackupEntry
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupScope
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.backup.RestoreOperationResult
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ManualRollbackService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class BackupListMenu(
    private val plugin: MWMChanponPlugin,
    private val backupService: BackupService,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    private var automationService: BackupAutomationService? = null
    private var manualRollbackService: ManualRollbackService? = null

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_LIST,
                renderer = { context -> renderList(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler(::back),
                    ACTION_PAGE to MenuActionHandler(::changePage),
                    ACTION_BACKUP to MenuActionHandler(::selectBackup),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_RESTORE_CONFIRM,
                renderer = { context ->
                    renderConfirmation(
                        context.player,
                        "confirm.restore",
                        Material.CHEST,
                        ACTION_CONFIRM_RESTORE,
                    )
                },
                actions = mapOf(
                    ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM_RESTORE to MenuActionHandler(::confirmRestore),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_DELETE_CONFIRM,
                renderer = { context -> renderDeleteConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM_DELETE to MenuActionHandler(::confirmDelete),
                ),
            ),
        )
    }

    fun setAutomationService(service: BackupAutomationService) {
        automationService = service
    }

    fun setManualRollbackService(service: ManualRollbackService) {
        manualRollbackService = service
    }

    fun openAll(player: Player, page: Int = 0) {
        runtime.navigate(player, routeForAll(page))
    }

    fun openWorld(player: Player, worldUuid: UUID, page: Int = 0) {
        runtime.navigate(player, routeForWorld(worldUuid, page))
    }

    internal fun routeForAll(page: Int = 0): MenuRoute =
        listRoute(BackupListMode.ALL, null, page)

    internal fun routeForWorld(worldUuid: UUID, page: Int = 0): MenuRoute =
        listRoute(BackupListMode.WORLD, worldUuid, page)

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val source = source(route)
        val backups = backups(source)
        val variablePage = if (source.mode == BackupListMode.WORLD) {
            CCSystem.getAPI().getGuiLayoutService().sevenColumnPage(backups.size, source.page)
        } else {
            null
        }
        val pages = variablePage?.totalPages ?: pageCount(backups.size)
        val safePage = variablePage?.page ?: source.page.coerceIn(0, pages - 1)
        val variableLayout = variablePage?.layout
        val fixedLayout = if (variableLayout == null) ChanponGuiElements.pagedListLayout() else null
        val inventorySize = variableLayout?.size ?: fixedLayout!!.size
        val previousPageSlot = variableLayout?.previousPageSlot ?: fixedLayout!!.previousPageSlot
        val nextPageSlot = variableLayout?.nextPageSlot ?: fixedLayout!!.nextPageSlot
        val backSlot = variableLayout?.backSlot ?: fixedLayout!!.backSlot
        val infoSlot = variableLayout?.actionSlot ?: fixedLayout!!.infoSlot
        val itemSlots = variableLayout?.itemSlots ?: fixedLayout!!.itemSlots
        val startIndex = variablePage?.startIndex ?: safePage * ITEMS_PER_PAGE
        val itemCount = variablePage?.itemCount ?: ITEMS_PER_PAGE
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += simpleActionEntry(
                player, previousPageSlot, Material.ARROW, i18n(player, "common.prev"),
                GuiElementRole.NAVIGATION, ACTION_PAGE, i18n(player, "common.prev_action"),
                sourcePayload(source.copy(page = safePage - 1)),
            )
        }
        if (safePage < pages - 1) {
            elements += simpleActionEntry(
                player, nextPageSlot, Material.ARROW, i18n(player, "common.next"),
                GuiElementRole.NAVIGATION, ACTION_PAGE, i18n(player, "common.next_action"),
                sourcePayload(source.copy(page = safePage + 1)),
            )
        }
        elements += simpleActionEntry(
            player, backSlot, Material.REDSTONE, i18n(player, "common.back"),
            GuiElementRole.BACK, ACTION_BACK, i18n(player, "common.back_action"),
        )
        elements += infoEntry(player, infoSlot, backups.size, safePage + 1, pages)
        backups.drop(startIndex).take(itemCount).forEachIndexed { index, backup ->
            elements += backupEntry(
                player,
                itemSlots[index],
                backup,
                sourcePayload(source.copy(page = safePage)) + (BACKUP_ID to backup.id),
            )
        }
        return InventoryMenuView(
            inventorySize,
            i18nComponent(player, "backup_list.title"),
            elements,
        )
    }

    private fun renderDeleteConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val stage = route.payload[STAGE]?.toIntOrNull()?.coerceIn(1, 2) ?: 1
        return renderConfirmation(
            player,
            "confirm.delete$stage",
            Material.BARRIER,
            ACTION_CONFIRM_DELETE,
        )
    }

    private fun renderConfirmation(
        player: Player,
        key: String,
        material: Material,
        confirmAction: String,
    ): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "$key.title"),
            listOf(
                displayEntry(
                    layout.previewSlot,
                    material,
                    GuiNameSpec.Component(i18nComponent(player, "$key.display")),
                    GuiLoreSpec.Rich(
                        i18nList(player, "$key.lore").map(GuiLoreLine::Text),
                        GuiLoreFrame.BOTH,
                    ),
                ),
                confirmationEntry(player, layout.confirmSlot, true, confirmAction),
                confirmationEntry(player, layout.cancelSlot, false, ACTION_CANCEL),
            ),
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun changePage(context: MenuActionContext): MenuActionResult {
        val source = source(context.payload)
        return MenuActionResult.Success(
            MenuUpdate.Replace(listRoute(source.mode, source.worldUuid, source.page)),
        )
    }

    private fun selectBackup(context: MenuActionContext): MenuActionResult {
        val backupId = context.payload[BACKUP_ID] ?: return MenuActionResult.Rejected()
        val source = source(context.payload)
        val route = when {
            context.click == ClickType.MIDDLE -> MenuRoute(
                OWNER,
                ROUTE_DELETE_CONFIRM,
                sourcePayload(source) + mapOf(BACKUP_ID to backupId, STAGE to "1"),
            )
            context.click.isLeftClick -> MenuRoute(
                OWNER,
                ROUTE_RESTORE_CONFIRM,
                sourcePayload(source) + (BACKUP_ID to backupId),
            )
            else -> return MenuActionResult.Ignored
        }
        return MenuActionResult.Success(MenuUpdate.Navigate(route))
    }

    private fun confirmRestore(context: MenuActionContext): MenuActionResult {
        val player = context.player
        if (automationService?.isManualOperationBlocked(player) == true) {
            player.sendMessage(i18nComponent(player, "message.manual_operation_blocked"))
            return MenuActionResult.Rejected()
        }
        val backup = findBackup(context.route)
            ?: return MenuActionResult.Rejected()
        val source = source(context.route)
        val targetWorlds = restoreTargets(backup, source)
        val rollbackCooldown = backupService.manualRollbackCooldownRemainingSeconds(targetWorlds)
        if (rollbackCooldown > 0L) {
            player.sendMessage(
                i18nComponent(
                    player,
                    "message.rollback_cooldown",
                    mapOf("remaining" to BackupDisplay.formatDuration(rollbackCooldown)),
                ),
            )
            return MenuActionResult.Rejected()
        }
        val preflightFailure = backupService.restorePreflightFailure(backup, targetWorlds)
        if (preflightFailure != null) {
            player.sendMessage(
                Component.text("要因: ${restoreReason(preflightFailure)}", NamedTextColor.RED),
            )
            return MenuActionResult.Rejected()
        }
        val rollbackService = manualRollbackService
        if (rollbackService == null) {
            executeRestore(player, backup, source)
        } else {
            rollbackService.schedule(
                player,
                {
                    if (source.mode == BackupListMode.WORLD && source.worldUuid != null) {
                        backupService.restoreWorldBackup(backup, source.worldUuid)
                    } else {
                        backupService.restoreBackup(backup)
                    }
                },
                { result ->
                    if (
                        result.status == BackupOperationStatus.SUCCESS ||
                        result.status == BackupOperationStatus.RECOVERED
                    ) {
                        backupService.markManualRollbackExecuted(result.restoredWorlds)
                    }
                    sendRestoreResult(player, result)
                },
            )
        }
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun confirmDelete(context: MenuActionContext): MenuActionResult {
        val backup = findBackup(context.route)
            ?: return MenuActionResult.Rejected()
        val stage = context.route.payload[STAGE]?.toIntOrNull() ?: 1
        if (stage == 1) {
            return MenuActionResult.Success(
                MenuUpdate.Replace(
                    MenuRoute(
                        OWNER,
                        ROUTE_DELETE_CONFIRM,
                        context.route.payload + (STAGE to "2"),
                    ),
                ),
            )
        }
        val deleted = backupService.deleteBackup(backup)
        context.player.sendMessage(
            i18nComponent(
                context.player,
                if (deleted) "message.delete_success" else "message.delete_failed",
            ),
        )
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun executeRestore(player: Player, backup: BackupEntry, source: BackupListSource) {
        val future = if (source.mode == BackupListMode.WORLD && source.worldUuid != null) {
            backupService.restoreWorldBackup(backup, source.worldUuid)
        } else {
            backupService.restoreBackup(backup)
        }
        future.thenAccept { result ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (
                    result.status == BackupOperationStatus.SUCCESS ||
                    result.status == BackupOperationStatus.RECOVERED
                ) {
                    backupService.markManualRollbackExecuted(result.restoredWorlds)
                }
                sendRestoreResult(player, result)
            })
        }
    }

    private fun sendRestoreResult(player: Player, result: RestoreOperationResult) {
        player.sendMessage(i18nComponent(player, "message.restore_${result.status.name.lowercase()}"))
        if (result.status != BackupOperationStatus.SUCCESS) {
            player.sendMessage(
                Component.text("要因: ${restoreReason(result.message)}", NamedTextColor.RED),
            )
        }
    }

    private fun restoreReason(message: String): String = when (message) {
        "world is not included in backup" -> "このバックアップに対象ワールドが含まれていません。"
        "backup is locked" -> "このバックアップは別の処理で使用中です。"
        "no production worlds" -> "対象となる本番用ワールドがありません。"
        "world is locked" -> "対象ワールドは別のバックアップまたはロールバック処理中です。"
        "core api unavailable" -> "MyWorldManagerのAPIを取得できません。"
        "spawn world is not configured or unavailable" -> "退避先のスポーンワールドが未設定、または利用できません。"
        "world is not a production world" -> "対象に本番用ワールドではないワールドが含まれています。"
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

    private fun restoreTargets(backup: BackupEntry, source: BackupListSource): List<UUID> =
        if (source.mode == BackupListMode.WORLD && source.worldUuid != null) {
            listOf(source.worldUuid)
        } else {
            backup.worldUuids
        }

    private fun findBackup(route: MenuRoute): BackupEntry? {
        val backupId = route.payload[BACKUP_ID] ?: return null
        return backupService.listBackups().firstOrNull { it.id == backupId }
    }

    private fun backups(source: BackupListSource): List<BackupEntry> = when (source.mode) {
        BackupListMode.ALL -> backupService.listBackups().filter { it.scope == BackupScope.ALL }
        BackupListMode.WORLD -> source.worldUuid
            ?.let(backupService::listBackupsForWorld)
            .orEmpty()
    }

    private fun pageCount(itemCount: Int): Int =
        if (itemCount == 0) 1 else (itemCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE

    private fun listRoute(
        mode: BackupListMode,
        worldUuid: UUID?,
        page: Int,
    ): MenuRoute = MenuRoute(OWNER, ROUTE_LIST, sourcePayload(BackupListSource(mode, worldUuid, page)))

    private fun source(route: MenuRoute): BackupListSource = source(route.payload)

    private fun source(payload: Map<String, String>): BackupListSource {
        val mode = payload[MODE]
            ?.let { runCatching { BackupListMode.valueOf(it) }.getOrNull() }
            ?: BackupListMode.ALL
        val worldUuid = payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return BackupListSource(mode, worldUuid, payload[PAGE]?.toIntOrNull() ?: 0)
    }

    private fun sourcePayload(source: BackupListSource): Map<String, String> = buildMap {
        put(MODE, source.mode.name)
        put(PAGE, source.page.toString())
        source.worldUuid?.let { put(WORLD_UUID, it.toString()) }
    }

    private fun backupEntry(
        player: Player,
        slot: Int,
        backup: BackupEntry,
        payload: Map<String, String>,
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = materialFor(backup.kind),
            name = GuiNameSpec.Component(
                i18nComponent(
                    player,
                    "backup_list.item.display",
                    mapOf("name" to BackupDisplay.displayName(backup)),
                ),
            ),
            role = GuiElementRole.ACTION,
            data = listOf(
                GuiMenuEntryData(i18n(player, "backup_list.item.kind_label"), i18n(player, "kind.${backup.kind.i18nKey}")),
                GuiMenuEntryData(i18n(player, "backup_list.item.created_label"), BackupDisplay.formatCreatedAt(backup.createdAt)),
                GuiMenuEntryData(i18n(player, "backup_list.item.worlds_label"), backup.worldUuids.size),
                GuiMenuEntryData(i18n(player, "backup_list.item.size_label"), formatBytes(backup.sizeBytes)),
                GuiMenuEntryData(i18n(player, "backup_list.item.actor_label"), BackupDisplay.actorName(backup)),
            ),
            actions = listOf(
                GuiMenuActionIntent.GestureAction(ACTION_BACKUP, MenuGesture.LEFT, i18n(player, "backup_list.item.rollback_action"), payload),
                GuiMenuActionIntent.GestureAction(ACTION_BACKUP, MenuGesture.MIDDLE, i18n(player, "backup_list.item.delete_action"), payload),
            ),
        ),
    )

    private fun materialFor(kind: BackupKind): Material = when (kind) {
        BackupKind.USER_MANUAL -> Material.PLAYER_HEAD
        BackupKind.ADMIN_MANUAL -> Material.GOLDEN_HELMET
        BackupKind.SCHEDULED -> Material.CLOCK
        BackupKind.RESTORE_POINT -> Material.END_CRYSTAL
        BackupKind.PRE_ROLLBACK -> Material.RECOVERY_COMPASS
    }

    private fun simpleActionEntry(
        player: Player,
        slot: Int,
        material: Material,
        text: String,
        role: GuiElementRole,
        actionId: String,
        actionText: String,
        payload: Map<String, String> = emptyMap(),
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.Text(text, GuiNameStyle.DEFAULT),
            role = role,
            actions = listOf(
                GuiMenuActionIntent.GestureAction(actionId, MenuGesture.ANY, actionText, payload),
            ),
        ),
    )

    private fun displayEntry(
        slot: Int,
        material: Material,
        name: GuiNameSpec,
        lore: GuiLoreSpec,
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuDisplay(
        GuiMenuDisplaySpec(
            slot,
            GuiItemSpec(material, name, lore, GuiElementRole.CONTENT, 1),
        ),
    )

    private fun infoEntry(
        player: Player,
        slot: Int,
        count: Int,
        page: Int,
        pages: Int,
    ): MenuElement = displayEntry(
        slot,
        Material.BOOK,
        GuiNameSpec.Component(i18nComponent(player, "backup_list.info.display")),
        GuiLoreSpec.Rich(
            i18nList(
                player,
                "backup_list.info.lore",
                mapOf("count" to count, "page" to page, "pages" to pages),
            ).map(GuiLoreLine::Text),
            GuiLoreFrame.BOTH,
        ),
    )

    private fun confirmationEntry(
        player: Player,
        slot: Int,
        confirm: Boolean,
        actionId: String,
    ): MenuElement {
        val key = if (confirm) "confirm.button.confirm" else "confirm.button.cancel"
        val text = i18n(player, key)
        return simpleActionEntry(
            player,
            slot,
            if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            text,
            if (confirm) GuiElementRole.CONFIRM else GuiElementRole.CANCEL,
            actionId,
            i18n(player, if (confirm) "confirm.button.confirm_action" else "confirm.button.cancel_action"),
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1fKB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1fMB".format(mb)
        return "%.1fGB".format(mb / 1024.0)
    }

    private fun i18n(player: Player?, key: String) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")

    private fun i18nComponent(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    enum class BackupListMode {
        ALL,
        WORLD,
    }

    private data class BackupListSource(
        val mode: BackupListMode,
        val worldUuid: UUID?,
        val page: Int,
    )

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE_LIST = "backup-list"
        private const val ROUTE_RESTORE_CONFIRM = "backup-restore-confirm"
        private const val ROUTE_DELETE_CONFIRM = "backup-delete-confirm"
        private const val ACTION_BACK = "back"
        private const val ACTION_PAGE = "page"
        private const val ACTION_BACKUP = "backup"
        private const val ACTION_CONFIRM_RESTORE = "confirm-restore"
        private const val ACTION_CONFIRM_DELETE = "confirm-delete"
        private const val ACTION_CANCEL = "cancel"
        private const val MODE = "mode"
        private const val WORLD_UUID = "world-uuid"
        private const val PAGE = "page"
        private const val BACKUP_ID = "backup-id"
        private const val STAGE = "stage"
        private const val ITEMS_PER_PAGE = 36
    }
}
