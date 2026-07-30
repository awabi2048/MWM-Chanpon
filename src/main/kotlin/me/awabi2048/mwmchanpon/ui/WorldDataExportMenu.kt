package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryAction
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryOption
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiValueTone
import com.awabi2048.ccsystem.api.gui.MenuAcceptedClicks
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
import me.awabi2048.mwmchanpon.service.WorldDataExportService
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

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
        if (safePage > 0) elements += actionElement(45, navItem(player, false, safePage - 1), GuiElementRole.NAVIGATION, ACTION_PAGE, mapOf(PAGE to (safePage - 1).toString()))
        elements += actionElement(49, backItem(player), GuiElementRole.BACK, ACTION_BACK)
        elements += MenuElement(50, infoItem(player, worlds.size, safeSelected.size, safePage + 1, pages), GuiElementRole.CONTENT)
        if (safePage < pages - 1) elements += actionElement(53, navItem(player, true, safePage + 1), GuiElementRole.NAVIGATION, ACTION_PAGE, mapOf(PAGE to (safePage + 1).toString()))
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            elements += worldItem(
                player,
                layout.itemSlots[index],
                world,
                world.uuid in safeSelected,
            )
        }
        elements += controlEntry(player, 47, Material.CHEST, "export_menu.select_all", ACTION_SELECT_ALL)
        elements += controlEntry(player, 48, Material.BARRIER, "export_menu.clear", ACTION_CLEAR_SELECTION)
        elements += exportSelectionItem(player, 51, safeSelected.size)
        return InventoryMenuView(layout.size, i18nComponent(player, "export_menu.title"), elements)
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
            MenuElement(
                layout.previewSlot,
                confirmationInfoItem(player, primary?.name ?: "-", selectedWorlds.size, fileName),
                GuiElementRole.CONTENT,
            ),
            controlEntry(
                player,
                layout.confirmSlot,
                if (primary != null) Material.LIME_CONCRETE else Material.GRAY_DYE,
                "export_menu.confirm.execute",
                ACTION_CONFIRM,
                GuiElementRole.CONFIRM,
                primary != null,
            ),
            actionElement(layout.cancelSlot, backItem(player), GuiElementRole.CANCEL, ACTION_BACK),
        )
        return InventoryMenuView(layout.size, i18nComponent(player, "export_menu.confirm.title"), elements)
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
                                    "message.export_failure_detail",
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
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = world.icon,
            name = GuiNameSpec.Component(
                i18nComponent(player, "export_menu.world.display", mapOf("world" to world.name)),
            ),
            role = GuiElementRole.ACTION,
            data = listOf(
                GuiMenuEntryData(
                    i18n(player, "export_menu.world.owner_label"),
                    Bukkit.getOfflinePlayer(world.owner).name ?: "-",
                ),
                GuiMenuEntryData(i18n(player, "export_menu.world.created_label"), world.createdAt),
            ),
            options = listOf(GuiMenuEntryOption(
                i18n(player, if (selected) "export_menu.world.selected" else "export_menu.world.not_selected"),
                selected,
            )),
            actions = listOf(GuiMenuEntryAction(
                ACTION_TOGGLE,
                MenuAcceptedClicks.LEFT_RIGHT,
                i18n(player, "export_menu.world.action"),
                mapOf(WORLD_UUID to world.uuid.toString()),
            )),
            glint = selected,
        ),
    )

    private fun exportSelectionItem(player: Player, slot: Int, selectedCount: Int): MenuElement {
        val enabled = selectedCount > 0
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = if (enabled) Material.DISPENSER else Material.GRAY_DYE,
                name = GuiNameSpec.Component(i18nComponent(player, "export_menu.execute.display")),
                role = if (enabled) GuiElementRole.CONFIRM else GuiElementRole.CONTENT,
                data = listOf(GuiMenuEntryData(
                    i18n(player, "export_menu.execute.selected_label"),
                    selectedCount,
                )),
                warnings = if (enabled) emptyList() else {
                    listOf(i18n(player, "export_menu.execute.disabled"))
                },
                actions = if (enabled) listOf(GuiMenuEntryAction(
                    ACTION_EXPORT_SELECTION,
                    MenuAcceptedClicks.LEFT_RIGHT,
                    i18n(player, "export_menu.execute.action"),
                )) else emptyList(),
            ),
        )
    }

    private fun controlEntry(
        player: Player,
        slot: Int,
        material: Material,
        key: String,
        actionId: String,
        role: GuiElementRole = GuiElementRole.ACTION,
        enabled: Boolean = true,
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.Component(i18nComponent(player, "$key.display")),
            role = if (enabled) role else GuiElementRole.CONTENT,
            description = listOf(i18n(player, "$key.description")),
            actions = if (enabled) listOf(GuiMenuEntryAction(
                actionId,
                MenuAcceptedClicks.LEFT_RIGHT,
                i18n(player, "$key.action"),
            )) else emptyList(),
        ),
    )

    private fun confirmationInfoItem(
        player: Player,
        primaryName: String,
        selectedCount: Int,
        fileName: String,
    ): ItemStack {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.confirm.info.display"))
            meta.lore(
                ChanponLore.build(
                    listOf(
                        GuiLoreLine.Data(i18n(player, "export_menu.confirm.info.primary_label"), primaryName, "§f"),
                        GuiLoreLine.Data(i18n(player, "export_menu.confirm.info.count_label"), selectedCount, "§f"),
                        GuiLoreLine.Data(i18n(player, "export_menu.confirm.info.file_label"), fileName, "§f"),
                        GuiLoreLine.Warning(i18n(player, "export_menu.confirm.info.warning")),
                    ),
                ),
            )
        }
        return item
    }

    private fun actionElement(
        slot: Int,
        item: ItemStack,
        role: GuiElementRole,
        actionId: String,
        payload: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
    ) = MenuElement(slot, item, role, actionId, payload, enabled)

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

    private fun infoItem(
        player: Player,
        count: Int,
        selectedCount: Int,
        page: Int,
        pages: Int
    ): ItemStack {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.info.display"))
            meta.lore(
                ChanponLore.build(
                    i18nList(
                        player,
                        "export_menu.info.lore",
                        mapOf("count" to count, "selected" to selectedCount, "page" to page, "pages" to pages)
                    ).map(GuiLoreLine::Text)
                )
            )
        }
        return item
    }

    private fun navItem(player: Player, next: Boolean, page: Int): ItemStack {
        val item = ChanponGuiElements.navigation(i18n(player, if (next) "common.next" else "common.prev"), next)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ChanponGuiElements.back(i18n(player, "common.back"))
        return item
    }

    private fun resultMessage(player: Player, success: Boolean, fileName: String?): Component =
        i18nComponent(
            player,
            if (success) "message.export_success" else "message.export_failed",
            mapOf("file" to (fileName ?: "-"))
        )

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    private fun structuredBlocks(blocks: List<GuiLoreBlock>): List<Component> =
        CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))

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
