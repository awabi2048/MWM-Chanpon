package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.LocalizationKey

import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiElementRole
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
import com.awabi2048.ccsystem.api.gui.menuUnavailable
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.mwmchanpon.service.WorldDataExportService
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

class WorldDataExportMenu(
    private val plugin: MWMChanponPlugin,
    private val exportService: WorldDataExportService
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
                    ACTION_TOGGLE to MenuActionHandler(::toggle),
                    ACTION_SELECT_ALL to MenuActionHandler(::selectAll),
                    ACTION_CLEAR_SELECTION to MenuActionHandler(::clear),
                    ACTION_EXPORT_SELECTION to MenuActionHandler(::showConfirmation),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_CONFIRM,
                renderer = { context -> renderConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM to MenuActionHandler(::confirmExport),
                ),
            ),
        )
    }

    fun open(player: Player, page: Int = 0, selected: Set<UUID> = emptySet()) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        runtime.navigate(player, listRoute(page, selected.toList()))
    }

    internal fun route(page: Int = 0, selected: Set<UUID> = emptySet()): MenuRoute =
        listRoute(page, selected.toList())

    private fun renderList(player: Player, route: MenuRoute): InventoryMenuView {
        val worlds = exportService.productionWorlds()
        val pages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page(route).coerceIn(0, pages - 1)
        val exportableIds = worlds.mapTo(mutableSetOf()) { it.uuid }
        val safeSelected = selection(route).filter { it in exportableIds }
        val layout = ChanponGuiElements.pagedListLayout()
        val elements = mutableListOf<MenuElement>()
        if (safePage > 0) elements += simpleActionEntry(
            player, 45, Material.ARROW, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV),
            GuiElementRole.NAVIGATION, ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_PREV_ACTION),
            mapOf(PAGE to (safePage - 1).toString()),
        )
        elements += simpleActionEntry(
            player, 49, Material.REDSTONE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_BACK),
            GuiElementRole.BACK, ACTION_BACK, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_BACK_ACTION),
        )
        elements += infoEntry(player, 50, worlds.size, safeSelected.size, safePage + 1, pages)
        if (safePage < pages - 1) elements += simpleActionEntry(
            player, 53, Material.ARROW, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT),
            GuiElementRole.NAVIGATION, ACTION_PAGE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_NEXT_ACTION),
            mapOf(PAGE to (safePage + 1).toString()),
        )
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            elements += worldItem(
                player,
                layout.itemSlots[index],
                world,
                world.uuid in safeSelected,
            )
        }
        elements += controlEntry(player, 47, Material.CHEST, ControlPresentation(ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_SELECT_ALL_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_SELECT_ALL_DESCRIPTION, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_SELECT_ALL_ACTION), ACTION_SELECT_ALL)
        elements += controlEntry(player, 48, Material.BARRIER, ControlPresentation(ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CLEAR_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CLEAR_DESCRIPTION, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CLEAR_ACTION), ACTION_CLEAR_SELECTION)
        elements += exportSelectionItem(player, 51, safeSelected.size)
        return InventoryMenuView(layout.size, i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_TITLE), elements)
    }

    private fun renderConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val selected = selection(route)
        val worlds = exportService.productionWorlds().associateBy { it.uuid }
        val selectedWorlds = selected.mapNotNull(worlds::get)
        val primary = selectedWorlds.firstOrNull()
        val fiscalYear = plugin.config.getInt("standalone_export.fiscal_year")
        val fileName = runCatching {
            me.awabi2048.mwmchanpon.service.StandaloneExportFileName.create(fiscalYear, selectedWorlds.map { it.name })
        }.getOrDefault("-")
        val layout = ChanponGuiElements.confirmationLayout()
        val elements = listOf(
            confirmationInfoEntry(
                player, layout.previewSlot,
                primary?.name ?: "-", selectedWorlds.size, fileName,
            ),
            controlEntry(
                player,
                layout.confirmSlot,
                if (primary != null) Material.LIME_CONCRETE else Material.GRAY_DYE,
                ControlPresentation(ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_EXECUTE_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_EXECUTE_DESCRIPTION, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_EXECUTE_ACTION),
                ACTION_CONFIRM,
                GuiElementRole.CONFIRM,
                primary != null,
            ),
            simpleActionEntry(
                player, layout.cancelSlot, Material.REDSTONE, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_BACK),
                GuiElementRole.CANCEL, ACTION_BACK, i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_BACK_ACTION),
            ),
        )
        return InventoryMenuView(layout.size, i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_TITLE), elements)
            .asConfirmation()
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun changePage(context: MenuActionContext) =
        MenuActionResult.Success(MenuUpdate.Replace(listRoute(context.payload[PAGE]?.toIntOrNull() ?: 0, selection(context.route))))

    private fun toggle(context: MenuActionContext): MenuActionResult {
        val uuid = context.payload[WORLD_UUID]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        val updated = selection(context.route).toMutableList()
        if (!updated.remove(uuid)) updated += uuid
        return MenuActionResult.Success(MenuUpdate.Replace(listRoute(page(context.route), updated)))
    }

    private fun selectAll(context: MenuActionContext) =
        MenuActionResult.Success(MenuUpdate.Replace(listRoute(page(context.route), exportService.productionWorlds().map { it.uuid })))

    private fun clear(context: MenuActionContext) =
        MenuActionResult.Success(MenuUpdate.Replace(listRoute(page(context.route), emptyList())))

    private fun showConfirmation(context: MenuActionContext): MenuActionResult {
        val selected = selection(context.route)
        if (selected.isEmpty()) return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Navigate(confirmRoute(selected)))
    }

    private fun confirmExport(context: MenuActionContext): MenuActionResult {
        val selected = selection(context.route)
        if (selected.isEmpty()) return MenuActionResult.Rejected()
        exportService.exportStandaloneWorld(selected).thenAccept { result ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                context.player.sendMessage(resultMessage(context.player, result.success, result.file?.name))
                if (!result.success) {
                    result.message.lineSequence()
                        .filter(String::isNotBlank)
                        .forEach { detail ->
                            context.player.sendMessage(
                                i18nComponent(
                                    context.player,
                                    ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_EXPORT_FAILURE_DETAIL,
                                    mapOf("detail" to detail),
                                )
                            )
                        }
                }
            })
        }
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun worldItem(
        player: Player,
        slot: Int,
        world: WorldData,
        selected: Boolean,
    ): MenuElement {
        return CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = world.icon,
            name = GuiNameSpec.TargetIdentity(
                i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_WORLD_DISPLAY, mapOf("world" to world.name)),
            ),
            role = GuiElementRole.ACTION,
            data = listOf(
                GuiMenuEntryData(
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_WORLD_OWNER_LABEL),
                    Bukkit.getOfflinePlayer(world.owner).name ?: "-",
                ),
                GuiMenuEntryData(i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_WORLD_CREATED_LABEL), world.createdAt),
            ),
            options = listOf(GuiMenuEntryOption(
                i18n(player, if (selected) ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_WORLD_SELECTED else ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_WORLD_NOT_SELECTED),
                selected,
            )),
            actions = listOf(chanponGestureAction(
                ACTION_TOGGLE,
                MenuGesture.ANY,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_WORLD_ACTION),
                mapOf(WORLD_UUID to world.uuid.toString()),
                // 選択集合は次Routeにのみ保持され、サーバー状態を変更しません。
                safety = MenuActionSafety.NAVIGATION_ONLY,
                classification = ChanponReversibleActionClassification.Operation.EXPORT_SELECTION_TOGGLE,
            )),
            glint = selected,
        ),
    )
    }

    private fun exportSelectionItem(player: Player, slot: Int, selectedCount: Int): MenuElement {
        val enabled = selectedCount > 0
        val gui = CCSystem.getAPI().getGuiElementService()
        val spec = GuiMenuEntrySpec(
                slot = slot,
                material = if (enabled) Material.DISPENSER else Material.GRAY_DYE,
                name = GuiNameSpec.FixedLabel(i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_EXECUTE_DISPLAY)),
                role = if (enabled) GuiElementRole.CONFIRM else GuiElementRole.CONTENT,
                data = listOf(GuiMenuEntryData(
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_EXECUTE_SELECTED_LABEL),
                    selectedCount,
                )),
                warnings = if (enabled) emptyList() else {
                    listOf(i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_EXECUTE_DISABLED))
                },
                actions = if (enabled) listOf(chanponGestureAction(
                    ACTION_EXPORT_SELECTION,
                    MenuGesture.ANY,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_EXECUTE_ACTION),
                    safety = MenuActionSafety.CONFIRM_ENTRY,
                )) else emptyList(),
            )
        return if (enabled) gui.menuEntry(player, spec) else gui.menuUnavailable(
            player,
            spec,
            i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_EXECUTE_DISABLED),
        )
    }

    private fun controlEntry(
        player: Player,
        slot: Int,
        material: Material,
        presentation: ControlPresentation,
        actionId: String,
        role: GuiElementRole = GuiElementRole.ACTION,
        enabled: Boolean = true,
    ): MenuElement {
        val gui = CCSystem.getAPI().getGuiElementService()
        val spec = GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.FixedLabel(i18nComponent(player, presentation.display)),
            role = if (enabled) role else GuiElementRole.CONTENT,
            description = listOf(i18n(player, presentation.description)),
            actions = if (enabled) listOf(chanponGestureAction(
                actionId,
                MenuGesture.ANY,
                i18n(player, presentation.action),
                safety = exportActionSafety(actionId),
                classification = exportRouteClassification(actionId),
            )) else emptyList(),
        )
        return if (enabled) gui.menuEntry(player, spec) else gui.menuUnavailable(
            player,
            spec,
            i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_EXECUTE_DISABLED),
        )
    }

    private data class ControlPresentation(
        val display: LocalizationKey<String>,
        val description: LocalizationKey<String>,
        val action: LocalizationKey<String>,
    )

    private fun confirmationInfoEntry(
        player: Player,
        slot: Int,
        primaryName: String,
        selectedCount: Int,
        fileName: String,
    ): MenuElement = displayEntry(
        slot,
        Material.BOOK,
        GuiNameSpec.FixedLabel(i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_INFO_DISPLAY)),
        me.awabi2048.mwmchanpon.ui.framedLore(
                    listOf(
                        GuiLoreLine.Data(i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_INFO_PRIMARY_LABEL), primaryName, "§f"),
                        GuiLoreLine.Data(i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_INFO_COUNT_LABEL), selectedCount, "§f"),
                        GuiLoreLine.Data(i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_INFO_FILE_LABEL), fileName, "§f"),
                        GuiLoreLine.Warning(i18n(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_CONFIRM_INFO_WARNING)),
                    ),
            GuiLoreFrame.BOTH,
        ),
    )

    private fun listRoute(page: Int, selected: List<UUID>) =
        MenuRoute(OWNER, ROUTE_LIST, mapOf(PAGE to page.toString(), SELECTION to selected.joinToString(",")))

    private fun confirmRoute(selected: List<UUID>) =
        MenuRoute(OWNER, ROUTE_CONFIRM, mapOf(SELECTION to selected.joinToString(",")))

    private fun page(route: MenuRoute): Int = route.payload[PAGE]?.toIntOrNull() ?: 0

    private fun selection(route: MenuRoute): List<UUID> =
        route.payload[SELECTION]
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank)
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .distinct()

    private fun infoEntry(
        player: Player,
        slot: Int,
        count: Int,
        selectedCount: Int,
        page: Int,
        pages: Int
    ): MenuElement = displayEntry(
        slot,
        Material.BOOK,
        GuiNameSpec.FixedLabel(i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_INFO_DISPLAY)),
        me.awabi2048.mwmchanpon.ui.framedLore(
                    i18nList(
                        player,
                        ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_INFO_LORE,
                        mapOf("count" to count, "selected" to selectedCount, "page" to page, "pages" to pages)
                    ).map(GuiLoreLine::Text),
            GuiLoreFrame.BOTH,
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
            name = opaqueName(text, GuiNameStyle.DEFAULT),
            role = role,
            actions = listOf(
                chanponGestureAction(actionId, MenuGesture.ANY, actionText, payload, safety = exportActionSafety(actionId)),
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

    private fun resultMessage(player: Player, success: Boolean, fileName: String?): Component =
        i18nComponent(
            player,
            if (success) ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_EXPORT_SUCCESS else ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_EXPORT_FAILED,
            mapOf("file" to (fileName ?: "-"))
        )

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, key, placeholders)

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun exportActionSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_BACK, ACTION_PAGE, ACTION_TOGGLE, ACTION_SELECT_ALL, ACTION_CLEAR_SELECTION -> MenuActionSafety.NAVIGATION_ONLY
        ACTION_EXPORT_SELECTION -> MenuActionSafety.CONFIRM_ENTRY
        ACTION_CONFIRM -> MenuActionSafety.IRREVERSIBLE
        else -> error("World-data export action must declare a dedicated safety mapping: $actionId")
    }

    private fun exportRouteClassification(actionId: String): ChanponReversibleActionClassification.Operation? = when (actionId) {
        ACTION_TOGGLE -> ChanponReversibleActionClassification.Operation.EXPORT_SELECTION_TOGGLE
        ACTION_SELECT_ALL, ACTION_CLEAR_SELECTION -> ChanponReversibleActionClassification.Operation.EXPORT_SELECTION_CONTROLS
        else -> null
    }

    private fun i18nList(player: Player?, key: LocalizationKey<List<String>>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE_LIST = "world_data_export"
        private const val ROUTE_CONFIRM = "world_data_export_confirm"
        private const val PAGE = "page"
        private const val SELECTION = "selection"
        private const val WORLD_UUID = "world_uuid"
        private const val ITEMS_PER_PAGE = 36
        private const val TYPE_CONTROL = "chanpon_export_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_BACK = "back"
        private const val ACTION_PAGE = "page"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_SELECT_ALL = "select_all"
        private const val ACTION_CLEAR_SELECTION = "clear_selection"
        private const val ACTION_EXPORT_SELECTION = "export_selection"
        private const val ACTION_CONFIRM = "confirm"
    }
}
