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
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.service.ArchiveService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class WorldDataManagementMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService,
    private val backupListMenu: BackupListMenu,
    private val archiveService: ArchiveService,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    private val childReturnPages = ConcurrentHashMap<UUID, Int>()
    private var parentMenuOpener: ((Player) -> Unit)? = null

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

    fun setParentMenuOpener(opener: (Player) -> Unit) {
        parentMenuOpener = opener
    }

    fun open(player: Player, page: Int = 0) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        runtime.open(player, listRoute(page))
    }

    fun reopenFromChild(player: Player) {
        open(player, childReturnPages.remove(player.uniqueId) ?: 0)
    }

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val worlds = productionWorlds()
        val pages = pageCount(worlds.size)
        val safePage = page(route).coerceIn(0, pages - 1)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += MenuElement(
                layout.previousPageSlot,
                navItem(player, false),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                mapOf(PAGE to (safePage - 1).toString()),
            )
        }
        if (safePage < pages - 1) {
            elements += MenuElement(
                layout.nextPageSlot,
                navItem(player, true),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                mapOf(PAGE to (safePage + 1).toString()),
            )
        }
        elements += MenuElement(layout.backSlot, backItem(player), GuiElementRole.BACK, ACTION_BACK)
        elements += MenuElement(
            layout.infoSlot,
            infoItem(player, worlds.size, safePage + 1, pages),
            GuiElementRole.CONTENT,
        )
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            elements += MenuElement(
                layout.itemSlots[index],
                worldItem(player, world),
                GuiElementRole.ACTION,
                ACTION_WORLD,
                mapOf(
                    WORLD_UUID to world.uuid.toString(),
                    PAGE to safePage.toString(),
                ),
            )
        }
        elements += MenuElement(
            47,
            controlItem(player, Material.CHEST, "world_data.bulk_backup", false),
            GuiElementRole.ACTION,
            ACTION_BULK_BACKUP,
            mapOf(PAGE to safePage.toString()),
        )
        elements += MenuElement(
            51,
            controlItem(player, Material.RECOVERY_COMPASS, "world_data.bulk_rollback", false),
            GuiElementRole.ACTION,
            ACTION_BULK_ROLLBACK,
            mapOf(PAGE to safePage.toString()),
        )
        elements += MenuElement(
            53,
            controlItem(player, Material.ENDER_CHEST, "world_data.annual_archive", true),
            GuiElementRole.ACTION,
            ACTION_ANNUAL_ARCHIVE,
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
            centerItem(player, Material.CHEST, key),
            ACTION_CONFIRM_BACKUP,
        )
    }

    private fun renderBulkRollbackConfirmation(player: Player): InventoryMenuView =
        confirmationView(
            player,
            "confirm.rollback_all.title",
            centerItem(player, Material.RECOVERY_COMPASS, "confirm.rollback_all"),
            ACTION_CONFIRM_ROLLBACK,
        )

    private fun confirmationView(
        player: Player,
        titleKey: String,
        preview: ItemStack,
        confirmAction: String,
    ): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, titleKey),
            listOf(
                MenuElement(layout.previewSlot, preview, GuiElementRole.CONTENT),
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
            childReturnPages[context.player.uniqueId] = currentPage
            backupListMenu.openWorld(context.player, worldUuid)
            return MenuActionResult.Success(MenuUpdate.None)
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
        childReturnPages[context.player.uniqueId] = page(context.route)
        backupListMenu.openAll(context.player)
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun startAnnualArchive(context: MenuActionContext): MenuActionResult {
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable { AnnualArchiveFlow(plugin, archiveService).start(context.player) },
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

    private fun worldItem(player: Player, world: WorldData): ItemStack =
        ItemStack(world.icon).also { item ->
            item.editMeta { meta ->
                meta.displayName(
                    i18nComponent(
                        player,
                        "world_data.world.display",
                        mapOf("world" to world.name),
                    ),
                )
                meta.lore(
                    structuredBlocks(
                        listOf(
                            GuiLoreBlock(
                                listOf(
                                    GuiLoreLine.Data(
                                        i18n(player, "world_data.world.owner_label"),
                                        Bukkit.getOfflinePlayer(world.owner).name ?: "-",
                                        "§f",
                                    ),
                                    GuiLoreLine.Data(
                                        i18n(player, "world_data.world.created_label"),
                                        world.createdAt,
                                        "§f",
                                    ),
                                    GuiLoreLine.Data(
                                        i18n(player, "world_data.world.backups_label"),
                                        backupService.listBackupsForWorld(world.uuid).size,
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
                                        i18n(player, "world_data.world.backup_action"),
                                    ),
                                    GuiLoreLine.Action(
                                        mwm().languageManager.getMessage(
                                            player,
                                            "gui.settings.click.right",
                                        ),
                                        i18n(player, "world_data.world.list_action"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun controlItem(
        player: Player,
        material: Material,
        key: String,
        warning: Boolean,
    ): ItemStack = ItemStack(material).also { item ->
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(
                structuredBlocks(
                    listOf(
                        GuiLoreBlock(
                            buildList {
                                add(GuiLoreLine.Text(i18n(player, "$key.description")))
                                if (warning) {
                                    add(GuiLoreLine.Warning(i18n(player, "$key.warning")))
                                }
                            },
                        ),
                    ),
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
                meta.displayName(i18nComponent(player, "world_data.info.display"))
                meta.lore(
                    ChanponLore.build(
                        i18nList(
                            player,
                            "world_data.info.lore",
                            mapOf("count" to count, "page" to page, "pages" to pages),
                        ).map(GuiLoreLine::Text),
                    ),
                )
            }
        }

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

    private fun structuredBlocks(blocks: List<GuiLoreBlock>): List<Component> =
        CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(
            me.awabi2048.myworldmanager.MyWorldManager::class.java,
        )

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
