package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiElementRole
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
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
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
            elements += actionElement(
                layout.itemSlots[index],
                worldItem(player, world, world.uuid in safeSelected),
                GuiElementRole.ACTION,
                ACTION_TOGGLE,
                mapOf(WORLD_UUID to world.uuid.toString()),
            )
        }
        elements += actionElement(47, selectAllItem(player), GuiElementRole.ACTION, ACTION_SELECT_ALL)
        elements += actionElement(48, clearSelectionItem(player), GuiElementRole.ACTION, ACTION_CLEAR_SELECTION)
        elements += actionElement(51, exportSelectionItem(player, safeSelected.size), GuiElementRole.CONFIRM, ACTION_EXPORT_SELECTION, enabled = safeSelected.isNotEmpty())
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
        val elements = listOf(
            MenuElement(13, confirmationInfoItem(player, primary?.name ?: "-", selectedWorlds.size, fileName), GuiElementRole.CONTENT),
            actionElement(11, backItem(player), GuiElementRole.BACK, ACTION_BACK),
            actionElement(15, confirmationExecuteItem(player), GuiElementRole.CONFIRM, ACTION_CONFIRM, enabled = primary != null),
        )
        return InventoryMenuView(27, i18nComponent(player, "export_menu.confirm.title"), elements)
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
            })
        }
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun worldItem(player: Player, world: WorldData, selected: Boolean): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.world.display", mapOf("world" to world.name)))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(
                    GuiLoreLine.Data(
                        i18n(player, "export_menu.world.owner_label"),
                        Bukkit.getOfflinePlayer(world.owner).name ?: "-",
                        "§f"
                    ),
                    GuiLoreLine.Data(i18n(player, "export_menu.world.created_label"), world.createdAt, "§f")
                )),
                GuiLoreBlock(listOf(
                    GuiLoreLine.Option(
                        i18n(player, if (selected) "export_menu.world.selected" else "export_menu.world.not_selected"),
                        selected,
                        "§a",
                        "§7"
                    )
                )),
                GuiLoreBlock(listOf(
                    GuiLoreActions.singleClick(
                        mwm().languageManager,
                        player,
                        i18n(player, "export_menu.world.action")
                    )
                ))
            )))
            meta.setEnchantmentGlintOverride(selected)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun selectAllItem(player: Player): ItemStack =
        controlItem(
            player,
            Material.CHEST,
            "export_menu.select_all",
            ACTION_SELECT_ALL
        )

    private fun clearSelectionItem(player: Player): ItemStack =
        controlItem(
            player,
            Material.BARRIER,
            "export_menu.clear",
            ACTION_CLEAR_SELECTION
        )

    private fun controlItem(
        player: Player,
        material: Material,
        key: String,
        action: String
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "$key.description")))),
                GuiLoreBlock(listOf(
                    GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "$key.action"))
                ))
            )))
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun exportSelectionItem(player: Player, selectedCount: Int): ItemStack {
        val enabled = selectedCount > 0
        val item = ItemStack(if (enabled) Material.DISPENSER else Material.GRAY_DYE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "export_menu.execute.display"))
            val status = mutableListOf<GuiLoreLine>(
                GuiLoreLine.Data(
                    i18n(player, "export_menu.execute.selected_label"),
                    selectedCount,
                    "§f"
                )
            )
            if (!enabled) status += GuiLoreLine.Warning(i18n(player, "export_menu.execute.disabled"))
            meta.lore(structuredBlocks(listOf(
                GuiLoreBlock(status),
                GuiLoreBlock(listOf(
                    GuiLoreActions.singleClick(
                        mwm().languageManager,
                        player,
                        i18n(player, "export_menu.execute.action")
                    )
                ))
            )))
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, ACTION_EXPORT_SELECTION)
        return item
    }

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
                        GuiLoreLine.Data(i18n(player, "export_menu.confirm.info.primary_label"), primaryName, "ﾂｧf"),
                        GuiLoreLine.Data(i18n(player, "export_menu.confirm.info.count_label"), selectedCount, "ﾂｧf"),
                        GuiLoreLine.Data(i18n(player, "export_menu.confirm.info.file_label"), fileName, "ﾂｧf"),
                        GuiLoreLine.Warning(i18n(player, "export_menu.confirm.info.warning")),
                    ),
                ),
            )
        }
        return item
    }

    private fun confirmationExecuteItem(player: Player): ItemStack =
        controlItem(player, Material.LIME_CONCRETE, "export_menu.confirm.execute", ACTION_CONFIRM)

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
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun navItem(player: Player, next: Boolean, page: Int): ItemStack {
        val item = ChanponGuiElements.navigation(i18n(player, if (next) "common.next" else "common.prev"), next)
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        ItemTag.setTargetPage(item, page)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ChanponGuiElements.back(i18n(player, "common.back"))
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
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

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)

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
