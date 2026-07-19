package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.AdminWorldListProvider
import me.awabi2048.myworldmanager.api.extension.AdminWorldListRequest
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ChanponAdminWorldListMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) : AdminWorldListProvider, Listener {
    private val sessions = ConcurrentHashMap<UUID, ListSession>()

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, request: AdminWorldListRequest): Boolean {
        val session = sessions.computeIfAbsent(player.uniqueId) { ListSession() }
        request.fromAdminMenu?.let { session.fromAdminMenu = it }
        open(player, request.page ?: session.page, session.visibilityFilter, session.sortType, session.fromAdminMenu)
        return true
    }

    fun open(
        player: Player,
        page: Int = 0,
        visibilityFilter: VisibilityFilter = sessions[player.uniqueId]?.visibilityFilter ?: VisibilityFilter.ALL,
        sortType: SortType = sessions[player.uniqueId]?.sortType ?: SortType.CREATED_DESC,
        fromAdminMenu: Boolean = sessions[player.uniqueId]?.fromAdminMenu ?: false
    ) {
        val session = sessions.computeIfAbsent(player.uniqueId) { ListSession() }
        session.visibilityFilter = visibilityFilter
        session.sortType = sortType
        session.fromAdminMenu = fromAdminMenu

        val worlds = filteredWorlds(player, visibilityFilter, sortType)
        val totalPages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, totalPages - 1)
        session.page = safePage

        val holder = ChanponAdminWorldListHolder(safePage, visibilityFilter, sortType, fromAdminMenu)
        val layout = ChanponGuiElements.pagedListLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "admin_list.title"))
        holder.inv = inventory

        ChanponGuiElements.applyStandardFrame(inventory)
        ChanponGuiElements.fill(inventory)

        currentWorld(player)?.let { inventory.setItem(4, worldItem(player, it, current = true)) }

        worlds.drop(safePage * ITEMS_PER_PAGE)
            .take(ITEMS_PER_PAGE)
            .forEachIndexed { index, worldData ->
                inventory.setItem(layout.itemSlots[index], worldItem(player, worldData, current = false))
            }

        ChanponGuiElements.setSettingsFooter(
            inventory,
            if (fromAdminMenu) backItem(player) else null,
            infoItem(player, worlds.size, safePage + 1, totalPages)
        )
        inventory.setItem(47, visibilityFilterItem(player, visibilityFilter))
        inventory.setItem(51, sortItem(player, sortType))
        if (safePage > 0) inventory.setItem(52, navItem(player, false, safePage - 1))
        if (safePage < totalPages - 1) inventory.setItem(53, navItem(player, true, safePage + 1))

        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? ChanponAdminWorldListHolder ?: return
        event.cancelWithDebug("ChanponAdminWorldListMenu.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> mwm().adminCommandGui.open(player)
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> {
                open(player, ItemTag.getTargetPage(item) ?: holder.page, holder.visibilityFilter, holder.sortType, holder.fromAdminMenu)
            }
            ItemTag.TYPE_GUI_EXTENSION -> handleControl(player, holder, item, event.click.isLeftClick)
            ItemTag.TYPE_GUI_WORLD_ITEM, ItemTag.TYPE_GUI_ADMIN_CURRENT_WORLD_INFO -> handleWorldClick(player, item, event.click)
        }
    }

    private fun handleControl(player: Player, holder: ChanponAdminWorldListHolder, item: ItemStack, reverse: Boolean) {
        if (ItemTag.getExtensionId(item) != PROVIDER_ID) return
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_VISIBILITY_FILTER -> open(player, 0, GuiCycle.select(holder.visibilityFilter, VisibilityFilter.entries, reverse), holder.sortType, holder.fromAdminMenu)
            ACTION_SORT -> open(player, 0, holder.visibilityFilter, GuiCycle.select(holder.sortType, SortType.entries, reverse), holder.fromAdminMenu)
        }
    }

    private fun handleWorldClick(player: Player, item: ItemStack, clickType: ClickType) {
        val uuid = ItemTag.getWorldUuid(item) ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(uuid) ?: return
        when {
            clickType == ClickType.MIDDLE && player.gameMode == GameMode.CREATIVE -> sendCopyMessage(player, worldData)
            clickType.isLeftClick -> warp(player, worldData)
            clickType.isRightClick -> openSettings(player, worldData)
        }
    }

    private fun filteredWorlds(player: Player, visibilityFilter: VisibilityFilter, sortType: SortType): List<WorldData> {
        val current = currentWorld(player)?.uuid
        val filtered = MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            .filter { it.uuid != current }
            .filter { worldData ->
                when (visibilityFilter) {
                    VisibilityFilter.ALL -> true
                    VisibilityFilter.PUBLIC -> storage.getVisibility(worldData.uuid) == ChanponWorldVisibility.PUBLIC
                    VisibilityFilter.DEVELOPMENT -> storage.getVisibility(worldData.uuid) == ChanponWorldVisibility.DEVELOPMENT
                    VisibilityFilter.PRIVATE -> storage.getVisibility(worldData.uuid) == ChanponWorldVisibility.PRIVATE
                }
            }
        return when (sortType) {
            SortType.CREATED_DESC -> filtered.sortedByDescending { it.createdAt }
            SortType.CREATED_ASC -> filtered.sortedBy { it.createdAt }
            SortType.WORLD_SIZE_DESC -> filtered.sortedByDescending { worldFolder(it).let { folder -> folderSize(folder) } }
        }
    }

    private fun currentWorld(player: Player): WorldData? {
        return MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)
    }

    private fun worldItem(player: Player, worldData: WorldData, current: Boolean): ItemStack {
        val visibility = storage.getVisibility(worldData.uuid)
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_list.world.display", mapOf("world" to worldData.name)))
            meta.lore(structuredLore(worldLore(player, worldData, visibility, current)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (current) ItemTag.TYPE_GUI_ADMIN_CURRENT_WORLD_INFO else ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, worldData.uuid)
        return item
    }

    private fun worldLore(
        player: Player,
        worldData: WorldData,
        visibility: ChanponWorldVisibility,
        current: Boolean
    ): List<GuiLoreLine> {
        val owner = Bukkit.getOfflinePlayer(worldData.owner).name ?: i18n(player, "admin_list.unknown")
        val members = (worldData.moderators + worldData.members)
            .distinct()
            .map { Bukkit.getOfflinePlayer(it).name ?: i18n(player, "admin_list.unknown") }
            .let {
                if (it.isEmpty()) i18n(player, "admin_list.world.no_members")
                else it.joinToString("§7, §f", prefix = "§f")
            }
        return buildList {
            add(GuiLoreLine.Data(i18n(player, "admin_list.world.owner_label"), owner, "§f"))
            add(GuiLoreLine.Data(i18n(player, "admin_list.world.members_label"), members, ""))
            add(GuiLoreLine.Data(i18n(player, "admin_list.world.created_label"), formatCreatedAt(worldData.createdAt), "§f"))
            add(GuiLoreLine.Data(i18n(player, "admin_list.world.visibility_label"), visibilityName(player, visibility), ""))
            add(GuiLoreLine.Data(i18n(player, "admin_list.world.directory_label"), worldDirectory(worldData), "§f"))
            add(GuiLoreLine.Spacer)
            add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.left"), i18n(player, "admin_list.world.action_warp")))
            if (current) {
                add(GuiLoreLine.Text(i18n(player, "admin_list.world.action_settings_current")))
            } else {
                add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.right"), i18n(player, "admin_list.world.action_settings")))
            }
            if (player.gameMode == GameMode.CREATIVE) {
                add(GuiLoreLine.Action(i18n(player, "common.middle_click"), i18n(player, "admin_list.world.action_copy")))
            }
        }
    }

    private fun visibilityName(player: Player, visibility: ChanponWorldVisibility): String {
        return when (visibility) {
            ChanponWorldVisibility.PUBLIC -> i18n(player, "admin_list.visibility.public")
            ChanponWorldVisibility.DEVELOPMENT -> i18n(player, "admin_list.visibility.development")
            ChanponWorldVisibility.PRIVATE -> i18n(player, "admin_list.visibility.private")
        }
    }

    private fun visibilityFilterItem(player: Player, filter: VisibilityFilter): ItemStack {
        return cycleControlItem(player, Material.ENDER_EYE, "admin_list.visibility_filter", i18n(player, filter.key), ACTION_VISIBILITY_FILTER)
    }

    private fun sortItem(player: Player, sortType: SortType): ItemStack {
        return cycleControlItem(player, Material.COMPARATOR, "admin_list.sort", i18n(player, sortType.key), ACTION_SORT)
    }

    private fun cycleControlItem(
        player: Player,
        material: Material,
        key: String,
        current: String,
        action: String
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(structuredLore(listOf(
                GuiLoreLine.Data(i18n(player, "$key.label"), current, "§e"),
            ) + GuiLoreActions.cyclePreviousNext(mwm().languageManager, player)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, PROVIDER_ID)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun infoItem(player: Player, total: Int, page: Int, totalPages: Int): ItemStack {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_list.info.display"))
            meta.lore(ChanponLore.build(i18nList(player, "admin_list.info.lore", mapOf("count" to total, "page" to page, "pages" to totalPages)).map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun controlItem(
        player: Player,
        material: Material,
        displayKey: String,
        loreKey: String,
        action: String,
        placeholders: Map<String, Any> = emptyMap()
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, displayKey, placeholders))
            meta.lore(ChanponLore.build(i18nList(player, loreKey, placeholders).map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, PROVIDER_ID)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun navItem(player: Player, next: Boolean, page: Int): ItemStack {
        val item = ItemStack(Material.ARROW)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, if (next) "admin_list.next" else "admin_list.prev"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        ItemTag.setTargetPage(item, page)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_list.back.display"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun warp(player: Player, worldData: WorldData) {
        player.closeInventory()
        MyWorldManagerApi.getWorldService()?.teleportToWorld(player, worldData.uuid)
    }

    private fun openSettings(player: Player, worldData: WorldData) {
        val mwm = mwm()
        val folderName = worldDirectory(worldData)
        if (!worldData.isArchived && Bukkit.getWorld(folderName) == null) {
            player.closeInventory()
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.world_loading"))
            Bukkit.getScheduler().runTask(mwm, Runnable {
                if (!player.isOnline) return@Runnable
                if (!mwm.worldService.loadWorld(worldData.uuid)) {
                    player.sendMessage(mwm.languageManager.getMessage(player, "error.load_failed"))
                    return@Runnable
                }
                openSettings(player, worldData)
            })
            return
        }
        mwm.settingsSessionManager.updateSessionAction(
            player,
            worldData.uuid,
            SettingsAction.VIEW_SETTINGS,
            true,
            true,
            null,
            null
        )
        mwm.worldSettingsGui.open(player, worldData, true, null, null)
    }

    private fun sendCopyMessage(player: Player, worldData: WorldData) {
        val directory = worldDirectory(worldData)
        player.sendMessage(Component.text("§8§m----------------"))
        player.sendMessage(Component.text("§e${worldData.name}"))
        player.sendMessage(
            Component.text("§7World: §f$directory")
                .clickEvent(ClickEvent.copyToClipboard(directory))
        )
        player.sendMessage(
            Component.text("§7Owner: §f${worldData.owner}")
                .clickEvent(ClickEvent.copyToClipboard(worldData.owner.toString()))
        )
        player.sendMessage(Component.text("§8§m----------------"))
    }

    private fun formatCreatedAt(value: String): String {
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        }.getOrDefault(value)
    }

    private fun worldDirectory(worldData: WorldData): String = worldData.customWorldName ?: "my_world.${worldData.uuid}"

    private fun worldFolder(worldData: WorldData): java.io.File {
        return MyWorldManagerApi.getWorldService()?.getWorldDirectory(worldData)
            ?: java.io.File(plugin.dataFolder, "unavailable")
    }

    private fun folderSize(file: java.io.File): Long {
        if (!file.exists()) return 0L
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
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

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private data class ListSession(
        var page: Int = 0,
        var visibilityFilter: VisibilityFilter = VisibilityFilter.ALL,
        var sortType: SortType = SortType.CREATED_DESC,
        var fromAdminMenu: Boolean = false
    )

    private class ChanponAdminWorldListHolder(
        val page: Int,
        val visibilityFilter: VisibilityFilter,
        val sortType: SortType,
        val fromAdminMenu: Boolean
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    enum class VisibilityFilter(val key: String) {
        ALL("admin_list.visibility_filter.all"),
        PUBLIC("admin_list.visibility_filter.public"),
        DEVELOPMENT("admin_list.visibility_filter.development"),
        PRIVATE("admin_list.visibility_filter.private")
    }

    enum class SortType(val key: String) {
        CREATED_DESC("admin_list.sort.created_desc"),
        CREATED_ASC("admin_list.sort.created_asc"),
        WORLD_SIZE_DESC("admin_list.sort.world_size_desc")
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.admin_world_list"
        private const val ITEMS_PER_PAGE = 36
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_VISIBILITY_FILTER = "visibility_filter"
        private const val ACTION_SORT = "sort"
    }
}
