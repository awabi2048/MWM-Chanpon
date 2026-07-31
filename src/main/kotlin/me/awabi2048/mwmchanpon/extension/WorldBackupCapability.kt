package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiValueTone
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
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
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.plugin.Plugin
import java.util.UUID

class WorldBackupCapability(
    private val plugin: Plugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val backupService: BackupService,
    private val automationService: BackupAutomationService,
    private val manualRollbackService: ManualRollbackService
) {
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
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_OWNER_BACKUP_CONFIRM,
                renderer = { context -> renderBackupConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_RUNTIME_CONFIRM to MenuActionHandler(::confirmOwnerBackup),
                    ACTION_RUNTIME_CANCEL to MenuActionHandler(::cancelConfirmation),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_RESTORE_CONFIRM,
                renderer = { context -> renderRestoreConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_RUNTIME_CONFIRM to MenuActionHandler(::confirmRestore),
                    ACTION_RUNTIME_CANCEL to MenuActionHandler(::cancelConfirmation),
                ),
            ),
        )
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = RUNTIME_OWNER,
                id = CAPABILITY_ID,
                placement = ChanponWorldSettingsPlacements.FOOTER_RIGHT,
                availability = MenuCapabilityAvailability { context ->
                    val world = worldUuid(context.arguments)
                        ?.let { ownedProductionWorld(context.player, it) }
                        ?: return@MenuCapabilityAvailability false
                    !protectionService.isProtected(world)
                },
                presentationProvider = MenuCapabilityPresentationProvider { context ->
                    entryPresentation(context.player)
                },
                actions = listOf(
                    MenuCapabilityAction(
                        id = "open",
                        trigger = MenuCapabilityTrigger.ANY,
                        textProvider = MenuCapabilityActionTextProvider {
                            i18n(it.player, "owner_backup.action")
                        },
                        handler = MenuCapabilityActionHandler(::openFromCapability),
                    ),
                ),
            ),
        )
    }

    fun open(player: Player, worldUuid: UUID, page: Int = 0): Boolean {
        if (ownedProductionWorld(player, worldUuid) == null) return false
        return runtime.navigate(player, route(worldUuid, page))
    }

    private fun openFromCapability(context: MenuCapabilityActionContext): MenuActionResult {
        val worldUuid = worldUuid(context.arguments) ?: return MenuActionResult.Ignored
        if (ownedProductionWorld(context.player, worldUuid) == null) {
            return MenuActionResult.Rejected()
        }
        return MenuActionResult.Success(MenuUpdate.Navigate(route(worldUuid, 0)))
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
            elements += simpleActionEntry(
                player, layout.previousPageSlot, Material.ARROW,
                i18n(player, "common.prev"), GuiElementRole.NAVIGATION,
                ACTION_RUNTIME_PAGE, i18n(player, "common.prev_action"), payload(source.copy(page = safePage - 1)),
            )
        }
        if (safePage < pages - 1) {
            elements += simpleActionEntry(
                player, layout.nextPageSlot, Material.ARROW,
                i18n(player, "common.next"), GuiElementRole.NAVIGATION,
                ACTION_RUNTIME_PAGE, i18n(player, "common.next_action"), payload(source.copy(page = safePage + 1)),
            )
        }
        elements += simpleActionEntry(
            player, layout.backSlot, Material.REDSTONE,
            i18n(player, "common.back"), GuiElementRole.BACK,
            ACTION_RUNTIME_BACK, i18n(player, "common.back_action"), payload(source),
        )
        elements += infoEntry(player, layout.infoSlot, world, backups.size, safePage + 1, pages)
        backups.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, backup ->
            elements += backupEntry(
                player,
                layout.itemSlots[index],
                backup,
                payload(source.copy(page = safePage)) + (BACKUP_ID_KEY to backup.id),
            )
        }
        elements += backupControlEntry(
            player,
            47,
            source.worldUuid,
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
            MenuUpdate.Replace(route(source.worldUuid, source.page)),
        )
    }

    private fun selectBackup(context: MenuActionContext): MenuActionResult {
        if (!context.click.isLeftClick) return MenuActionResult.Ignored
        val source = source(context.payload)
        val backupId = context.payload[BACKUP_ID_KEY] ?: return MenuActionResult.Rejected()
        val backup = backupService.listBackupsForWorld(source.worldUuid)
            .firstOrNull { it.id == backupId }
            ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(
            MenuUpdate.Navigate(restoreConfirmationRoute(source, backup.id)),
        )
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
        return MenuActionResult.Success(MenuUpdate.None)
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
                        MenuActionResult.Success(
                            MenuUpdate.Navigate(backupConfirmationRoute(sourceSnapshot, name)),
                        )
                    },
                ),
                cancel = MenuDialogButton(
                    i18nComponent(player, "owner_backup.name_input.cancel"),
                    MenuDialogHandler { _, _ ->
                        MenuActionResult.Success(MenuUpdate.Resume)
                    },
                ),
            ),
        )
    }

    private fun renderBackupConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val backupName = route.payload[BACKUP_NAME_KEY].orEmpty()
        return confirmationView(
            player,
            i18nComponent(player, "confirm.owner_backup.title"),
            i18nList(
                player,
                "confirm.owner_backup.lore",
                mapOf("name" to backupName),
            ),
        )
    }

    private fun renderRestoreConfirmation(player: Player, route: MenuRoute): InventoryMenuView =
        confirmationView(
            player,
            i18nComponent(player, "confirm.restore.title"),
            i18nList(player, "confirm.restore.lore"),
        )

    private fun confirmationView(
        player: Player,
        title: Component,
        body: List<String>,
    ): InventoryMenuView {
        val layout = me.awabi2048.mwmchanpon.ui.ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            title,
            listOf(
                simpleActionEntry(
                    player, layout.confirmSlot, Material.LIME_CONCRETE,
                    i18n(player, "confirm.button.confirm"), GuiElementRole.CONFIRM,
                    ACTION_RUNTIME_CONFIRM, i18n(player, "confirm.button.confirm_action"),
                ),
                displayEntry(
                    layout.previewSlot,
                    Material.PAPER,
                    GuiNameSpec.Component(title),
                    GuiLoreSpec.Rich(body.map(GuiLoreLine::Text), GuiLoreFrame.NONE),
                ),
                simpleActionEntry(
                    player, layout.cancelSlot, Material.RED_CONCRETE,
                    i18n(player, "confirm.button.cancel"), GuiElementRole.CANCEL,
                    ACTION_RUNTIME_CANCEL, i18n(player, "confirm.button.cancel_action"),
                ),
            ),
        )
    }

    private fun confirmOwnerBackup(context: MenuActionContext): MenuActionResult {
        val source = source(context.payload)
        val backupName = context.payload[BACKUP_NAME_KEY]
            ?: return MenuActionResult.Rejected()
        executeOwnerBackup(context.player, source, backupName)
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun confirmRestore(context: MenuActionContext): MenuActionResult {
        val source = source(context.payload)
        val backupId = context.payload[BACKUP_ID_KEY] ?: return MenuActionResult.Rejected()
        val backup = backupService.listBackupsForWorld(source.worldUuid)
            .firstOrNull { it.id == backupId }
            ?: return MenuActionResult.Rejected()
        executeRestore(context.player, backup, source)
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun cancelConfirmation(@Suppress("UNUSED_PARAMETER") context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(MenuUpdate.Back)

    private fun entryPresentation(player: Player) = MenuCapabilityPresentation(
        GuiItemSpec(
            material = Material.CHEST,
            name = GuiNameSpec.Component(i18nComponent(player, "owner_backup.display")),
            lore = GuiLoreSpec.Blocks(
                listOf(
                    GuiLoreBlock(
                        listOf(GuiLoreLine.Text(i18n(player, "owner_backup.description"))),
                    ),
                ),
            ),
            role = GuiElementRole.ACTION,
            amount = 1,
        ),
    )

    private fun backupEntry(
        player: Player,
        slot: Int,
        backup: BackupEntry,
        payload: Map<String, String>,
    ): MenuElement {
        return CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = materialFor(backup),
            name = GuiNameSpec.Component(
                i18nComponent(player, "owner_backup.item.display", mapOf("name" to BackupDisplay.displayName(backup))),
            ),
            role = GuiElementRole.ACTION,
            data = listOf(
                GuiMenuEntryData(
                    i18n(player, "owner_backup.item.created_label"),
                    BackupDisplay.formatCreatedAt(backup.createdAt),
                ),
                GuiMenuEntryData(
                    i18n(player, "owner_backup.item.actor_label"),
                    BackupDisplay.actorName(backup),
                ),
            ),
            actions = listOf(GuiMenuActionIntent.GestureAction(
                ACTION_RUNTIME_RESTORE,
                MenuGesture.LEFT_RIGHT,
                i18n(player, "owner_backup.item.rollback_action"),
                payload,
            )),
        ),
    )
    }

    private fun backupControlEntry(
        player: Player,
        slot: Int,
        worldUuid: UUID,
        payload: Map<String, String>,
    ): MenuElement {
        val remaining = backupService.ownerBackupCooldownRemainingSeconds(worldUuid)
        val key = if (remaining > 0) "owner_backup.cooldown" else "owner_backup.create"
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = if (remaining > 0) Material.CLOCK else Material.CHEST,
                name = GuiNameSpec.Component(i18nComponent(
                    player,
                    "$key.display",
                    mapOf("remaining" to BackupDisplay.formatDuration(remaining)),
                )),
                role = if (remaining > 0) GuiElementRole.CONTENT else GuiElementRole.ACTION,
                description = listOf(i18n(
                    player,
                    "$key.description",
                    mapOf("remaining" to BackupDisplay.formatDuration(remaining)),
                )),
                actions = if (remaining > 0) emptyList() else listOf(GuiMenuActionIntent.GestureAction(
                    ACTION_RUNTIME_CREATE,
                    MenuGesture.ANY,
                    i18n(player, "$key.action"),
                    payload,
                )),
            ),
        )
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
    ): MenuElement {
        if (role == GuiElementRole.BACK) return CCSystem.getAPI().getGuiElementService().backEntry(player, slot)
        return CCSystem.getAPI().getGuiElementService().menuEntry(
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
    }

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
        world: WorldData,
        count: Int,
        page: Int,
        pages: Int,
    ): MenuElement = displayEntry(
        slot,
        world.icon,
        GuiNameSpec.Component(
            i18nComponent(player, "owner_backup.info.display", mapOf("world" to world.name)),
        ),
        GuiLoreSpec.Rich(
            i18nList(
                player,
                "owner_backup.info.lore",
                mapOf("count" to count, "page" to page, "pages" to pages),
            ).map(GuiLoreLine::Text),
            GuiLoreFrame.BOTH,
        ),
    )

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

    private fun route(worldUuid: UUID, page: Int): MenuRoute {
        return MenuRoute(
            RUNTIME_OWNER,
            ROUTE_OWNER_BACKUPS,
            payload(Holder(worldUuid, page)),
        )
    }

    private fun payload(source: Holder): Map<String, String> = mapOf(
        PAYLOAD_WORLD_UUID to source.worldUuid.toString(),
        PAYLOAD_PAGE to source.page.toString(),
    )

    private fun backupConfirmationRoute(source: Holder, backupName: String): MenuRoute =
        MenuRoute(
            RUNTIME_OWNER,
            ROUTE_OWNER_BACKUP_CONFIRM,
            payload(source) + (BACKUP_NAME_KEY to backupName),
        )

    private fun restoreConfirmationRoute(source: Holder, backupId: String): MenuRoute =
        MenuRoute(
            RUNTIME_OWNER,
            ROUTE_RESTORE_CONFIRM,
            payload(source) + (BACKUP_ID_KEY to backupId),
        )

    private fun source(route: MenuRoute): Holder = source(route.payload)

    private fun source(payload: Map<String, String>): Holder {
        val worldUuid = payload[PAYLOAD_WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID(0L, 0L)
        return Holder(
            worldUuid,
            payload[PAYLOAD_PAGE]?.toIntOrNull() ?: 0,
        )
    }

    private fun worldUuid(arguments: Map<String, String>): UUID? =
        arguments[PAYLOAD_WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)
    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)
    private fun structuredBlocks(blocks: List<GuiLoreBlock>): List<Component> =
        CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))
    data class Holder(val worldUuid: UUID, val page: Int)

    companion object {
        private const val ITEMS_PER_PAGE = 36
        private const val CAPABILITY_ID = "world-backup"
        private const val TYPE_BACKUP_ITEM = "chanpon_owner_backup_item"
        private const val TYPE_CONTROL = "chanpon_owner_backup_control"
        private const val BACKUP_ID_KEY = "chanpon_backup_id"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_BACKUP = "backup"
        private const val MAX_BACKUP_NAME_LENGTH = 32
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val ROUTE_OWNER_BACKUPS = "owner-backups"
        private const val ROUTE_OWNER_BACKUP_CONFIRM = "owner-backup-confirm"
        private const val ROUTE_RESTORE_CONFIRM = "owner-backup-restore-confirm"
        private const val ACTION_RUNTIME_BACK = "back"
        private const val ACTION_RUNTIME_PAGE = "page"
        private const val ACTION_RUNTIME_RESTORE = "restore"
        private const val ACTION_RUNTIME_CREATE = "create"
        private const val ACTION_RUNTIME_CONFIRM = "confirm"
        private const val ACTION_RUNTIME_CANCEL = "cancel"
        private const val PAYLOAD_WORLD_UUID = "world_uuid"
        private const val PAYLOAD_PAGE = "page"
        private const val BACKUP_NAME_KEY = "backup_name"
    }
}
