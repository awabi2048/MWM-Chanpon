package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.LocalizationKey

import com.awabi2048.ccsystem.api.localization.generated.ChanponSubmissionKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiInteractionGuidance
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryOption
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiValueTone
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
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
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.mwmchanpon.service.ChanponReversibleContracts
import me.awabi2048.mwmchanpon.service.ChanponReversibleStateProviders
import me.awabi2048.mwmchanpon.service.SubmissionCompletionMacroService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class SubmissionAdminMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController,
    private val submissionCompletionMacroService: SubmissionCompletionMacroService,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        ChanponReversibleStateProviders.registerSubmission(
            CCSystem.getAPI().getMenuReversibleStateProviderRegistry(),
            plugin,
            storage,
            protectionService,
            toolPermissionController,
        )
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
                renderer = { context -> renderCompleteConfirmation(context.player, context.route) },
                actions = confirmationActions(ACTION_CONFIRM_COMPLETE, ::confirmCompleteUnsubmitted),
            ),
        )
    }

    fun open(
        player: Player,
        page: Int = 0,
        progressFilter: ProgressFilter = ProgressFilter.ALL,
        sortType: SortType = SortType.CREATED_DESC
    ) {
        if (!player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_NO_PERMISSION))
            return
        }
        JavaPluginBridge.endCoreMenuSession(player)
        runtime.navigate(player, listRoute(SubmissionViewState(page, progressFilter, sortType)))
    }

    internal fun route(
        page: Int = 0,
        progressFilter: ProgressFilter = ProgressFilter.ALL,
        sortType: SortType = SortType.CREATED_DESC,
    ): MenuRoute = listRoute(SubmissionViewState(page, progressFilter, sortType))

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val requested = state(route)
        val worlds = filteredWorlds(requested.progressFilter, requested.sortType)
        val totalPages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = requested.page.coerceIn(0, totalPages - 1)
        val current = requested.copy(page = safePage)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += simpleActionEntry(
                player, layout.previousPageSlot, Material.ARROW,
                i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PREV), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PREV_ACTION), statePayload(current.copy(page = safePage - 1)),
            )
        }
        if (safePage < totalPages - 1) {
            elements += simpleActionEntry(
                player, layout.nextPageSlot, Material.ARROW,
                i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_NEXT), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_NEXT_ACTION), statePayload(current.copy(page = safePage + 1)),
            )
        }
        elements += simpleActionEntry(
            player, layout.backSlot, Material.REDSTONE,
            i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_BACK_DISPLAY), GuiElementRole.BACK, ACTION_BACK,
            i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_BACK_ACTION),
        )
        elements += statsEntry(player, layout.infoSlot)
        worlds.drop(safePage * ITEMS_PER_PAGE)
            .take(ITEMS_PER_PAGE)
            .forEachIndexed { index, worldData ->
                elements += worldEntry(
                    player,
                    layout.itemSlots[index],
                    worldData,
                    statePayload(current) + (WORLD_UUID to worldData.uuid.toString()),
                )
            }
        elements += filterEntry(
            player,
            47,
            Material.HOPPER,
            ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PROGRESS_FILTER_DISPLAY,
            ProgressFilter.entries.map { it.key },
            current.progressFilter.key,
            ACTION_PROGRESS_FILTER,
        )
        elements += filterEntry(
            player,
            51,
            Material.COMPARATOR,
            ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_SORT_DISPLAY,
            SortType.entries.map { it.key },
            current.sortType.key,
            ACTION_SORT,
        )
        elements += closeSubmissionItem(player)
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_TITLE),
            elements,
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
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
            if (!plugin.isConfigurationMigrationCurrent()) return MenuActionResult.Rejected()
            plugin.config.set("submission.closed", false)
            plugin.saveConfigIfCurrent()
            context.player.sendMessage(i18nComponent(context.player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_REOPENED_MESSAGE))
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
            if (!storage.isProductionWorld(worldUuid)) return MenuActionResult.Rejected()
            return MenuActionResult.Success(
                MenuUpdate.Navigate(
                    MenuRoute(
                        OWNER,
                        ROUTE_PRODUCTION_CONFIRM,
                        statePayload(current) + mapOf(
                            WORLD_UUID to worldUuid.toString(),
                            ENABLING to false.toString(),
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
            player.sendMessage(i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_NOT_PRODUCTION))
            return MenuActionResult.Rejected()
        }
        storage.setSubmissionState(
            worldData.uuid,
            SubmissionState.COMPLETED,
            player.uniqueId,
            player.name,
            "mark_completed"
        )
        // 完成状態が永続化された後をコミット点とし、外部コマンドの失敗で状態を戻さないようにします。
        submissionCompletionMacroService.execute(
            worldData,
            current,
            player.uniqueId,
            player.name,
        )
        protectionService.sync(worldData)
        toolPermissionController.syncWorld(worldData)
        player.sendMessage(i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_COMPLETED_MESSAGE))
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
        player.sendMessage(i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_UNMARKED_MESSAGE))
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

    private fun statsEntry(player: Player, slot: Int): MenuElement {
        val worlds = productionWorlds()
        val states = worlds.mapNotNull { runCatching { storage.getSubmissionState(it.uuid) }.getOrNull() }
        val submitted = states.count { it == SubmissionState.SUBMITTED || it == SubmissionState.COMPLETED }
        val completed = states.count { it == SubmissionState.COMPLETED }
        return displayEntry(
            slot,
            Material.WRITABLE_BOOK,
            GuiNameSpec.FixedLabel(i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_STATS_DISPLAY)),
            me.awabi2048.mwmchanpon.ui.framedLore(
                i18nList(
                    player,
                    ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_STATS_LORE,
                    mapOf(
                        "submitted" to submitted,
                        "total" to worlds.size,
                        "completed" to completed,
                    ),
                ).map(GuiLoreLine::Text),
                GuiLoreFrame.BOTH,
            ),
        )
    }

    private fun renderCloseSubmissionConfirmation(player: Player): InventoryMenuView =
        confirmationView(
            player,
            ConfirmationPresentation(
                ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_CLOSE_SUBMISSIONS_TITLE,
                ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_CLOSE_SUBMISSIONS_DISPLAY,
                ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_CLOSE_SUBMISSIONS_LORE,
            ),
            Material.REDSTONE_BLOCK,
            ACTION_CONFIRM_CLOSE,
            ChanponReversibleContracts.submission("global", "close"),
            ChanponReversibleActionClassification.Operation.SUBMISSION_CLOSURE,
        )

    private fun renderProductionToggleConfirmation(
        player: Player,
        route: MenuRoute,
    ): InventoryMenuView {
        val enabling = route.payload[ENABLING]?.toBooleanStrictOrNull()
            ?: error("本番用ワールド切替方向がありません")
        val key = if (enabling) {
            ConfirmationPresentation(ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_SET_PRODUCTION_TITLE, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_SET_PRODUCTION_DISPLAY, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_SET_PRODUCTION_LORE)
        } else {
            ConfirmationPresentation(ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_UNSET_PRODUCTION_TITLE, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_UNSET_PRODUCTION_DISPLAY, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_UNSET_PRODUCTION_LORE)
        }
        return confirmationView(
            player,
            key,
            Material.HEART_OF_THE_SEA,
            ACTION_CONFIRM_PRODUCTION,
        )
    }

    private fun renderCompleteConfirmation(player: Player, route: MenuRoute): InventoryMenuView =
        confirmationView(
            player,
            ConfirmationPresentation(
                ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_COMPLETE_UNSUBMITTED_TITLE,
                ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_COMPLETE_UNSUBMITTED_DISPLAY,
                ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_COMPLETE_UNSUBMITTED_LORE,
            ),
            Material.GOLDEN_APPLE,
            ACTION_CONFIRM_COMPLETE,
            route.payload[WORLD_UUID]?.let { ChanponReversibleContracts.submission(it, "complete") },
            ChanponReversibleActionClassification.Operation.SUBMISSION_COMPLETION,
        )

    private fun confirmationView(
        player: Player,
        presentation: ConfirmationPresentation,
        previewMaterial: Material,
        confirmAction: String,
        reversibleContract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract? = null,
        classification: ChanponReversibleActionClassification.Operation? = null,
    ): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, presentation.title),
            listOf(
                displayEntry(
                    layout.previewSlot,
                    previewMaterial,
                    GuiNameSpec.FixedLabel(i18nComponent(player, presentation.display)),
                    me.awabi2048.mwmchanpon.ui.framedLore(
                        i18nList(player, presentation.lore).map(GuiLoreLine::Text),
                        GuiLoreFrame.BOTH,
                    ),
                ),
                confirmationEntry(player, layout.confirmSlot, true, confirmAction, reversibleContract, classification),
                confirmationEntry(player, layout.cancelSlot, false, ACTION_CANCEL),
            ),
        ).asConfirmation()
    }

    private fun confirmationActions(
        confirmAction: String,
        confirmHandler: (MenuActionContext) -> MenuActionResult,
    ): Map<String, MenuActionHandler> = mapOf(
        ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
        confirmAction to MenuActionHandler(confirmHandler),
    )

    private fun confirmCloseSubmissions(context: MenuActionContext): MenuActionResult {
        if (!plugin.isConfigurationMigrationCurrent()) return MenuActionResult.Rejected()
        plugin.config.set("submission.closed", true)
        plugin.saveConfigIfCurrent()
        context.player.sendMessage(i18nComponent(context.player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_CLOSED_MESSAGE))
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
            player.sendMessage(i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PRODUCTION_LIMIT))
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
                    ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PRODUCTION_ENABLED
                } else {
                    ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PRODUCTION_DISABLED
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

    private fun worldEntry(
        player: Player,
        slot: Int,
        worldData: WorldData,
        payload: Map<String, String>,
    ): MenuElement {
        val state = stateFor(worldData)
        val unknown = i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_UNKNOWN)
        val ownerName = playerName(worldData.owner, unknown)
        val members = (worldData.moderators + worldData.members)
            .distinct()
            .map { playerName(it, unknown) }
            .let { if (it.isEmpty()) i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_NO_MEMBERS) else it.joinToString(", ") }
        val production = storage.isProductionWorld(worldData.uuid)
        val actions = buildList {
            add(chanponGestureAction(ACTION_WORLD, MenuGesture.LEFT, i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_ACTION_WARP), payload, safety = MenuActionSafety.EXTERNAL_SIDE_EFFECT))
            if (player.gameMode == GameMode.CREATIVE) {
                add(chanponGestureAction(
                    ACTION_WORLD,
                    MenuGesture.RIGHT,
                    i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_ACTION_COMPLETE),
                    payload,
                    safety = if (state == SubmissionState.UNSUBMITTED) MenuActionSafety.CONFIRM_ENTRY else MenuActionSafety.REVERSIBLE,
                    reversibleContract = when (state) {
                        SubmissionState.SUBMITTED -> ChanponReversibleContracts.submission(worldData.uuid.toString(), "complete")
                        SubmissionState.COMPLETED -> ChanponReversibleContracts.submission(worldData.uuid.toString(), "uncomplete")
                        SubmissionState.UNSUBMITTED -> null
                    },
                    classification = if (state == SubmissionState.UNSUBMITTED) null else {
                        ChanponReversibleActionClassification.Operation.SUBMISSION_COMPLETION
                    },
                ))
            }
            if (production) {
                add(chanponGestureAction(ACTION_WORLD, MenuGesture.MIDDLE, i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_ACTION_REMOVE_PRODUCTION), payload, safety = MenuActionSafety.CONFIRM_ENTRY))
            }
        }
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = worldData.icon,
                name = GuiNameSpec.TargetIdentity(i18nComponent(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_DISPLAY, mapOf("world" to worldData.name))),
                role = GuiElementRole.ACTION,
                data = listOf(
                    GuiMenuEntryData(i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_OWNER_LABEL), ownerName),
                    GuiMenuEntryData(i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_MEMBERS_LABEL), members),
                    GuiMenuEntryData(i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_CREATED_LABEL), formatCreatedAt(worldData.createdAt)),
                    GuiMenuEntryData(
                        i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_PRODUCTION_LABEL),
                        i18n(player, if (production) ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_PRODUCTION_YES else ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_PRODUCTION_NO),
                        if (production) GuiValueTone.SUCCESS else GuiValueTone.MUTED,
                    ),
                    GuiMenuEntryData(
                        i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_STATE_LABEL),
                        i18n(player, submissionStateKey(state)),
                        when (state) {
                            SubmissionState.UNSUBMITTED -> GuiValueTone.WARNING
                            SubmissionState.SUBMITTED -> GuiValueTone.SUCCESS
                            SubmissionState.COMPLETED -> GuiValueTone.INFO
                        },
                    ),
                    GuiMenuEntryData(
                        i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_COMPLETED_LABEL),
                        i18n(player, if (state == SubmissionState.COMPLETED) ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_COMPLETED_YES else ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_WORLD_COMPLETED_NO),
                    ),
                ),
                actions = actions,
            ),
        )
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

    private fun filterEntry(
        player: Player,
        slot: Int,
        material: Material,
        displayKey: LocalizationKey<String>,
        optionKeys: List<LocalizationKey<String>>,
        selectedKey: LocalizationKey<String>,
        actionId: String,
    ): MenuElement {
        return CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.FixedLabel(i18nComponent(player, displayKey)),
            role = GuiElementRole.ACTION,
            options = optionKeys.map { GuiMenuEntryOption(i18n(player, it), it == selectedKey) },
            actions = listOf(chanponCycleAction(
                actionId,
                i18n(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_COMMON_ACTION_NEXT),
                // フィルタ／並び順は次Routeの表示状態のみを変更します。
                safety = MenuActionSafety.NAVIGATION_ONLY,
                classification = submissionRouteClassification(actionId),
            )),
            interactionGuidance = GuiInteractionGuidance.LIST_SETTING,
        ),
    )
    }

    private fun closeSubmissionItem(player: Player): MenuElement {
        val presentation = if (plugin.isSubmissionClosed()) {
            MenuPresentation(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_CLOSE_CLOSED_DISPLAY, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_CLOSE_CLOSED_LORE, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_CLOSE_CLOSED_ACTION)
        } else {
            MenuPresentation(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_CLOSE_OPEN_DISPLAY, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_CLOSE_OPEN_LORE, ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_CLOSE_OPEN_ACTION)
        }
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = 52,
                material = if (plugin.isSubmissionClosed()) Material.BARRIER else Material.REDSTONE_BLOCK,
                name = GuiNameSpec.FixedLabel(i18nComponent(player, presentation.display)),
                role = GuiElementRole.ACTION,
                description = i18nList(player, presentation.lore),
                actions = listOf(chanponGestureAction(
                    ACTION_CLOSE_SUBMISSIONS,
                    MenuGesture.ANY,
                    i18n(player, presentation.action),
                    safety = if (plugin.isSubmissionClosed()) MenuActionSafety.REVERSIBLE else MenuActionSafety.CONFIRM_ENTRY,
                    reversibleContract = if (plugin.isSubmissionClosed()) {
                        ChanponReversibleContracts.submission("global", "reopen")
                    } else {
                        null
                    },
                    classification = if (plugin.isSubmissionClosed()) {
                        ChanponReversibleActionClassification.Operation.SUBMISSION_CLOSURE
                    } else {
                        null
                    },
                )),
            ),
        )
    }

    private fun confirmationEntry(
        player: Player,
        slot: Int,
        confirm: Boolean,
        actionId: String,
        reversibleContract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract? = null,
        classification: ChanponReversibleActionClassification.Operation? = null,
    ): MenuElement {
        val presentation = if (confirm) {
            MenuPresentation(ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_BUTTON_CONFIRM_DISPLAY, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_BUTTON_CONFIRM_LORE, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_BUTTON_CONFIRM_ACTION)
        } else {
            MenuPresentation(ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_BUTTON_CANCEL_DISPLAY, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_BUTTON_CANCEL_LORE, ChanponSubmissionKeys.CHANPON_SUBMISSION_CONFIRM_BUTTON_CANCEL_ACTION)
        }
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE,
                name = GuiNameSpec.FixedLabel(i18nComponent(player, presentation.display)),
                role = if (confirm) GuiElementRole.CONFIRM else GuiElementRole.CANCEL,
                description = i18nList(player, presentation.lore),
                actions = listOf(
                    chanponGestureAction(
                        actionId,
                        MenuGesture.ANY,
                        i18n(player, presentation.action),
                        safety = submissionActionSafety(actionId),
                        reversibleContract = reversibleContract,
                        classification = classification,
                    ),
                ),
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
            name = opaqueName(text, GuiNameStyle.DEFAULT),
            role = role,
            actions = listOf(
                chanponGestureAction(actionId, MenuGesture.ANY, actionText, payload, safety = submissionActionSafety(actionId)),
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

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getLocalized(player, key, placeholders)
    }

    private fun submissionActionSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_BACK, ACTION_PAGE, ACTION_CANCEL, ACTION_PROGRESS_FILTER, ACTION_SORT -> MenuActionSafety.NAVIGATION_ONLY
        ACTION_CLOSE_SUBMISSIONS -> MenuActionSafety.CONFIRM_ENTRY
        ACTION_CONFIRM_CLOSE, ACTION_CONFIRM_COMPLETE -> MenuActionSafety.REVERSIBLE
        ACTION_CONFIRM_PRODUCTION -> MenuActionSafety.IRREVERSIBLE
        else -> error("Submission action must declare a dedicated safety mapping: $actionId")
    }

    private fun submissionRouteClassification(actionId: String): ChanponReversibleActionClassification.Operation? = when (actionId) {
        ACTION_PROGRESS_FILTER -> ChanponReversibleActionClassification.Operation.SUBMISSION_LIST_FILTER
        ACTION_SORT -> ChanponReversibleActionClassification.Operation.SUBMISSION_LIST_SORT
        else -> null
    }

    private fun i18nList(player: Player?, key: LocalizationKey<List<String>>, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getLocalized(player, key, placeholders)
    }

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, key, placeholders)
    }

    private fun submissionStateKey(state: SubmissionState): LocalizationKey<String> = when (state) {
        SubmissionState.UNSUBMITTED -> ChanponSubmissionKeys.CHANPON_SUBMISSION_STATE_UNSUBMITTED
        SubmissionState.SUBMITTED -> ChanponSubmissionKeys.CHANPON_SUBMISSION_STATE_SUBMITTED
        SubmissionState.COMPLETED -> ChanponSubmissionKeys.CHANPON_SUBMISSION_STATE_COMPLETED
    }

    private data class ConfirmationPresentation(
        val title: LocalizationKey<String>,
        val display: LocalizationKey<String>,
        val lore: LocalizationKey<List<String>>,
    )

    private data class MenuPresentation(
        val display: LocalizationKey<String>,
        val lore: LocalizationKey<List<String>>,
        val action: LocalizationKey<String>,
    )

    enum class ProgressFilter(val key: LocalizationKey<String>) {
        ALL(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PROGRESS_FILTER_ALL),
        UNSUBMITTED(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PROGRESS_FILTER_UNSUBMITTED),
        SUBMITTED(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PROGRESS_FILTER_SUBMITTED),
        COMPLETED(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_PROGRESS_FILTER_COMPLETED)
    }

    enum class SortType(val key: LocalizationKey<String>) {
        CREATED_DESC(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_SORT_CREATED_DESC),
        CREATED_ASC(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_SORT_CREATED_ASC),
        NAME_ASC(ChanponSubmissionKeys.CHANPON_SUBMISSION_ADMIN_MENU_SORT_NAME_ASC)
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
            MyWorldManagerApi.closeWorldSettingsContext(player)
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
