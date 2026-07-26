package me.awabi2048.mwmchanpon.ui

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
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class BackupListMenu(
    private val plugin: MWMChanponPlugin,
    private val backupService: BackupService,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    private var parentMenuOpener: ((Player) -> Unit)? = null
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
        runtime.open(player, listRoute(mode, worldUuid, page))
    }

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val source = source(route)
        val backups = backups(source)
        val pages = pageCount(backups.size)
        val safePage = source.page.coerceIn(0, pages - 1)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += MenuElement(
                layout.previousPageSlot,
                navItem(player, false),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                sourcePayload(source.copy(page = safePage - 1)),
            )
        }
        if (safePage < pages - 1) {
            elements += MenuElement(
                layout.nextPageSlot,
                navItem(player, true),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                sourcePayload(source.copy(page = safePage + 1)),
            )
        }
        elements += MenuElement(layout.backSlot, backItem(player), GuiElementRole.BACK, ACTION_BACK)
        elements += MenuElement(
            layout.infoSlot,
            infoItem(player, backups.size, safePage + 1, pages),
            GuiElementRole.CONTENT,
        )
        backups.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, backup ->
            elements += MenuElement(
                layout.itemSlots[index],
                backupItem(player, backup),
                GuiElementRole.ACTION,
                ACTION_BACKUP,
                sourcePayload(source.copy(page = safePage)) + (BACKUP_ID to backup.id),
            )
        }
        return InventoryMenuView(
            layout.size,
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
                MenuElement(
                    layout.previewSlot,
                    centerItem(player, material, key),
                    GuiElementRole.CONTENT,
                ),
                MenuElement(
                    layout.confirmSlot,
                    confirmationButton(player, true),
                    GuiElementRole.CONFIRM,
                    confirmAction,
                ),
                MenuElement(
                    layout.cancelSlot,
                    confirmationButton(player, false),
                    GuiElementRole.CANCEL,
                    ACTION_CANCEL,
                ),
            ),
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        val opener = parentMenuOpener ?: return MenuActionResult.Success(MenuUpdate.Back)
        Bukkit.getScheduler().runTask(plugin, Runnable { opener(context.player) })
        return MenuActionResult.Success(MenuUpdate.Close)
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

    private fun backupItem(player: Player, backup: BackupEntry): ItemStack =
        ItemStack(materialFor(backup.kind)).also { item ->
            item.editMeta { meta ->
                meta.displayName(
                    i18nComponent(
                        player,
                        "backup_list.item.display",
                        mapOf("name" to BackupDisplay.displayName(backup)),
                    ),
                )
                meta.lore(
                    structuredBlocks(
                        listOf(
                            GuiLoreBlock(
                                listOf(
                                    GuiLoreLine.Data(
                                        i18n(player, "backup_list.item.kind_label"),
                                        i18n(player, "kind.${backup.kind.i18nKey}"),
                                        "",
                                    ),
                                    GuiLoreLine.Data(
                                        i18n(player, "backup_list.item.created_label"),
                                        BackupDisplay.formatCreatedAt(backup.createdAt),
                                        "§f",
                                    ),
                                    GuiLoreLine.Data(
                                        i18n(player, "backup_list.item.worlds_label"),
                                        backup.worldUuids.size,
                                        "§f",
                                    ),
                                    GuiLoreLine.Data(
                                        i18n(player, "backup_list.item.size_label"),
                                        formatBytes(backup.sizeBytes),
                                        "§f",
                                    ),
                                    GuiLoreLine.Data(
                                        i18n(player, "backup_list.item.actor_label"),
                                        BackupDisplay.actorName(backup),
                                        "§f",
                                    ),
                                ),
                            ),
                            GuiLoreBlock(
                                listOf(
                                    GuiLoreLine.Action(
                                        mwm().languageManager.getMessage(
                                            player,
                                            "gui.settings.click.left",
                                        ),
                                        i18n(player, "backup_list.item.rollback_action"),
                                    ),
                                    GuiLoreLine.Action(
                                        i18n(player, "common.middle_click"),
                                        i18n(player, "backup_list.item.delete_action"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun materialFor(kind: BackupKind): Material = when (kind) {
        BackupKind.USER_MANUAL -> Material.PLAYER_HEAD
        BackupKind.ADMIN_MANUAL -> Material.GOLDEN_HELMET
        BackupKind.SCHEDULED -> Material.CLOCK
        BackupKind.RESTORE_POINT -> Material.END_CRYSTAL
        BackupKind.PRE_ROLLBACK -> Material.RECOVERY_COMPASS
    }

    private fun navItem(player: Player, next: Boolean): ItemStack =
        ChanponGuiElements.navigation(
            i18n(player, if (next) "common.next" else "common.prev"),
            next,
        )

    private fun backItem(player: Player): ItemStack =
        ChanponGuiElements.back(i18n(player, "common.back"))

    private fun infoItem(player: Player, count: Int, page: Int, pages: Int): ItemStack =
        ItemStack(Material.BOOK).also { item ->
            item.editMeta { meta ->
                meta.displayName(i18nComponent(player, "backup_list.info.display"))
                meta.lore(
                    ChanponLore.build(
                        i18nList(
                            player,
                            "backup_list.info.lore",
                            mapOf("count" to count, "page" to page, "pages" to pages),
                        ).map(GuiLoreLine::Text),
                    ),
                )
            }
        }

    private fun centerItem(player: Player, material: Material, key: String): ItemStack =
        ItemStack(material).also { item ->
            item.editMeta { meta ->
                meta.displayName(i18nComponent(player, "$key.display"))
                meta.lore(ChanponLore.build(i18nList(player, "$key.lore").map(GuiLoreLine::Text)))
            }
        }

    private fun confirmationButton(player: Player, confirm: Boolean): ItemStack =
        ChanponGuiElements.confirm(
            i18n(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel"),
            confirm,
        )

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

    private fun structuredBlocks(blocks: List<GuiLoreBlock>): List<Component> =
        CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(
            me.awabi2048.myworldmanager.MyWorldManager::class.java,
        )

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
