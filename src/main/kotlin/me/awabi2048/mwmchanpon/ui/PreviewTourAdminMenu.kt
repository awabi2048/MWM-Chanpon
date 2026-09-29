package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuDialogResponse
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys
import com.awabi2048.ccsystem.api.localization.generated.ChanponPreviewTourKeys
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.tour.PreviewTourData
import me.awabi2048.mwmchanpon.tour.PreviewTourListener
import me.awabi2048.mwmchanpon.tour.PreviewTourService
import me.awabi2048.mwmchanpon.tour.PreviewTourStop
import me.awabi2048.mwmchanpon.tour.PreviewTourStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * プレビューツアーの管理画面です。
 *
 * 一覧 → 編集 → 巡業ワールド一覧 → 追加ワールド選択の4画面で構成し、
 * タイトル・説明や所要時間の入力はCC-Systemの共通Dialogへ委譲します。
 */
class PreviewTourAdminMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: PreviewTourStorage,
    private val service: PreviewTourService,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    private val dialogs = CCSystem.getAPI().getMenuDialogService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_LIST,
                renderer = { context -> renderList(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_PAGE to MenuActionHandler(::changeListPage),
                    ACTION_OPEN to MenuActionHandler(::openTour),
                    ACTION_CREATE to MenuActionHandler(::openCreateDialog),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_EDIT,
                renderer = { context -> renderEdit(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_RENAME to MenuActionHandler(::openRenameDialog),
                    ACTION_STOPS to MenuActionHandler(::openStops),
                    ACTION_SIGN to MenuActionHandler(::giveSign),
                    ACTION_DELETE to MenuActionHandler(::openDeleteConfirm),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_STOPS,
                renderer = { context -> renderStops(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_PAGE to MenuActionHandler(::changeStopsPage),
                    ACTION_STOP_UP to MenuActionHandler(::moveStopUp),
                    ACTION_STOP_DOWN to MenuActionHandler(::moveStopDown),
                    ACTION_STOP_DURATION to MenuActionHandler(::openStopDurationDialog),
                    ACTION_STOP_REMOVE to MenuActionHandler(::removeStop),
                    ACTION_ADD_STOP to MenuActionHandler(::openWorldPicker),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_PICK,
                renderer = { context -> renderWorldPicker(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_PAGE to MenuActionHandler(::changePickPage),
                    ACTION_PICK to MenuActionHandler(::openPickDurationDialog),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_DELETE_CONFIRM,
                renderer = { context -> renderDeleteConfirm(context.player, context.route) },
                actions = mapOf(
                    ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM_DELETE to MenuActionHandler(::confirmDelete),
                ),
            ),
        )
    }

    fun open(player: Player, page: Int = 0) {
        if (!player.hasPermission(ADMIN_PERMISSION)) return
        runtime.navigate(player, listRoute(page))
    }

    internal fun route(page: Int = 0): MenuRoute = listRoute(page)

    // ------------------------------------------------------------------
    // 一覧
    // ------------------------------------------------------------------

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val tours = storage.allTours().sortedByDescending { it.createdAt }
        val pages = pageCount(tours.size)
        val safePage = page(route).coerceIn(0, pages - 1)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) {
            elements += simpleEntry(
                player, layout.previousPageSlot, Material.ARROW,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV_ACTION),
                mapOf(PAGE to (safePage - 1).toString()),
            )
        }
        if (safePage < pages - 1) {
            elements += simpleEntry(
                player, layout.nextPageSlot, Material.ARROW,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT_ACTION),
                mapOf(PAGE to (safePage + 1).toString()),
            )
        }
        elements += CCSystem.getAPI().getGuiElementService().backEntry(player, layout.backSlot)
        elements += infoEntry(
            player,
            layout.infoSlot,
            i18nList(
                player,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_INFO_LORE,
                mapOf("count" to tours.size, "page" to safePage + 1, "pages" to pages),
            ),
        )
        tours.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, tour ->
            elements += tourEntry(player, layout.itemSlots[index], tour)
        }
        // 新規作成は一覧末尾の操作として独立した項目に置き、既存ツアー項目と混在させません。
        elements += controlEntry(
            player, 53, Material.NETHER_STAR,
            ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_CREATE_NAME,
            ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_CREATE_LORE,
            ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_CREATE_ACTION,
            ACTION_CREATE,
        )
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_TITLE),
            elements,
        )
    }

    private fun tourEntry(player: Player, slot: Int, tour: PreviewTourData): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = Material.FILLED_MAP,
                name = GuiNameSpec.TargetIdentity(Component.text("§b${tour.title}")),
                role = GuiElementRole.ACTION,
                data = listOf(
                    GuiMenuEntryData(
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_STOPS),
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_FIELD_STOP_COUNT_VALUE, mapOf("count" to tour.stops.size)),
                    ),
                    GuiMenuEntryData(
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_DURATION),
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_FIELD_TOTAL_DURATION_VALUE, mapOf("duration" to PreviewTourService.formatDuration(tour.totalDurationSeconds))),
                    ),
                    GuiMenuEntryData(
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_CREATOR),
                        creatorName(tour),
                    ),
                ),
                actions = listOf(
                    chanponGestureAction(
                        ACTION_OPEN,
                        MenuGesture.ANY,
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_ENTRY_ACTION),
                        mapOf(TOUR_UUID to tour.uuid.toString()),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                ),
            ),
        )

    // ------------------------------------------------------------------
    // 編集
    // ------------------------------------------------------------------

    private fun renderEdit(player: Player, route: MenuRoute): InventoryMenuView {
        val tour = tour(route)
        val elements = mutableListOf<MenuElement>()
        if (tour != null) {
            elements += CCSystem.getAPI().getGuiElementService().menuDisplay(
                GuiMenuDisplaySpec(
                    4,
                    GuiItemSpec(
                        Material.FILLED_MAP,
                        GuiNameSpec.FixedLabel(Component.text("§b${tour.title}")),
                        GuiLoreSpec.Blocks(
                            listOf(
                                GuiLoreBlock(
                                    tour.description.takeIf { it.isNotBlank() }?.lines()?.map { GuiLoreLine.Text("§7$it") }.orEmpty(),
                                ),
                                GuiLoreBlock(
                                    listOf(
                                        GuiLoreLine.Text(
                                            "§7" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_STOPS) +
                                                ": §f" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_FIELD_STOP_COUNT_VALUE, mapOf("count" to tour.stops.size)),
                                        ),
                                        GuiLoreLine.Text(
                                            "§7" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_DURATION) +
                                                ": §f" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_FIELD_TOTAL_DURATION_VALUE, mapOf("duration" to PreviewTourService.formatDuration(tour.totalDurationSeconds))),
                                        ),
                                        GuiLoreLine.Text(
                                            "§7" + i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_CREATOR) +
                                                ": §f" + creatorName(tour),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        GuiElementRole.CONTENT,
                        1,
                    ),
                ),
            )
            val payload = mapOf(TOUR_UUID to tour.uuid.toString())
            elements += controlEntry(
                player, 20, Material.NAME_TAG,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_RENAME_NAME,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_RENAME_LORE,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_RENAME_ACTION,
                ACTION_RENAME, payload,
            )
            elements += controlEntry(
                player, 22, Material.MAP,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOPS_NAME,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOPS_LORE,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOPS_ACTION,
                ACTION_STOPS, payload,
            )
            elements += controlEntry(
                player, 24, Material.OAK_SIGN,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_SIGN_NAME,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_SIGN_LORE,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_SIGN_ACTION,
                ACTION_SIGN, payload,
            )
            elements += CCSystem.getAPI().getGuiElementService().menuEntry(
                player,
                GuiMenuEntrySpec(
                    slot = 32,
                    material = Material.LAVA_BUCKET,
                    name = GuiNameSpec.FixedLabel(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_NAME)),
                    role = GuiElementRole.ACTION,
                    description = i18nList(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_LORE),
                    warnings = listOf(i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_BODY, mapOf("tour" to tour.title))),
                    actions = listOf(
                        chanponGestureAction(
                            ACTION_DELETE,
                            MenuGesture.ANY,
                            i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_ACTION),
                            payload,
                            safety = MenuActionSafety.CONFIRM_ENTRY,
                        ),
                    ),
                ),
            )
        }
        elements += CCSystem.getAPI().getGuiElementService().backEntry(player, 40)
        return InventoryMenuView(
            45,
            i18nComponent(
                player,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_EDIT_TITLE,
                mapOf("tour" to (tour?.title ?: "-")),
            ),
            elements,
        )
    }

    // ------------------------------------------------------------------
    // 巡業ワールド
    // ------------------------------------------------------------------

    private fun renderStops(player: Player, route: MenuRoute): InventoryMenuView {
        val tour = tour(route)
        val stops = tour?.stops.orEmpty()
        val pages = pageCount(stops.size)
        val safePage = page(route).coerceIn(0, pages - 1)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        val basePayload = mapOf(
            TOUR_UUID to (tour?.uuid?.toString() ?: ""),
            PAGE to safePage.toString(),
        )
        if (safePage > 0) {
            elements += simpleEntry(
                player, layout.previousPageSlot, Material.ARROW,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV_ACTION),
                basePayload + (PAGE to (safePage - 1).toString()),
            )
        }
        if (safePage < pages - 1) {
            elements += simpleEntry(
                player, layout.nextPageSlot, Material.ARROW,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT_ACTION),
                basePayload + (PAGE to (safePage + 1).toString()),
            )
        }
        elements += CCSystem.getAPI().getGuiElementService().backEntry(player, layout.backSlot)
        elements += infoEntry(
            player,
            layout.infoSlot,
            i18nList(
                player,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_INFO_LORE,
                mapOf(
                    "count" to stops.size,
                    "page" to safePage + 1,
                    "pages" to pages,
                ),
            ),
        )
        stops.drop(safePage * STOPS_PER_PAGE).take(STOPS_PER_PAGE).forEachIndexed { index, stop ->
            val absoluteIndex = safePage * STOPS_PER_PAGE + index
            elements += stopEntry(player, layout.itemSlots[index], absoluteIndex, stop, stops.size, basePayload)
        }
        if (tour != null) {
            elements += controlEntry(
                player, 53, Material.EMERALD,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_ADD_NAME,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_ADD_LORE,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_ADD_ACTION,
                ACTION_ADD_STOP, basePayload,
            )
        }
        return InventoryMenuView(
            layout.size,
            i18nComponent(
                player,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOPS_TITLE,
                mapOf("tour" to (tour?.title ?: "-")),
            ),
            elements,
        )
    }

    private fun stopEntry(
        player: Player,
        slot: Int,
        index: Int,
        stop: PreviewTourStop,
        stopCount: Int,
        basePayload: Map<String, String>,
    ): MenuElement {
        val world = MyWorldManagerApi.getWorldRepository()?.findByUuid(stop.worldUuid)
        val worldName = world?.name ?: "(${stop.worldUuid})"
        val payload = basePayload + (STOP_INDEX to index.toString())
        val actions = mutableListOf(
            chanponGestureAction(
                ACTION_STOP_UP,
                MenuGesture.PLAIN_LEFT,
                i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_UP_ACTION),
                payload,
                enabled = index > 0,
                safety = MenuActionSafety.EXTERNAL_SIDE_EFFECT,
            ),
            chanponGestureAction(
                ACTION_STOP_DOWN,
                MenuGesture.SHIFT_LEFT,
                i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_DOWN_ACTION),
                payload,
                enabled = index < stopCount - 1,
                safety = MenuActionSafety.EXTERNAL_SIDE_EFFECT,
            ),
            chanponGestureAction(
                ACTION_STOP_DURATION,
                MenuGesture.SHIFT_RIGHT,
                i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_DURATION_ACTION),
                payload,
                safety = MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE,
            ),
            chanponGestureAction(
                ACTION_STOP_REMOVE,
                MenuGesture.PLAIN_RIGHT,
                i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_REMOVE_ACTION),
                payload,
                safety = MenuActionSafety.EXTERNAL_SIDE_EFFECT,
            ),
        )
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = world?.icon ?: Material.BARRIER,
                name = GuiNameSpec.TargetIdentity(
                    i18nComponent(
                        player,
                        ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_NAME,
                        mapOf("index" to index + 1, "world" to worldName),
                    ),
                ),
                role = GuiElementRole.ACTION,
                description = listOf(
                    i18n(
                        player,
                        ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_DURATION,
                        mapOf("duration" to PreviewTourService.formatDuration(stop.durationSeconds)),
                    ),
                ),
                actions = actions,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 追加ワールド選択
    // ------------------------------------------------------------------

    private fun renderWorldPicker(player: Player, route: MenuRoute): InventoryMenuView {
        val tourUuid = route.payload[TOUR_UUID].orEmpty()
        val worlds = completedWorlds()
        val pages = pageCount(worlds.size)
        val safePage = page(route).coerceIn(0, pages - 1)
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        val basePayload = mapOf(TOUR_UUID to tourUuid, PAGE to safePage.toString())
        if (safePage > 0) {
            elements += simpleEntry(
                player, layout.previousPageSlot, Material.ARROW,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV_ACTION),
                basePayload + (PAGE to (safePage - 1).toString()),
            )
        }
        if (safePage < pages - 1) {
            elements += simpleEntry(
                player, layout.nextPageSlot, Material.ARROW,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT), GuiElementRole.NAVIGATION,
                ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT_ACTION),
                basePayload + (PAGE to (safePage + 1).toString()),
            )
        }
        elements += CCSystem.getAPI().getGuiElementService().backEntry(player, layout.backSlot)
        elements += infoEntry(
            player,
            layout.infoSlot,
            i18nList(
                player,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_INFO_LORE,
                mapOf("count" to worlds.size, "page" to safePage + 1, "pages" to pages),
            ),
        )
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            elements += worldPickEntry(player, layout.itemSlots[index], world, basePayload)
        }
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_PICK_TITLE),
            elements,
        )
    }

    private fun worldPickEntry(player: Player, slot: Int, world: WorldData, basePayload: Map<String, String>): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = world.icon,
                name = GuiNameSpec.TargetIdentity(Component.text("§f${world.name}")),
                role = GuiElementRole.ACTION,
                data = listOf(
                    GuiMenuEntryData(
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_FIELD_CREATOR),
                        Bukkit.getOfflinePlayer(world.owner).name ?: "-",
                    ),
                ),
                actions = listOf(
                    chanponGestureAction(
                        ACTION_PICK,
                        MenuGesture.ANY,
                        i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_STOP_PICK_ACTION),
                        basePayload + (WORLD_UUID to world.uuid.toString()),
                        safety = MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE,
                    ),
                ),
            ),
        )

    // ------------------------------------------------------------------
    // 削除確認
    // ------------------------------------------------------------------

    private fun renderDeleteConfirm(player: Player, route: MenuRoute): InventoryMenuView {
        val tour = tour(route)
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_TITLE),
            listOf(
                CCSystem.getAPI().getGuiElementService().menuDisplay(
                    GuiMenuDisplaySpec(
                        layout.previewSlot,
                        GuiItemSpec(
                            Material.FILLED_MAP,
                            GuiNameSpec.FixedLabel(Component.text("§b${tour?.title ?: "-"}")),
                            framedLore(
                                listOf(
                                    GuiLoreLine.Text(
                                        i18n(
                                            player,
                                            ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_BODY,
                                            mapOf("tour" to (tour?.title ?: "-")),
                                        ),
                                    ),
                                ),
                                GuiLoreFrame.BOTH,
                            ),
                            GuiElementRole.CONTENT,
                            1,
                        ),
                    ),
                ),
                simpleEntry(
                    player, layout.confirmSlot, Material.LIME_CONCRETE,
                    i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_CONFIRM), GuiElementRole.CONFIRM,
                    ACTION_CONFIRM_DELETE, i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_CONFIRM_ACTION),
                    route.payload,
                ),
                simpleEntry(
                    player, layout.cancelSlot, Material.RED_CONCRETE,
                    i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_CANCEL), GuiElementRole.CANCEL,
                    ACTION_CANCEL, i18n(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_DELETE_CANCEL_ACTION),
                ),
            ),
        ).asConfirmation()
    }

    // ------------------------------------------------------------------
    // アクション: 一覧
    // ------------------------------------------------------------------

    private fun changeListPage(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(MenuUpdate.Replace(listRoute(context.payload[PAGE]?.toIntOrNull() ?: 0)))

    private fun openTour(context: MenuActionContext): MenuActionResult {
        val tourUuid = context.payload[TOUR_UUID] ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Navigate(editRoute(tourUuid)))
    }

    private fun openCreateDialog(context: MenuActionContext): MenuActionResult {
        val player = context.player
        dialogs.show(
            player,
            textDialog(
                player,
                id = "preview-tour-create",
                titleKey = ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_CREATE_TITLE,
                confirmKey = ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_CONFIRM,
                inputs = listOf(
                    MenuDialogInput.Text("title", i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_FIELD_TITLE), maxLength = 32),
                    MenuDialogInput.Text("description", i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_FIELD_DESCRIPTION), maxLength = 256),
                ),
                onConfirm = { response ->
                    val title = response.textValue("title").trim()
                    if (title.isEmpty()) {
                        MenuActionResult.Rejected(
                            i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_TITLE_REQUIRED),
                        )
                    } else {
                        val tour = PreviewTourData(
                            uuid = UUID.randomUUID(),
                            title = title,
                            description = response.textValue("description").trim(),
                            createdBy = player.uniqueId,
                        )
                        storage.saveTour(tour)
                        MenuActionResult.Success(MenuUpdate.Navigate(editRoute(tour.uuid.toString())))
                    }
                },
            ),
        )
        return MenuActionResult.Success(MenuUpdate.None)
    }

    // ------------------------------------------------------------------
    // アクション: 編集
    // ------------------------------------------------------------------

    private fun openRenameDialog(context: MenuActionContext): MenuActionResult {
        val tour = tour(context.route) ?: return MenuActionResult.Rejected()
        val player = context.player
        dialogs.show(
            player,
            textDialog(
                player,
                id = "preview-tour-rename",
                titleKey = ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_EDIT_TITLE,
                confirmKey = ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_CONFIRM,
                inputs = listOf(
                    MenuDialogInput.Text("title", i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_FIELD_TITLE), initial = tour.title, maxLength = 32),
                    MenuDialogInput.Text("description", i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_FIELD_DESCRIPTION), initial = tour.description, maxLength = 256),
                ),
                onConfirm = { response ->
                    val title = response.textValue("title").trim()
                    if (title.isEmpty()) {
                        MenuActionResult.Rejected(
                            i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_TITLE_REQUIRED),
                        )
                    } else {
                        storage.saveTour(
                            tour.copy(title = title, description = response.textValue("description").trim()),
                        )
                        MenuActionResult.Success(MenuUpdate.Resume)
                    }
                },
            ),
        )
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun openStops(context: MenuActionContext): MenuActionResult {
        val tourUuid = context.route.payload[TOUR_UUID] ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(
            MenuUpdate.Navigate(MenuRoute(OWNER, ROUTE_STOPS, mapOf(TOUR_UUID to tourUuid, PAGE to "0"))),
        )
    }

    private fun giveSign(context: MenuActionContext): MenuActionResult {
        val tour = tour(context.route) ?: return MenuActionResult.Rejected()
        val player = context.player
        val item = createSignItem(player, tour)
        val overflow = player.inventory.addItem(item)
        if (overflow.isNotEmpty()) {
            overflow.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_SIGN_GIVEN, mapOf("tour" to tour.title)))
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun openDeleteConfirm(context: MenuActionContext): MenuActionResult {
        val tourUuid = context.route.payload[TOUR_UUID] ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(
            MenuUpdate.Navigate(MenuRoute(OWNER, ROUTE_DELETE_CONFIRM, mapOf(TOUR_UUID to tourUuid))),
        )
    }

    private fun confirmDelete(context: MenuActionContext): MenuActionResult {
        val tour = tour(context.route) ?: return MenuActionResult.Rejected()
        val player = context.player
        service.abortRunsForTour(tour.uuid)
        storage.deleteTour(tour.uuid)
        player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_TOUR_DELETED))
        return MenuActionResult.Success(MenuUpdate.Navigate(listRoute(0)))
    }

    // ------------------------------------------------------------------
    // アクション: 巡業ワールド
    // ------------------------------------------------------------------

    private fun changeStopsPage(context: MenuActionContext): MenuActionResult {
        val tourUuid = context.payload[TOUR_UUID] ?: return MenuActionResult.Rejected()
        val page = context.payload[PAGE]?.toIntOrNull() ?: 0
        return MenuActionResult.Success(
            MenuUpdate.Replace(MenuRoute(OWNER, ROUTE_STOPS, mapOf(TOUR_UUID to tourUuid, PAGE to page.toString()))),
        )
    }

    private fun moveStopUp(context: MenuActionContext): MenuActionResult = moveStop(context, -1)
    private fun moveStopDown(context: MenuActionContext): MenuActionResult = moveStop(context, +1)

    private fun moveStop(context: MenuActionContext, delta: Int): MenuActionResult {
        val tour = tour(context.route) ?: return MenuActionResult.Rejected()
        val index = context.payload[STOP_INDEX]?.toIntOrNull() ?: return MenuActionResult.Rejected()
        if (!storage.moveStop(tour.uuid, index, index + delta)) {
            return MenuActionResult.Rejected()
        }
        return MenuActionResult.Success(MenuUpdate.Replace(stopsRoute(tour.uuid.toString(), context.payload[PAGE]?.toIntOrNull() ?: 0)))
    }

    private fun removeStop(context: MenuActionContext): MenuActionResult {
        val tour = tour(context.route) ?: return MenuActionResult.Rejected()
        val index = context.payload[STOP_INDEX]?.toIntOrNull() ?: return MenuActionResult.Rejected()
        if (!storage.removeStop(tour.uuid, index)) {
            return MenuActionResult.Rejected()
        }
        return MenuActionResult.Success(MenuUpdate.Replace(stopsRoute(tour.uuid.toString(), context.payload[PAGE]?.toIntOrNull() ?: 0)))
    }

    private fun openStopDurationDialog(context: MenuActionContext): MenuActionResult {
        val tour = tour(context.route) ?: return MenuActionResult.Rejected()
        val index = context.payload[STOP_INDEX]?.toIntOrNull() ?: return MenuActionResult.Rejected()
        val stop = tour.stops.getOrNull(index) ?: return MenuActionResult.Rejected()
        val player = context.player
        val worldName = MyWorldManagerApi.getWorldRepository()?.findByUuid(stop.worldUuid)?.name
            ?: stop.worldUuid.toString()
        dialogs.show(player, durationDialog(player, worldName, stop.durationSeconds) { seconds ->
            if (storage.setStopDuration(tour.uuid, index, seconds)) {
                MenuActionResult.Success(MenuUpdate.Resume)
            } else {
                MenuActionResult.Rejected(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_DURATION_INVALID))
            }
        })
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun openWorldPicker(context: MenuActionContext): MenuActionResult {
        val tourUuid = context.route.payload[TOUR_UUID] ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(
            MenuUpdate.Navigate(MenuRoute(OWNER, ROUTE_PICK, mapOf(TOUR_UUID to tourUuid, PAGE to "0"))),
        )
    }

    // ------------------------------------------------------------------
    // アクション: ワールド選択
    // ------------------------------------------------------------------

    private fun changePickPage(context: MenuActionContext): MenuActionResult {
        val tourUuid = context.payload[TOUR_UUID] ?: return MenuActionResult.Rejected()
        val page = context.payload[PAGE]?.toIntOrNull() ?: 0
        return MenuActionResult.Success(
            MenuUpdate.Replace(MenuRoute(OWNER, ROUTE_PICK, mapOf(TOUR_UUID to tourUuid, PAGE to page.toString()))),
        )
    }

    private fun openPickDurationDialog(context: MenuActionContext): MenuActionResult {
        val tour = tour(context.route) ?: return MenuActionResult.Rejected()
        val worldUuid = context.payload[WORLD_UUID]?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        } ?: return MenuActionResult.Rejected()
        val player = context.player
        val worldName = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.name
            ?: worldUuid.toString()
        dialogs.show(player, durationDialog(player, worldName, DEFAULT_STOP_SECONDS) { seconds ->
            if (!eligibleWorld(worldUuid)) {
                return@durationDialog MenuActionResult.Rejected(
                    i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_WORLD_NOT_ELIGIBLE),
                )
            }
            if (storage.addStop(tour.uuid, worldUuid, seconds)) {
                MenuActionResult.Success(MenuUpdate.Navigate(stopsRoute(tour.uuid.toString(), 0)))
            } else {
                MenuActionResult.Rejected(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_WORLD_NOT_ELIGIBLE))
            }
        })
        return MenuActionResult.Success(MenuUpdate.None)
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** ツアー看板アイテムを生成します。設置時はアイテムPDCから対象ツアーを復元します。 */
    internal fun createSignItem(player: Player, tour: PreviewTourData): ItemStack {
        val item = ItemStack(Material.OAK_SIGN)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_SIGN_ITEM_NAME))
            meta.lore(i18nComponentList(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_SIGN_ITEM_LORE, mapOf("tour" to tour.title)))
            meta.persistentDataContainer.set(PreviewTourListener.SIGN_ITEM_KEY, PersistentDataType.STRING, tour.uuid.toString())
        }
        return item
    }

    private fun tour(route: MenuRoute): PreviewTourData? =
        route.payload[TOUR_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let(storage::getTour)

    private fun creatorName(tour: PreviewTourData): String =
        Bukkit.getOfflinePlayer(tour.createdBy).name ?: "-"

    /** 巡業へ登録できるワールドは、提出済みで完成登録が残っている本番相当ワールドのみです。 */
    private fun completedWorlds(): List<WorldData> =
        MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter(::eligibleWorld)
            .sortedBy { it.name.lowercase() }

    private fun eligibleWorld(world: WorldData): Boolean =
        plugin.chanponWorldStorage.isSubmissionCompleted(world.uuid) && !world.isArchived

    private fun eligibleWorld(worldUuid: UUID): Boolean =
        MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let(::eligibleWorld) == true

    private fun pageCount(itemCount: Int): Int =
        if (itemCount == 0) 1 else (itemCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE

    private fun page(route: MenuRoute): Int = route.payload[PAGE]?.toIntOrNull() ?: 0

    private fun listRoute(page: Int): MenuRoute =
        MenuRoute(OWNER, ROUTE_LIST, mapOf(PAGE to page.toString()))

    private fun editRoute(tourUuid: String): MenuRoute =
        MenuRoute(OWNER, ROUTE_EDIT, mapOf(TOUR_UUID to tourUuid))

    private fun stopsRoute(tourUuid: String, page: Int): MenuRoute =
        MenuRoute(OWNER, ROUTE_STOPS, mapOf(TOUR_UUID to tourUuid, PAGE to page.toString()))

    private fun textDialog(
        player: Player,
        id: String,
        titleKey: LocalizationKey<String>,
        confirmKey: LocalizationKey<String>,
        inputs: List<MenuDialogInput>,
        onConfirm: (MenuDialogResponse) -> MenuActionResult,
    ): MenuDialogRequest = MenuDialogRequest(
        owner = OWNER,
        id = id,
        title = i18nComponent(player, titleKey),
        body = emptyList(),
        inputs = inputs,
        confirm = MenuDialogButton(
            label = i18nComponent(player, confirmKey),
            handler = dialogHandler(onConfirm),
        ),
        cancel = MenuDialogButton(
            label = i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_CANCEL),
            handler = dialogHandler { MenuActionResult.Success(MenuUpdate.Resume) },
        ),
    )

    private fun durationDialog(
        player: Player,
        worldName: String,
        initialSeconds: Int,
        onConfirm: (Int) -> MenuActionResult,
    ): MenuDialogRequest = MenuDialogRequest(
        owner = OWNER,
        id = "preview-tour-duration",
        title = i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_DURATION_TITLE),
        body = listOf(
            i18nComponent(
                player,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_DURATION_BODY,
                mapOf("world" to worldName),
            ),
        ),
        inputs = listOf(
            MenuDialogInput.Text(
                "seconds",
                i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_DURATION_LABEL),
                initial = initialSeconds.toString(),
                maxLength = 8,
            ),
        ),
        confirm = MenuDialogButton(
            label = i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_CONFIRM),
            handler = dialogHandler { response ->
                val seconds = response.textValue("seconds").trim().toIntOrNull()
                if (seconds == null || seconds <= 0 || seconds > MAX_STOP_SECONDS) {
                    MenuActionResult.Rejected(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_DURATION_INVALID))
                } else {
                    onConfirm(seconds)
                }
            },
        ),
        cancel = MenuDialogButton(
            label = i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_DIALOG_CANCEL),
            handler = dialogHandler { MenuActionResult.Success(MenuUpdate.Resume) },
        ),
    )

    private fun simpleEntry(
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
            name = opaqueName(text),
            role = role,
            actions = listOf(
                chanponGestureAction(
                    actionId,
                    MenuGesture.ANY,
                    actionText,
                    payload,
                    safety = actionSafety(actionId),
                ),
            ),
        ),
    )

    private fun controlEntry(
        player: Player,
        slot: Int,
        material: Material,
        display: LocalizationKey<String>,
        description: LocalizationKey<List<String>>,
        action: LocalizationKey<String>,
        actionId: String,
        payload: Map<String, String> = emptyMap(),
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.FixedLabel(i18nComponent(player, display)),
            role = GuiElementRole.ACTION,
            description = i18nList(player, description),
            actions = listOf(
                chanponGestureAction(
                    actionId,
                    MenuGesture.ANY,
                    i18n(player, action),
                    payload,
                    safety = actionSafety(actionId),
                ),
            ),
        ),
    )

    private fun infoEntry(player: Player, slot: Int, lines: List<String>): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuDisplay(
            GuiMenuDisplaySpec(
                slot,
                GuiItemSpec(
                    Material.BOOK,
                    GuiNameSpec.FixedLabel(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_ADMIN_INFO_NAME)),
                    framedLore(lines.map(GuiLoreLine::Text), GuiLoreFrame.BOTH),
                    GuiElementRole.CONTENT,
                    1,
                ),
            ),
        )

    private fun actionSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_BACK, ACTION_PAGE, ACTION_CANCEL, ACTION_OPEN, ACTION_STOPS, ACTION_ADD_STOP -> MenuActionSafety.NAVIGATION_ONLY
        ACTION_CREATE, ACTION_RENAME, ACTION_STOP_DURATION, ACTION_PICK -> MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE
        ACTION_DELETE -> MenuActionSafety.CONFIRM_ENTRY
        ACTION_CONFIRM_DELETE -> MenuActionSafety.IRREVERSIBLE
        ACTION_SIGN, ACTION_STOP_UP, ACTION_STOP_DOWN, ACTION_STOP_REMOVE -> MenuActionSafety.EXTERNAL_SIDE_EFFECT
        else -> error("Preview-tour action must declare a dedicated safety mapping: $actionId")
    }

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): String =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun i18nList(player: Player?, key: LocalizationKey<List<String>>, placeholders: Map<String, Any> = emptyMap()): List<String> =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): Component =
        CCSystem.getAPI().getI18nComponent(player, key, placeholders)

    private fun i18nComponentList(player: Player?, key: LocalizationKey<List<String>>, placeholders: Map<String, Any> = emptyMap()): List<Component> =
        CCSystem.getAPI().getI18nComponentList(player, key, placeholders)

    companion object {
        private const val OWNER = "mwm-chanpon"
        const val ADMIN_PERMISSION = "mwmchanpon.admin"
        const val ROUTE_LIST = "preview-tour-admin"
        private const val ROUTE_EDIT = "preview-tour-admin-edit"
        private const val ROUTE_STOPS = "preview-tour-admin-stops"
        private const val ROUTE_PICK = "preview-tour-admin-pick"
        private const val ROUTE_DELETE_CONFIRM = "preview-tour-admin-delete"
        private const val ACTION_BACK = "back"
        private const val ACTION_PAGE = "page"
        private const val ACTION_OPEN = "open"
        private const val ACTION_CREATE = "create"
        private const val ACTION_RENAME = "rename"
        private const val ACTION_STOPS = "stops"
        private const val ACTION_SIGN = "sign"
        private const val ACTION_DELETE = "delete"
        private const val ACTION_CONFIRM_DELETE = "confirm-delete"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_STOP_UP = "stop-up"
        private const val ACTION_STOP_DOWN = "stop-down"
        private const val ACTION_STOP_DURATION = "stop-duration"
        private const val ACTION_STOP_REMOVE = "stop-remove"
        private const val ACTION_ADD_STOP = "add-stop"
        private const val ACTION_PICK = "pick"
        private const val PAGE = "page"
        private const val TOUR_UUID = "tour-uuid"
        private const val WORLD_UUID = "world-uuid"
        private const val STOP_INDEX = "stop-index"
        private const val ITEMS_PER_PAGE = 45
        private const val STOPS_PER_PAGE = 45
        private const val DEFAULT_STOP_SECONDS = 300
        private const val MAX_STOP_SECONDS = 24 * 60 * 60
    }
}

private fun dialogHandler(
    handler: (MenuDialogResponse) -> MenuActionResult,
): MenuDialogHandler =
    MenuDialogHandler { _, response -> handler(response) }
