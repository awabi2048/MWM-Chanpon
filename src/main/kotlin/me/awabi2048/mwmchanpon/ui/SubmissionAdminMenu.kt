package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.GuiLoreActions
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class SubmissionAdminMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
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
                    ACTION_PROGRESS_FILTER to MenuActionHandler(::cycleProgressFilter),
                    ACTION_SORT to MenuActionHandler(::cycleSort),
                    ACTION_CLOSE_SUBMISSIONS to MenuActionHandler(::toggleSubmissionClosure),
                    ACTION_WORLD to MenuActionHandler(::handleWorldAction),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_CLOSE_CONFIRM,
                renderer = { context -> renderCloseSubmissionConfirmation(context.player) },
                actions = confirmationActions(ACTION_CONFIRM_CLOSE, ::confirmCloseSubmissions),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_PRODUCTION_CONFIRM,
                renderer = { context ->
                    renderProductionToggleConfirmation(context.player, context.route)
                },
                actions = confirmationActions(ACTION_CONFIRM_PRODUCTION, ::confirmProductionToggle),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_COMPLETE_CONFIRM,
                renderer = { context -> renderCompleteConfirmation(context.player) },
                actions = confirmationActions(ACTION_CONFIRM_COMPLETE, ::confirmCompleteUnsubmitted),
            ),
        )
    }

    fun setParentMenuOpener(opener: (Player) -> Unit) {
        parentMenuOpener = opener
    }

    fun open(
        player: Player,
        page: Int = 0,
        progressFilter: ProgressFilter = ProgressFilter.ALL,
        sortType: SortType = SortType.CREATED_DESC
    ) {
        if (!player.hasPermission("myworldmanager.admin")) {
            player.sendMessage(i18nComponent(player, "admin_menu.no_permission"))
            return
        }
        JavaPluginBridge.endCoreMenuSession(player)
        runtime.open(player, listRoute(SubmissionViewState(page, progressFilter, sortType)))
    }

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val requested = state(route)
        val worlds = filteredWorlds(requested.progressFilter, requested.sortType)
        val totalPages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = requested.page.coerceIn(0, totalPages - 1)
        val current = requested.copy(page = safePage)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += MenuElement(
                layout.previousPageSlot,
                navItem(player, false),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                statePayload(current.copy(page = safePage - 1)),
            )
        }
        if (safePage < totalPages - 1) {
            elements += MenuElement(
                layout.nextPageSlot,
                navItem(player, true),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                statePayload(current.copy(page = safePage + 1)),
            )
        }
        elements += MenuElement(layout.backSlot, backItem(player), GuiElementRole.BACK, ACTION_BACK)
        elements += MenuElement(layout.infoSlot, statsItem(player), GuiElementRole.CONTENT)
        worlds.drop(safePage * ITEMS_PER_PAGE)
            .take(ITEMS_PER_PAGE)
            .forEachIndexed { index, worldData ->
                elements += MenuElement(
                    layout.itemSlots[index],
                    worldItem(player, worldData),
                    GuiElementRole.ACTION,
                    ACTION_WORLD,
                    statePayload(current) + (WORLD_UUID to worldData.uuid.toString()),
                )
            }
        elements += MenuElement(
            47,
            progressFilterItem(player, current.progressFilter),
            GuiElementRole.ACTION,
            ACTION_PROGRESS_FILTER,
        )
        elements += MenuElement(
            51,
            sortItem(player, current.sortType),
            GuiElementRole.ACTION,
            ACTION_SORT,
        )
        elements += MenuElement(
            52,
            closeSubmissionItem(player),
            GuiElementRole.ACTION,
            ACTION_CLOSE_SUBMISSIONS,
        )
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "admin_menu.title"),
            elements,
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        val opener = parentMenuOpener ?: return MenuActionResult.Success(MenuUpdate.Back)
        Bukkit.getScheduler().runTask(plugin, Runnable { opener(context.player) })
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun changePage(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(MenuUpdate.Replace(listRoute(state(context.payload))))

    private fun cycleProgressFilter(context: MenuActionContext): MenuActionResult {
        val direction = GuiCycle.direction(context.click) ?: return MenuActionResult.Ignored
        val current = state(context.route)
        return MenuActionResult.Success(
            MenuUpdate.Replace(
                listRoute(
                    current.copy(
                        page = 0,
                        progressFilter = GuiCycle.select(
                            current.progressFilter,
                            ProgressFilter.entries,
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
                        sortType = GuiCycle.select(current.sortType, SortType.entries, direction),
                    ),
                ),
            ),
        )
    }

    private fun toggleSubmissionClosure(context: MenuActionContext): MenuActionResult {
        if (plugin.isSubmissionClosed()) {
            plugin.config.set("submission.closed", false)
            plugin.saveConfig()
            context.player.sendMessage(i18nComponent(context.player, "admin_menu.reopened_message"))
            return MenuActionResult.Success(MenuUpdate.Refresh)
        }
        return MenuActionResult.Success(
            MenuUpdate.Navigate(
                MenuRoute(OWNER, ROUTE_CLOSE_CONFIRM, statePayload(state(context.route))),
            ),
        )
    }

    private fun handleWorldAction(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: return MenuActionResult.Rejected()
        val current = state(context.route)
        if (context.click == ClickType.MIDDLE) {
            if (!canToggleProduction(context.player)) return MenuActionResult.Rejected()
            val enabling = !storage.isProductionWorld(worldUuid)
            return MenuActionResult.Success(
                MenuUpdate.Navigate(
                    MenuRoute(
                        OWNER,
                        ROUTE_PRODUCTION_CONFIRM,
                        statePayload(current) + mapOf(
                            WORLD_UUID to worldUuid.toString(),
                            ENABLING to enabling.toString(),
                        ),
                    ),
                ),
            )
        }
        if (context.click.isRightClick) {
            if (context.player.gameMode != GameMode.CREATIVE) return MenuActionResult.Rejected()
            return when (stateFor(worldData)) {
                SubmissionState.COMPLETED ->
                    unmarkCompleted(context.player, worldData, current)
                SubmissionState.SUBMITTED ->
                    markCompleted(context.player, worldData, current)
                SubmissionState.UNSUBMITTED -> MenuActionResult.Success(
                    MenuUpdate.Navigate(
                        MenuRoute(
                            OWNER,
                            ROUTE_COMPLETE_CONFIRM,
                            statePayload(current) + (WORLD_UUID to worldUuid.toString()),
                        ),
                    ),
                )
            }
        }
        if (!context.click.isLeftClick) return MenuActionResult.Ignored
        MyWorldManagerApi.getWorldService()?.teleportToWorld(context.player, worldUuid)
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun markCompleted(
        player: Player,
        worldData: WorldData,
        source: SubmissionViewState,
    ): MenuActionResult {
        val current = stateFor(worldData)
        if (current == SubmissionState.COMPLETED) return MenuActionResult.Rejected()

        if (!storage.isProductionWorld(worldData.uuid)) {
            player.sendMessage(i18nComponent(player, "admin_menu.not_production"))
            return MenuActionResult.Rejected()
        }
        storage.setSubmissionState(
            worldData.uuid,
            SubmissionState.COMPLETED,
            player.uniqueId,
            player.name,
            "mark_completed"
        )
        protectionService.sync(worldData)
        toolPermissionController.syncWorld(worldData)
        player.sendMessage(i18nComponent(player, "admin_menu.completed_message"))
        return MenuActionResult.Success(MenuUpdate.Replace(listRoute(source)))
    }

    private fun unmarkCompleted(
        player: Player,
        worldData: WorldData,
        source: SubmissionViewState,
    ): MenuActionResult {
        val current = stateFor(worldData)
        if (current != SubmissionState.COMPLETED) return MenuActionResult.Rejected()

        storage.setSubmissionState(
            worldData.uuid,
            SubmissionState.SUBMITTED,
            player.uniqueId,
            player.name,
            "unmark_completed"
        )
        protectionService.sync(worldData)
        toolPermissionController.syncWorld(worldData)
        player.sendMessage(i18nComponent(player, "admin_menu.unmarked_message"))
        return MenuActionResult.Success(MenuUpdate.Replace(listRoute(source)))
    }

    private fun filteredWorlds(
        progressFilter: ProgressFilter,
        sortType: SortType
    ): List<WorldData> {
        val filtered = MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            // 提出状況メニューは提出運用対象である本番用ワールドだけを扱う。
            .filter { storage.isProductionWorld(it.uuid) }
            .filter { worldData ->
                val state = stateFor(worldData)
                matchesProgressFilter(state, progressFilter)
            }
        return when (sortType) {
            SortType.CREATED_DESC -> filtered.sortedByDescending { it.createdAt }
            SortType.CREATED_ASC -> filtered.sortedBy { it.createdAt }
            SortType.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
        }
    }

    private fun matchesProgressFilter(state: SubmissionState, filter: ProgressFilter): Boolean {
        return when (filter) {
            ProgressFilter.ALL -> true
            ProgressFilter.UNSUBMITTED -> state == SubmissionState.UNSUBMITTED
            ProgressFilter.SUBMITTED -> state == SubmissionState.SUBMITTED
            ProgressFilter.COMPLETED -> state == SubmissionState.COMPLETED
        }
    }

    private fun stateFor(worldData: WorldData): SubmissionState {
        if (!storage.isProductionWorld(worldData.uuid)) return SubmissionState.UNSUBMITTED
        return runCatching { storage.getSubmissionState(worldData.uuid) }.getOrDefault(SubmissionState.UNSUBMITTED)
    }

    private fun productionWorlds(): List<WorldData> {
        return MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
    }

    private fun statsItem(player: Player): ItemStack {
        val worlds = productionWorlds()
        val states = worlds.mapNotNull { runCatching { storage.getSubmissionState(it.uuid) }.getOrNull() }
        val submitted = states.count { it == SubmissionState.SUBMITTED || it == SubmissionState.COMPLETED }
        val completed = states.count { it == SubmissionState.COMPLETED }
        val item = ItemStack(Material.WRITABLE_BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.stats.display"))
            meta.lore(
                ChanponLore.build(
                    i18nList(
                        player,
                        "admin_menu.stats.lore",
                        mapOf(
                            "submitted" to submitted,
                            "total" to worlds.size,
                            "completed" to completed
                        )
                    ).map(GuiLoreLine::Text)
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun renderCloseSubmissionConfirmation(player: Player): InventoryMenuView =
        confirmationView(
            player,
            "confirm.close_submissions.title",
            closeSubmissionConfirmationItem(player),
            ACTION_CONFIRM_CLOSE,
        )

    private fun renderProductionToggleConfirmation(
        player: Player,
        route: MenuRoute,
    ): InventoryMenuView {
        val enabling = route.payload[ENABLING]?.toBooleanStrictOrNull()
            ?: error("本番用ワールド切替方向がありません")
        val key = if (enabling) "confirm.set_production" else "confirm.unset_production"
        return confirmationView(
            player,
            "$key.title",
            productionToggleConfirmationItem(player, key),
            ACTION_CONFIRM_PRODUCTION,
        )
    }

    private fun renderCompleteConfirmation(player: Player): InventoryMenuView =
        confirmationView(
            player,
            "confirm.complete_unsubmitted.title",
            completeUnsubmittedConfirmationItem(player),
            ACTION_CONFIRM_COMPLETE,
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
                    confirmButton(player, true),
                    GuiElementRole.CONFIRM,
                    confirmAction,
                ),
                MenuElement(
                    layout.cancelSlot,
                    confirmButton(player, false),
                    GuiElementRole.CANCEL,
                    ACTION_CANCEL,
                ),
            ),
        )
    }

    private fun confirmationActions(
        confirmAction: String,
        confirmHandler: (MenuActionContext) -> MenuActionResult,
    ): Map<String, MenuActionHandler> = mapOf(
        ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
        confirmAction to MenuActionHandler(confirmHandler),
    )

    private fun confirmCloseSubmissions(context: MenuActionContext): MenuActionResult {
        plugin.config.set("submission.closed", true)
        plugin.saveConfig()
        context.player.sendMessage(i18nComponent(context.player, "admin_menu.closed_message"))
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun confirmProductionToggle(context: MenuActionContext): MenuActionResult {
        val player = context.player
        if (!canToggleProduction(player)) return MenuActionResult.Rejected()
        val worldUuid = context.route.payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        val enabling = context.route.payload[ENABLING]?.toBooleanStrictOrNull()
            ?: return MenuActionResult.Rejected()
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: return MenuActionResult.Rejected()
        if (enabling && ownerHasProductionWorld(worldData.owner, worldUuid)) {
            player.sendMessage(i18nComponent(player, "admin_menu.production_limit"))
            return MenuActionResult.Rejected()
        }
        storage.setProductionWorld(worldUuid, enabling)
        if (enabling) applyForcedTags(worldData)
        protectionService.sync(worldData)
        toolPermissionController.syncWorld(worldData)
        player.sendMessage(
            i18nComponent(
                player,
                if (enabling) {
                    "admin_menu.production_enabled"
                } else {
                    "admin_menu.production_disabled"
                },
            ),
        )
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun confirmCompleteUnsubmitted(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.route.payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: return MenuActionResult.Rejected()
        val result = markCompleted(context.player, worldData, state(context.route))
        return if (result is MenuActionResult.Success) {
            MenuActionResult.Success(MenuUpdate.Back)
        } else {
            result
        }
    }

    private fun worldItem(player: Player, worldData: WorldData): ItemStack {
        val state = stateFor(worldData)
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.world.display", mapOf("world" to worldData.name)))
            meta.lore(structuredLore(worldLore(player, worldData, state)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun worldLore(player: Player, worldData: WorldData, state: SubmissionState): List<GuiLoreLine> {
        val unknown = i18n(player, "admin_menu.unknown")
        val ownerName = playerName(worldData.owner, unknown)
        val members = (worldData.moderators + worldData.members)
            .distinct()
            .map { playerName(it, unknown) }
        val memberLine = if (members.isEmpty()) {
            i18n(player, "admin_menu.world.no_members")
        } else {
            members.joinToString("§7, §f", prefix = "§f")
        }
        val created = formatCreatedAt(worldData.createdAt)
        val production = if (storage.isProductionWorld(worldData.uuid)) {
            i18n(player, "admin_menu.world.production_yes")
        } else {
            i18n(player, "admin_menu.world.production_no")
        }
        val completed = if (state == SubmissionState.COMPLETED) {
            i18n(player, "admin_menu.world.completed_yes")
        } else {
            i18n(player, "admin_menu.world.completed_no")
        }
        return buildList {
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.owner_label"), ownerName, "§f"))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.members_label"), memberLine, ""))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.created_label"), created, "§f"))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.production_label"), production, ""))
            add(GuiLoreLine.Data(
                i18n(player, "admin_menu.world.state_label"),
                i18n(player, "state.${state.i18nKey}"),
                when (state) {
                    SubmissionState.UNSUBMITTED -> "§6"
                    SubmissionState.SUBMITTED -> "§a"
                    SubmissionState.COMPLETED -> "§b"
                }
            ))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.completed_label"), completed, ""))
            add(GuiLoreLine.Spacer)
            add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.left"), i18n(player, "admin_menu.world.action_warp")))
            if (player.gameMode == GameMode.CREATIVE) {
                add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.right"), i18n(player, "admin_menu.world.action_complete")))
            }
            add(GuiLoreLine.Action(i18n(player, "common.middle_click"), i18n(player, "admin_menu.world.action_toggle_production")))
        }
    }

    private fun productionToggleConfirmationItem(player: Player, key: String): ItemStack {
        val item = ItemStack(Material.HEART_OF_THE_SEA)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun completeUnsubmittedConfirmationItem(player: Player): ItemStack {
        val item = ItemStack(Material.GOLDEN_APPLE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "confirm.complete_unsubmitted.display"))
            meta.lore(ChanponLore.build(i18nList(player, "confirm.complete_unsubmitted.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun ownerHasProductionWorld(ownerUuid: UUID, excludingWorldUuid: UUID): Boolean {
        return MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            .any { it.owner == ownerUuid && it.uuid != excludingWorldUuid && storage.isProductionWorld(it.uuid) }
    }

    private fun canToggleProduction(player: Player): Boolean {
        val decision = MyWorldManagerApi.checkWorldCreation(
            me.awabi2048.myworldmanager.api.extension.WorldCreationRequest(
                actor = player,
                player = player,
                operation = me.awabi2048.myworldmanager.api.extension.WorldCreationOperation.PRODUCTION,
                type = null
            )
        )
        if (!decision.allowed) decision.denialMessage?.let(player::sendMessage)
        return decision.allowed
    }

    private fun applyForcedTags(worldData: WorldData) {
        val tags = plugin.config.getStringList("production.forced_tags")
            .mapNotNull { raw ->
                MyWorldManagerApi.getWorldTagService()?.normalizeTagId(raw)
                    ?: raw.trim().lowercase().takeIf { it.isNotBlank() }
            }
            .distinct()
        val missing = tags.filterNot { worldData.tags.contains(it) }
        if (missing.isEmpty()) return
        worldData.tags.addAll(missing)
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
    }

    private fun formatCreatedAt(value: String): String {
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        }.getOrDefault(value)
    }

    private fun playerName(uuid: UUID, fallback: String): String {
        return Bukkit.getOfflinePlayer(uuid).name ?: fallback
    }

    private fun navItem(player: Player, next: Boolean): ItemStack {
        val item = ItemStack(Material.ARROW)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, if (next) "admin_menu.next" else "admin_menu.prev"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.back.display"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun progressFilterItem(player: Player, filter: ProgressFilter): ItemStack {
        return filterListItem(
            player,
            Material.HOPPER,
            "admin_menu.progress_filter.display",
            ProgressFilter.entries.map { it.key },
            filter.key,
        )
    }

    private fun sortItem(player: Player, sortType: SortType): ItemStack {
        return filterListItem(
            player,
            Material.COMPARATOR,
            "admin_menu.sort.display",
            SortType.entries.map { it.key },
            sortType.key,
        )
    }

    private fun filterListItem(
        player: Player,
        material: Material,
        displayKey: String,
        optionKeys: List<String>,
        selectedKey: String,
    ): ItemStack {
        val item = ItemStack(material)
        val lore = CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(buildList {
                optionKeys.forEach { optionKey ->
                    val selected = optionKey == selectedKey
                    add(GuiLoreLine.Option(i18n(player, optionKey), selected, "\u00A7f", "\u00A77"))
                }
                add(GuiLoreLine.Spacer)
                addAll(GuiLoreActions.cyclePreviousNext(mwm().languageManager, player))
            }, GuiLoreFrame.BOTH)
        )
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, displayKey))
            meta.lore(lore)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun closeSubmissionItem(player: Player): ItemStack {
        val key = if (plugin.isSubmissionClosed()) {
            "admin_menu.close.closed"
        } else {
            "admin_menu.close.open"
        }
        return extensionItem(
            player,
            if (plugin.isSubmissionClosed()) Material.BARRIER else Material.REDSTONE_BLOCK,
            "$key.display",
            "$key.lore",
        )
    }

    private fun closeSubmissionConfirmationItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE_BLOCK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "confirm.close_submissions.display"))
            meta.lore(ChanponLore.build(i18nList(player, "confirm.close_submissions.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun confirmButton(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        val key = if (confirm) "confirm.button.confirm" else "confirm.button.cancel"
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun extensionItem(
        player: Player,
        material: Material,
        displayKey: String,
        loreKey: String,
        placeholders: Map<String, Any> = emptyMap()
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, displayKey, placeholders))
            meta.lore(ChanponLore.build(i18nList(player, loreKey, placeholders).map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun structuredLore(lines: List<GuiLoreLine>): List<Component> {
        return CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(lines, GuiLoreFrame.BOTH))
    }

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.submission.$key", placeholders)
    }

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)

    enum class ProgressFilter(val key: String) {
        ALL("admin_menu.progress_filter.all"),
        UNSUBMITTED("admin_menu.progress_filter.unsubmitted"),
        SUBMITTED("admin_menu.progress_filter.submitted"),
        COMPLETED("admin_menu.progress_filter.completed")
    }

    enum class SortType(val key: String) {
        CREATED_DESC("admin_menu.sort.created_desc"),
        CREATED_ASC("admin_menu.sort.created_asc"),
        NAME_ASC("admin_menu.sort.name_asc")
    }

    private data class SubmissionViewState(
        val page: Int,
        val progressFilter: ProgressFilter,
        val sortType: SortType,
    )

    private fun listRoute(state: SubmissionViewState): MenuRoute =
        MenuRoute(OWNER, ROUTE_LIST, statePayload(state))

    private fun state(route: MenuRoute): SubmissionViewState = state(route.payload)

    private fun state(payload: Map<String, String>): SubmissionViewState =
        SubmissionViewState(
            page = payload[PAGE]?.toIntOrNull() ?: 0,
            progressFilter = payload[PROGRESS_FILTER]
                ?.let { runCatching { ProgressFilter.valueOf(it) }.getOrNull() }
                ?: ProgressFilter.ALL,
            sortType = payload[SORT_TYPE]
                ?.let { runCatching { SortType.valueOf(it) }.getOrNull() }
                ?: SortType.CREATED_DESC,
        )

    private fun statePayload(state: SubmissionViewState): Map<String, String> = mapOf(
        PAGE to state.page.toString(),
        PROGRESS_FILTER to state.progressFilter.name,
        SORT_TYPE to state.sortType.name,
    )

    private object JavaPluginBridge {
        fun endCoreMenuSession(player: Player) {
            val mwm = org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)
            mwm.settingsSessionManager.endSession(player)
        }
    }

    companion object {
        const val EXTENSION_ID = "chanpon.submission_admin_menu"
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE_LIST = "submission-admin"
        private const val ROUTE_CLOSE_CONFIRM = "submission-close-confirm"
        private const val ROUTE_PRODUCTION_CONFIRM = "submission-production-confirm"
        private const val ROUTE_COMPLETE_CONFIRM = "submission-complete-confirm"
        private const val ITEMS_PER_PAGE = 36
        private const val ACTION_PROGRESS_FILTER = "progress_filter"
        private const val ACTION_SORT = "sort"
        private const val ACTION_CLOSE_SUBMISSIONS = "close_submissions"
        private const val ACTION_BACK = "back"
        private const val ACTION_PAGE = "page"
        private const val ACTION_WORLD = "world"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_CONFIRM_CLOSE = "confirm-close"
        private const val ACTION_CONFIRM_PRODUCTION = "confirm-production"
        private const val ACTION_CONFIRM_COMPLETE = "confirm-complete"
        private const val PAGE = "page"
        private const val PROGRESS_FILTER = "progress-filter"
        private const val SORT_TYPE = "sort-type"
        private const val WORLD_UUID = "world-uuid"
        private const val ENABLING = "enabling"
    }
}
