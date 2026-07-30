package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiClickLabel
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsNavigationRequest
import me.awabi2048.myworldmanager.api.extension.AdminMenuCapabilityPlacements
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class ChanponAdminWorldListMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
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
                    ACTION_VISIBILITY_FILTER to MenuActionHandler(::cycleVisibilityFilter),
                    ACTION_SORT to MenuActionHandler(::cycleSort),
                    ACTION_WORLD to MenuActionHandler(::handleWorld),
                ),
            ),
        )
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = OWNER,
                id = WORLD_LIST_CAPABILITY,
                placement = AdminMenuCapabilityPlacements.WORLD_LIST_ACTION,
                availability = MenuCapabilityAvailability {
                    it.player.hasPermission("mwmchanpon.admin")
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    adminMenuWorldListPresentation(it.player)
                },
                actionHandler = MenuCapabilityActionHandler { context ->
                    val state = ListState(fromAdminMenu = true)
                    MenuActionResult.Success(MenuUpdate.Navigate(listRoute(state)))
                },
            ),
        )
    }

    fun open(
        player: Player,
        page: Int = 0,
        visibilityFilter: VisibilityFilter = VisibilityFilter.ALL,
        sortType: SortType = SortType.CREATED_DESC,
        fromAdminMenu: Boolean = false,
    ): Boolean {
        val state = ListState(page, visibilityFilter, sortType, fromAdminMenu)
        return runtime.navigate(player, listRoute(state))
    }

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val requested = state(route)
        val worlds = filteredWorlds(player, requested.visibilityFilter, requested.sortType)
        val totalPages = pageCount(worlds.size)
        val current = requested.copy(page = requested.page.coerceIn(0, totalPages - 1))
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        currentWorld(player)?.let { worldData ->
            elements += MenuElement(
                4,
                worldItem(player, worldData, current = true),
                GuiElementRole.ACTION,
                ACTION_WORLD,
                statePayload(current) + (WORLD_UUID to worldData.uuid.toString()),
            )
        }
        worlds.drop(current.page * ITEMS_PER_PAGE)
            .take(ITEMS_PER_PAGE)
            .forEachIndexed { index, worldData ->
                elements += MenuElement(
                    layout.itemSlots[index],
                    worldItem(player, worldData, current = false),
                    GuiElementRole.ACTION,
                    ACTION_WORLD,
                    statePayload(current) + (WORLD_UUID to worldData.uuid.toString()),
                )
            }
        if (CCSystem.getAPI().getMenuNavigationService().canGoBack(player)) {
            elements += MenuElement(45, backItem(player), GuiElementRole.BACK, ACTION_BACK)
        }
        elements += MenuElement(
            49,
            infoItem(player, worlds.size, current.page + 1, totalPages),
            GuiElementRole.CONTENT,
        )
        elements += MenuElement(
            47,
            visibilityFilterItem(player, current.visibilityFilter),
            GuiElementRole.ACTION,
            ACTION_VISIBILITY_FILTER,
        )
        elements += MenuElement(
            51,
            sortItem(player, current.sortType),
            GuiElementRole.ACTION,
            ACTION_SORT,
        )
        if (current.page > 0) {
            elements += MenuElement(
                52,
                navItem(player, false),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                statePayload(current.copy(page = current.page - 1)),
            )
        }
        if (current.page < totalPages - 1) {
            elements += MenuElement(
                53,
                navItem(player, true),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                statePayload(current.copy(page = current.page + 1)),
            )
        }
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "admin_list.title"),
            elements,
        )
    }

    private fun adminMenuWorldListPresentation(player: Player): MenuCapabilityPresentation {
        val actions = CCSystem.getAPI().getGuiActionService()
        return MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.FILLED_MAP,
                name = GuiNameSpec.Text(
                    CCSystem.getAPI().getI18nString(player, "gui.admin_menu.info.display"),
                    GuiNameStyle.DEFAULT,
                ),
                lore = GuiLoreSpec.Blocks(
                    listOf(
                        GuiLoreBlock(
                            CCSystem.getAPI()
                                .getI18nStringList(player, "gui.admin_menu.info.lore")
                                .map(GuiLoreLine::Text),
                        ),
                        GuiLoreBlock(
                            listOf(
                                actions.singleClick(
                                    player,
                                    CCSystem.getAPI()
                                        .getI18nString(player, "gui.admin_menu.info.action"),
                                ),
                            ),
                        ),
                    ),
                ),
                role = GuiElementRole.ACTION,
                amount = 1,
            ),
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun changePage(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(MenuUpdate.Replace(listRoute(state(context.payload))))

    private fun cycleVisibilityFilter(context: MenuActionContext): MenuActionResult {
        val direction = GuiCycle.direction(context.click) ?: return MenuActionResult.Ignored
        val current = state(context.route)
        return MenuActionResult.Success(
            MenuUpdate.Replace(
                listRoute(
                    current.copy(
                        page = 0,
                        visibilityFilter = GuiCycle.select(
                            current.visibilityFilter,
                            VisibilityFilter.entries,
                            direction,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun cycleSort(context: MenuActionContext): MenuActionResult {
        val direction = GuiCycle.direction(context.click) ?: return MenuActionResult.Ignored
        val current = state(context.route)
        return MenuActionResult.Success(
            MenuUpdate.Replace(
                listRoute(
                    current.copy(
                        page = 0,
                        sortType = GuiCycle.select(
                            current.sortType,
                            SortType.entries,
                            direction,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun handleWorld(context: MenuActionContext): MenuActionResult {
        val uuid = context.payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(uuid)
            ?: return MenuActionResult.Rejected()
        return when {
            context.click == ClickType.MIDDLE &&
                context.player.gameMode == GameMode.CREATIVE -> {
                sendCopyMessage(context.player, worldData)
                MenuActionResult.Success(MenuUpdate.None)
            }
            context.click.isLeftClick -> {
                MyWorldManagerApi.getWorldService()?.teleportToWorld(context.player, uuid)
                MenuActionResult.Success(MenuUpdate.Close)
            }
            context.click.isRightClick -> {
                openSettings(context.player, worldData)
            }
            else -> MenuActionResult.Ignored
        }
    }

    private fun filteredWorlds(
        player: Player,
        visibilityFilter: VisibilityFilter,
        sortType: SortType,
    ): List<WorldData> {
        val current = currentWorld(player)?.uuid
        val filtered = MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            .filter { it.uuid != current }
            .filter { worldData ->
                when (visibilityFilter) {
                    VisibilityFilter.ALL -> true
                    VisibilityFilter.PUBLIC ->
                        storage.getVisibility(worldData.uuid) == ChanponWorldVisibility.PUBLIC
                    VisibilityFilter.DEVELOPMENT ->
                        storage.getVisibility(worldData.uuid) == ChanponWorldVisibility.DEVELOPMENT
                    VisibilityFilter.PRIVATE ->
                        storage.getVisibility(worldData.uuid) == ChanponWorldVisibility.PRIVATE
                }
            }
        return when (sortType) {
            SortType.CREATED_DESC -> filtered.sortedByDescending { it.createdAt }
            SortType.CREATED_ASC -> filtered.sortedBy { it.createdAt }
            SortType.WORLD_SIZE_DESC -> filtered.sortedByDescending {
                worldFolder(it).let(::folderSize)
            }
        }
    }

    private fun currentWorld(player: Player): WorldData? =
        MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)

    private fun worldItem(player: Player, worldData: WorldData, current: Boolean): ItemStack =
        ItemStack(worldData.icon).also { item ->
            val visibility = storage.getVisibility(worldData.uuid)
            item.editMeta { meta ->
                meta.displayName(
                    i18nComponent(
                        player,
                        "admin_list.world.display",
                        mapOf("world" to worldData.name),
                    ),
                )
                meta.lore(structuredLore(worldLore(player, worldData, visibility, current)))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun worldLore(
        player: Player,
        worldData: WorldData,
        visibility: ChanponWorldVisibility,
        current: Boolean,
    ): List<GuiLoreLine> {
        val owner = Bukkit.getOfflinePlayer(worldData.owner).name
            ?: i18n(player, "admin_list.unknown")
        val members = (worldData.moderators + worldData.members)
            .distinct()
            .map {
                Bukkit.getOfflinePlayer(it).name ?: i18n(player, "admin_list.unknown")
            }
            .let {
                if (it.isEmpty()) {
                    i18n(player, "admin_list.world.no_members")
                } else {
                    it.joinToString("§7, §f", prefix = "§f")
                }
            }
        return buildList {
            add(GuiLoreLine.Data(i18n(player, "admin_list.world.owner_label"), owner, "§f"))
            add(GuiLoreLine.Data(i18n(player, "admin_list.world.members_label"), members, ""))
            add(
                GuiLoreLine.Data(
                    i18n(player, "admin_list.world.created_label"),
                    formatCreatedAt(worldData.createdAt),
                    "§f",
                ),
            )
            add(
                GuiLoreLine.Data(
                    i18n(player, "admin_list.world.visibility_label"),
                    visibilityName(player, visibility),
                    "",
                ),
            )
            add(
                GuiLoreLine.Data(
                    i18n(player, "admin_list.world.directory_label"),
                    worldDirectory(worldData),
                    "§f",
                ),
            )
            add(GuiLoreLine.Spacer)
            add(
                GuiLoreLine.Action(
                    CCSystem.getAPI().getGuiActionService()
                        .clickLabel(player, GuiClickLabel.LEFT),
                    i18n(player, "admin_list.world.action_warp"),
                ),
            )
            if (current) {
                add(GuiLoreLine.Text(i18n(player, "admin_list.world.action_settings_current")))
            } else {
                add(
                    GuiLoreLine.Action(
                        CCSystem.getAPI().getGuiActionService()
                            .clickLabel(player, GuiClickLabel.RIGHT),
                        i18n(player, "admin_list.world.action_settings"),
                    ),
                )
            }
            if (player.gameMode == GameMode.CREATIVE) {
                add(
                    GuiLoreLine.Action(
                        i18n(player, "common.middle_click"),
                        i18n(player, "admin_list.world.action_copy"),
                    ),
                )
            }
        }
    }

    private fun visibilityName(
        player: Player,
        visibility: ChanponWorldVisibility,
    ): String = when (visibility) {
        ChanponWorldVisibility.PUBLIC -> i18n(player, "admin_list.visibility.public")
        ChanponWorldVisibility.DEVELOPMENT ->
            i18n(player, "admin_list.visibility.development")
        ChanponWorldVisibility.PRIVATE -> i18n(player, "admin_list.visibility.private")
    }

    private fun visibilityFilterItem(
        player: Player,
        filter: VisibilityFilter,
    ): ItemStack = cycleControlItem(
        player,
        Material.ENDER_EYE,
        "admin_list.visibility_filter",
        i18n(player, filter.key),
    )

    private fun sortItem(player: Player, sortType: SortType): ItemStack =
        cycleControlItem(
            player,
            Material.COMPARATOR,
            "admin_list.sort",
            i18n(player, sortType.key),
        )

    private fun cycleControlItem(
        player: Player,
        material: Material,
        key: String,
        current: String,
    ): ItemStack = ItemStack(material).also { item ->
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(
                structuredLore(
                    listOf(
                        GuiLoreLine.Data(i18n(player, "$key.label"), current, "§e"),
                    ) + CCSystem.getAPI().getGuiActionService().cycle(player),
                ),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
    }

    private fun infoItem(
        player: Player,
        total: Int,
        page: Int,
        totalPages: Int,
    ): ItemStack = ItemStack(Material.BOOK).also { item ->
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_list.info.display"))
            meta.lore(
                ChanponLore.build(
                    i18nList(
                        player,
                        "admin_list.info.lore",
                        mapOf("count" to total, "page" to page, "pages" to totalPages),
                    ).map(GuiLoreLine::Text),
                ),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
    }

    private fun navItem(player: Player, next: Boolean): ItemStack =
        ItemStack(Material.ARROW).also { item ->
            item.editMeta { meta ->
                meta.displayName(
                    i18nComponent(player, if (next) "admin_list.next" else "admin_list.prev"),
                )
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun backItem(player: Player): ItemStack =
        ItemStack(Material.REDSTONE).also { item ->
            item.editMeta { meta ->
                meta.displayName(i18nComponent(player, "admin_list.back.display"))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun openSettings(player: Player, worldData: WorldData): MenuActionResult {
        val folderName = worldDirectory(worldData)
        if (!worldData.isArchived && Bukkit.getWorld(folderName) == null) {
            player.sendMessage(CCSystem.getAPI().getI18nString(player, "messages.world_loading"))
            runtime.suspendForExternal(player)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!player.isOnline) {
                    runtime.completeExternal(player)
                    return@Runnable
                }
                if (MyWorldManagerApi.getWorldService()?.loadWorld(worldData.uuid) != true) {
                    player.sendMessage(CCSystem.getAPI().getI18nString(player, "error.load_failed"))
                    runtime.resumeFromExternal(player)
                    return@Runnable
                }
                val route = settingsRoute(player, worldData)
                if (route == null) {
                    runtime.resumeFromExternal(player)
                } else {
                    runtime.navigate(player, route)
                }
            })
            return MenuActionResult.Success(MenuUpdate.None)
        }
        val route = settingsRoute(player, worldData) ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Navigate(route))
    }

    private fun settingsRoute(player: Player, worldData: WorldData): MenuRoute? =
        MyWorldManagerApi.prepareWorldSettingsRoute(
            player,
            worldData.uuid,
            WorldSettingsNavigationRequest(
                showBackButton = true,
                isAdminFlow = true,
            ),
        )

    private fun sendCopyMessage(player: Player, worldData: WorldData) {
        val directory = worldDirectory(worldData)
        player.sendMessage(Component.text("§8§m----------------"))
        player.sendMessage(Component.text("§e${worldData.name}"))
        player.sendMessage(
            Component.text("§7World: §f$directory")
                .clickEvent(ClickEvent.copyToClipboard(directory)),
        )
        player.sendMessage(
            Component.text("§7Owner: §f${worldData.owner}")
                .clickEvent(ClickEvent.copyToClipboard(worldData.owner.toString())),
        )
        player.sendMessage(Component.text("§8§m----------------"))
    }

    private fun formatCreatedAt(value: String): String = runCatching {
        LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
    }.getOrDefault(value)

    private fun worldDirectory(worldData: WorldData): String =
        worldData.customWorldName ?: "my_world.${worldData.uuid}"

    private fun worldFolder(worldData: WorldData): java.io.File =
        MyWorldManagerApi.getWorldService()?.getWorldDirectory(worldData)
            ?: java.io.File(plugin.dataFolder, "unavailable")

    private fun folderSize(file: java.io.File): Long {
        if (!file.exists()) return 0L
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun pageCount(itemCount: Int): Int =
        if (itemCount == 0) 1 else (itemCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE

    private fun listRoute(state: ListState): MenuRoute =
        MenuRoute(OWNER, ROUTE_LIST, statePayload(state))

    private fun state(route: MenuRoute): ListState = state(route.payload)

    private fun state(payload: Map<String, String>): ListState = ListState(
        page = payload[PAGE]?.toIntOrNull() ?: 0,
        visibilityFilter = payload[VISIBILITY_FILTER]
            ?.let { runCatching { VisibilityFilter.valueOf(it) }.getOrNull() }
            ?: VisibilityFilter.ALL,
        sortType = payload[SORT_TYPE]
            ?.let { runCatching { SortType.valueOf(it) }.getOrNull() }
            ?: SortType.CREATED_DESC,
        fromAdminMenu = payload[FROM_ADMIN_MENU]?.toBooleanStrictOrNull() ?: false,
    )

    private fun statePayload(state: ListState): Map<String, String> = mapOf(
        PAGE to state.page.toString(),
        VISIBILITY_FILTER to state.visibilityFilter.name,
        SORT_TYPE to state.sortType.name,
        FROM_ADMIN_MENU to state.fromAdminMenu.toString(),
    )

    private fun structuredLore(lines: List<GuiLoreLine>): List<Component> =
        CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(lines, GuiLoreFrame.BOTH),
        )

    private fun i18n(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ): String = CCSystem.getAPI().getI18nString(
        player,
        "chanpon.submission.$key",
        placeholders,
    )

    private fun i18nList(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ): List<String> = CCSystem.getAPI().getI18nStringList(
        player,
        "chanpon.submission.$key",
        placeholders,
    )

    private fun i18nComponent(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ): Component = CCSystem.getAPI().getI18nComponent(
        player,
        "chanpon.submission.$key",
        placeholders,
    )

    private data class ListState(
        val page: Int = 0,
        val visibilityFilter: VisibilityFilter = VisibilityFilter.ALL,
        val sortType: SortType = SortType.CREATED_DESC,
        val fromAdminMenu: Boolean = false,
    )

    enum class VisibilityFilter(val key: String) {
        ALL("admin_list.visibility_filter.all"),
        PUBLIC("admin_list.visibility_filter.public"),
        DEVELOPMENT("admin_list.visibility_filter.development"),
        PRIVATE("admin_list.visibility_filter.private"),
    }

    enum class SortType(val key: String) {
        CREATED_DESC("admin_list.sort.created_desc"),
        CREATED_ASC("admin_list.sort.created_asc"),
        WORLD_SIZE_DESC("admin_list.sort.world_size_desc"),
    }

    companion object {
        private const val WORLD_LIST_CAPABILITY = "admin-world-list"
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE_LIST = "admin-world-list"
        private const val ACTION_BACK = "back"
        private const val ACTION_PAGE = "page"
        private const val ACTION_VISIBILITY_FILTER = "visibility-filter"
        private const val ACTION_SORT = "sort"
        private const val ACTION_WORLD = "world"
        private const val PAGE = "page"
        private const val VISIBILITY_FILTER = "visibility-filter"
        private const val SORT_TYPE = "sort-type"
        private const val FROM_ADMIN_MENU = "from-admin-menu"
        private const val WORLD_UUID = "world-uuid"
        private const val ITEMS_PER_PAGE = 36
    }
}
