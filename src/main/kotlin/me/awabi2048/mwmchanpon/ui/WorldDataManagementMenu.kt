package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
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
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

class WorldDataManagementMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService,
    private val backupListMenu: BackupListMenu,
    private val annualArchiveFlow: AnnualArchiveFlow,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_LIST,
                renderer = { context -> renderList(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler(::back),
                    ACTION_PAGE to MenuActionHandler(::changePage),
                    ACTION_WORLD to MenuActionHandler(::selectWorld),
                    ACTION_BULK_BACKUP to MenuActionHandler(::showBulkBackupConfirmation),
                    ACTION_BULK_ROLLBACK to MenuActionHandler(::showBulkRollbackConfirmation),
                    ACTION_ANNUAL_ARCHIVE to MenuActionHandler(::startAnnualArchive),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_BACKUP_CONFIRM,
                renderer = { context -> renderBackupConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM_BACKUP to MenuActionHandler(::confirmBackup),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_ROLLBACK_CONFIRM,
                renderer = { context -> renderBulkRollbackConfirmation(context.player) },
                actions = mapOf(
                    ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM_ROLLBACK to MenuActionHandler(::confirmBulkRollback),
                ),
            ),
        )
    }

    fun open(player: Player, page: Int = 0) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        runtime.navigate(player, listRoute(page))
    }

    internal fun route(page: Int = 0): MenuRoute = listRoute(page)

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val worlds = productionWorlds()
        val pages = pageCount(worlds.size)
        val safePage = page(route).coerceIn(0, pages - 1)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += simpleActionEntry(
                player, layout.previousPageSlot, Material.ARROW,
                i18n(player, "common.prev"), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, "common.prev_action"), mapOf(PAGE to (safePage - 1).toString()),
            )
        }
        if (safePage < pages - 1) {
            elements += simpleActionEntry(
                player, layout.nextPageSlot, Material.ARROW,
                i18n(player, "common.next"), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, "common.next_action"), mapOf(PAGE to (safePage + 1).toString()),
            )
        }
        elements += simpleActionEntry(
            player, layout.backSlot, Material.REDSTONE,
            i18n(player, "common.back"), GuiElementRole.BACK, ACTION_BACK, i18n(player, "common.back_action"),
        )
        elements += infoEntry(player, layout.infoSlot, worlds.size, safePage + 1, pages)
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            elements += worldEntry(
                player,
                layout.itemSlots[index],
                world,
                mapOf(
                    WORLD_UUID to world.uuid.toString(),
                    PAGE to safePage.toString(),
                ),
            )
        }
        elements += controlEntry(
            player, 47, Material.CHEST, "world_data.bulk_backup",
            ACTION_BULK_BACKUP, mapOf(PAGE to safePage.toString()),
        )
        elements += controlEntry(
            player, 51, Material.RECOVERY_COMPASS, "world_data.bulk_rollback",
            ACTION_BULK_ROLLBACK, mapOf(PAGE to safePage.toString()),
        )
        elements += controlEntry(
            player, 53, Material.ENDER_CHEST, "world_data.annual_archive",
            ACTION_ANNUAL_ARCHIVE, warning = true,
        )
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "world_data.title"),
            elements,
        )
    }

    private fun renderBackupConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val worldUuid = route.payload[WORLD_UUID]?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        val key = if (worldUuid == null) "confirm.backup_all" else "confirm.backup_world"
        return confirmationView(
            player,
            "$key.title",
            Material.CHEST,
            key,
            ACTION_CONFIRM_BACKUP,
        )
    }

    private fun renderBulkRollbackConfirmation(player: Player): InventoryMenuView =
        confirmationView(
            player,
            "confirm.rollback_all.title",
            Material.RECOVERY_COMPASS,
            "confirm.rollback_all",
            ACTION_CONFIRM_ROLLBACK,
        )

    private fun confirmationView(
        player: Player,
        titleKey: String,
        previewMaterial: Material,
        previewKey: String,
        confirmAction: String,
    ): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, titleKey),
            listOf(
                displayEntry(
                    layout.previewSlot,
                    previewMaterial,
                    GuiNameSpec.Component(i18nComponent(player, "$previewKey.display")),
                    GuiLoreSpec.Rich(
                        i18nList(player, "$previewKey.lore").map(GuiLoreLine::Text),
                        GuiLoreFrame.BOTH,
                    ),
                ),
                simpleActionEntry(
                    player, layout.confirmSlot, Material.LIME_CONCRETE,
                    i18n(player, "confirm.button.confirm"), GuiElementRole.CONFIRM,
                    confirmAction, i18n(player, "confirm.button.confirm_action"),
                ),
                simpleActionEntry(
                    player, layout.cancelSlot, Material.RED_CONCRETE,
                    i18n(player, "confirm.button.cancel"), GuiElementRole.CANCEL,
                    ACTION_CANCEL, i18n(player, "confirm.button.cancel_action"),
                ),
            ),
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun changePage(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(
            MenuUpdate.Replace(listRoute(context.payload[PAGE]?.toIntOrNull() ?: 0)),
        )

    private fun selectWorld(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.payload[WORLD_UUID]?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        } ?: return MenuActionResult.Rejected()
        val currentPage = context.payload[PAGE]?.toIntOrNull() ?: page(context.route)
        if (context.click.isLeftClick) {
            return MenuActionResult.Success(
                MenuUpdate.Navigate(
                    MenuRoute(
                        OWNER,
                        ROUTE_BACKUP_CONFIRM,
                        mapOf(
                            WORLD_UUID to worldUuid.toString(),
                            PAGE to currentPage.toString(),
                        ),
                    ),
                ),
            )
        }
        if (context.click.isRightClick) {
            return MenuActionResult.Success(
                MenuUpdate.Navigate(backupListMenu.routeForWorld(worldUuid)),
            )
        }
        return MenuActionResult.Ignored
    }

    private fun showBulkBackupConfirmation(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(
            MenuUpdate.Navigate(
                MenuRoute(
                    OWNER,
                    ROUTE_BACKUP_CONFIRM,
                    mapOf(PAGE to (context.payload[PAGE]?.toIntOrNull() ?: 0).toString()),
                ),
            ),
        )

    private fun showBulkRollbackConfirmation(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(
            MenuUpdate.Navigate(
                MenuRoute(
                    OWNER,
                    ROUTE_ROLLBACK_CONFIRM,
                    mapOf(PAGE to (context.payload[PAGE]?.toIntOrNull() ?: 0).toString()),
                ),
            ),
        )

    private fun confirmBackup(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.route.payload[WORLD_UUID]?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        val player = context.player
        val future = if (worldUuid == null) {
            backupService.createAllBackup(
                productionWorlds().map { it.uuid },
                BackupKind.ADMIN_MANUAL,
                player.uniqueId,
                player.name,
                "admin_bulk",
            )
        } else {
            backupService.createWorldBackup(
                worldUuid,
                BackupKind.ADMIN_MANUAL,
                player.uniqueId,
                player.name,
                "admin_world",
            )
        }
        future.thenAccept { result ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.sendMessage(
                    i18nComponent(
                        player,
                        if (result.status == BackupOperationStatus.SUCCESS) {
                            "message.backup_success"
                        } else {
                            "message.backup_failed"
                        },
                    ),
                )
            })
        }
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun confirmBulkRollback(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(
            MenuUpdate.Navigate(backupListMenu.routeForAll()),
        )
    }

    private fun startAnnualArchive(context: MenuActionContext): MenuActionResult {
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable { annualArchiveFlow.start(context.player) },
        )
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun productionWorlds(): List<WorldData> =
        MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
            .sortedByDescending { it.createdAt }

    private fun pageCount(worldCount: Int): Int =
        if (worldCount == 0) 1 else (worldCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE

    private fun page(route: MenuRoute): Int = route.payload[PAGE]?.toIntOrNull() ?: 0

    private fun listRoute(page: Int): MenuRoute =
        MenuRoute(OWNER, ROUTE_LIST, mapOf(PAGE to page.toString()))

    private fun worldEntry(
        player: Player,
        slot: Int,
        world: WorldData,
        payload: Map<String, String>,
    ): MenuElement {
        return CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = world.icon,
            name = GuiNameSpec.Component(
                i18nComponent(player, "world_data.world.display", mapOf("world" to world.name)),
            ),
            role = GuiElementRole.ACTION,
            data = listOf(
                com.awabi2048.ccsystem.api.gui.GuiMenuEntryData(
                    i18n(player, "world_data.world.owner_label"),
                    Bukkit.getOfflinePlayer(world.owner).name ?: "-",
                ),
                com.awabi2048.ccsystem.api.gui.GuiMenuEntryData(
                    i18n(player, "world_data.world.created_label"),
                    world.createdAt,
                ),
                com.awabi2048.ccsystem.api.gui.GuiMenuEntryData(
                    i18n(player, "world_data.world.backups_label"),
                    backupService.listBackupsForWorld(world.uuid).size,
                ),
            ),
            actions = listOf(
                GuiMenuActionIntent.GestureAction(ACTION_WORLD, MenuGesture.LEFT, i18n(player, "world_data.world.backup_action"), payload),
                GuiMenuActionIntent.GestureAction(ACTION_WORLD, MenuGesture.RIGHT, i18n(player, "world_data.world.list_action"), payload),
            ),
        ),
    )
    }

    private fun controlEntry(
        player: Player,
        slot: Int,
        material: Material,
        key: String,
        actionId: String,
        payload: Map<String, String> = emptyMap(),
        warning: Boolean = false,
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.Component(i18nComponent(player, "$key.display")),
            role = GuiElementRole.ACTION,
            description = listOf(i18n(player, "$key.description")),
            warnings = if (warning) listOf(i18n(player, "$key.warning")) else emptyList(),
            actions = listOf(GuiMenuActionIntent.GestureAction(
                actionId,
                MenuGesture.ANY,
                i18n(player, "$key.action"),
                payload,
            )),
        ),
    )

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
        count: Int,
        page: Int,
        pages: Int,
    ): MenuElement = displayEntry(
        slot,
        Material.BOOK,
        GuiNameSpec.Component(i18nComponent(player, "world_data.info.display")),
        GuiLoreSpec.Rich(
            i18nList(
                player,
                "world_data.info.lore",
                mapOf("count" to count, "page" to page, "pages" to pages),
            ).map(GuiLoreLine::Text),
            GuiLoreFrame.BOTH,
        ),
    )

    private fun i18nComponent(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18n(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE_LIST = "world-data"
        private const val ROUTE_BACKUP_CONFIRM = "world-data-backup-confirm"
        private const val ROUTE_ROLLBACK_CONFIRM = "world-data-rollback-confirm"
        private const val ACTION_BACK = "back"
        private const val ACTION_PAGE = "page"
        private const val ACTION_WORLD = "world"
        private const val ACTION_BULK_BACKUP = "bulk-backup"
        private const val ACTION_BULK_ROLLBACK = "bulk-rollback"
        private const val ACTION_ANNUAL_ARCHIVE = "annual-archive"
        private const val ACTION_CONFIRM_BACKUP = "confirm-backup"
        private const val ACTION_CONFIRM_ROLLBACK = "confirm-rollback"
        private const val ACTION_CANCEL = "cancel"
        private const val PAGE = "page"
        private const val WORLD_UUID = "world-uuid"
        private const val ITEMS_PER_PAGE = 36
    }
}
