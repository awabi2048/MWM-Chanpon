package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWarpReason
import me.awabi2048.myworldmanager.api.extension.FavoriteListMenuProvider
import me.awabi2048.myworldmanager.api.extension.FavoriteListMenuRequest
import me.awabi2048.myworldmanager.api.extension.FavoriteMenuProvider
import me.awabi2048.myworldmanager.api.extension.VisitMenuProvider
import me.awabi2048.myworldmanager.api.extension.VisitMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.time.LocalDate
import java.util.UUID

class ChanponFavoriteVisitMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) : FavoriteListMenuProvider, FavoriteMenuProvider, VisitMenuProvider, Listener {
    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, request: FavoriteListMenuRequest): Boolean {
        val mwm = mwm()
        val stats = mwm.playerStatsRepository.findByUuid(player.uniqueId)
        val worlds = stats.favoriteWorlds.keys
            .mapNotNull { mwm.worldConfigRepository.findByUuid(it) }
            .filter { !it.isArchived }
        openWorldList(player, ListMode.FAVORITE, worlds, request.page, request.worldData, null, request.showBackButton)
        return true
    }

    override fun open(player: Player, worldData: WorldData?): Boolean {
        val holder = FavoriteMenuHolder(worldData?.uuid)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "favorite_menu.title"))
        holder.inv = inventory
        applyFrame(inventory)

        if (worldData != null && worldData.owner != player.uniqueId) {
            inventory.setItem(20, favoriteControlItem(player, "other_worlds", Material.COMPASS, ACTION_OTHER_WORLDS, worldData.uuid))
            inventory.setItem(22, favoriteToggleItem(player, worldData))
            inventory.setItem(24, favoriteControlItem(player, "list", Material.BOOK, ACTION_FAVORITE_LIST, worldData.uuid))
            inventory.setItem(40, worldInfoItem(player, worldData))
        } else {
            inventory.setItem(22, favoriteControlItem(player, "list", Material.BOOK, ACTION_FAVORITE_LIST, worldData?.uuid))
            if (worldData != null) inventory.setItem(40, worldInfoItem(player, worldData))
        }

        fill(inventory)
        player.openInventory(inventory)
        return true
    }

    override fun open(player: Player, owner: OfflinePlayer, request: VisitMenuRequest): Boolean {
        val worlds = mwm().worldConfigRepository.findAll().filter { world ->
            if (world.owner != owner.uniqueId || world.isArchived) return@filter false
            val isMember = isMember(player, world)
            MyWorldManagerApi.getWorldAccessPolicy().canUseVisitEntry(player, world, isMember)
        }
        openWorldList(player, ListMode.VISIT, worlds, request.page, request.worldData, owner, request.worldData != null)
        return true
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        when (val holder = event.view.topInventory.holder) {
            is WorldListHolder -> handleWorldListClick(event, player, holder)
            is FavoriteMenuHolder -> handleFavoriteMenuClick(event, player, holder)
        }
    }

    private fun handleWorldListClick(event: InventoryClickEvent, player: Player, holder: WorldListHolder) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> {
                val page = ItemTag.getTargetPage(item) ?: return
                val returnWorld = holder.returnWorldUuid?.let { mwm().worldConfigRepository.findByUuid(it) }
                val owner = holder.ownerUuid?.let { Bukkit.getOfflinePlayer(it) }
                val worlds = when (holder.mode) {
                    ListMode.FAVORITE -> favoriteWorlds(player)
                    ListMode.VISIT -> owner?.let { visitableWorlds(player, it.uniqueId) } ?: emptyList()
                }
                openWorldList(player, holder.mode, worlds, page, returnWorld, owner, holder.showBackButton)
            }
            ItemTag.TYPE_GUI_RETURN -> returnToWorld(player, holder.returnWorldUuid)
            ItemTag.TYPE_GUI_WORLD_ITEM -> handleWorldItemClick(event, player, item)
        }
    }

    private fun handleFavoriteMenuClick(event: InventoryClickEvent, player: Player, holder: FavoriteMenuHolder) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        val worldData = holder.worldUuid?.let { mwm().worldConfigRepository.findByUuid(it) }
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_OTHER_WORLDS -> worldData?.let {
                val owner = Bukkit.getOfflinePlayer(it.owner)
                open(player, owner, VisitMenuRequest(0, it))
            }
            ACTION_FAVORITE_LIST -> open(player, FavoriteListMenuRequest(0, worldData, true, true))
            ACTION_TOGGLE_FAVORITE -> worldData?.let {
                toggleFavorite(player, it)
                open(player, it)
            }
        }
    }

    private fun handleWorldItemClick(event: InventoryClickEvent, player: Player, item: ItemStack) {
        val uuid = ItemTag.getWorldUuid(item) ?: return
        val worldData = mwm().worldConfigRepository.findByUuid(uuid) ?: return
        if (event.isLeftClick && !event.isShiftClick) {
            player.closeInventory()
            mwm().worldService.teleportToWorld(player, uuid, null, true, MwmWarpReason.DIRECT, null)
            return
        }
        if (event.isRightClick && !event.isShiftClick && !isMember(player, worldData)) {
            toggleFavorite(player, worldData)
            val holder = event.view.topInventory.holder as? WorldListHolder ?: return
            val worlds = when (holder.mode) {
                ListMode.FAVORITE -> favoriteWorlds(player)
                ListMode.VISIT -> holder.ownerUuid?.let { visitableWorlds(player, it) } ?: emptyList()
            }
            openWorldList(
                player,
                holder.mode,
                worlds,
                holder.page,
                holder.returnWorldUuid?.let { mwm().worldConfigRepository.findByUuid(it) },
                holder.ownerUuid?.let { Bukkit.getOfflinePlayer(it) },
                holder.showBackButton
            )
        }
    }

    private fun openWorldList(
        player: Player,
        mode: ListMode,
        worlds: List<WorldData>,
        page: Int,
        returnWorld: WorldData?,
        owner: OfflinePlayer?,
        showBackButton: Boolean
    ) {
        val pages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, pages - 1)
        val holder = WorldListHolder(mode, safePage, returnWorld?.uuid, owner?.uniqueId, showBackButton)
        val inventory = Bukkit.createInventory(holder, 54, i18nComponent(player, if (mode == ListMode.FAVORITE) "favorite_list.title" else "visit_list.title"))
        holder.inv = inventory
        applyFrame(inventory)
        worlds.drop(safePage * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE).forEachIndexed { index, world ->
            inventory.setItem(WORLD_SLOTS[index], worldListItem(player, world))
        }
        if (safePage > 0) inventory.setItem(0, navItem(player, safePage - 1, false))
        if (safePage < pages - 1) inventory.setItem(8, navItem(player, safePage + 1, true))
        if (showBackButton || returnWorld != null) inventory.setItem(45, returnItem(player))
        inventory.setItem(49, infoItem(player, worlds.size, safePage + 1, pages))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun worldListItem(player: Player, world: WorldData): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(mwm().languageManager.getComponent(player, "gui.common.world_item_name", mapOf("world" to world.name)).decoration(TextDecoration.ITALIC, false))
            val lines = mutableListOf<String>()
            if (world.description.isNotBlank()) lines.add(mwm().languageManager.getMessage(player, "gui.common.world_desc", mapOf("description" to world.description)))
            val ownerName = Bukkit.getOfflinePlayer(world.owner).name ?: mwm().languageManager.getMessage(player, "general.unknown")
            lines.add(i18n(player, "favorite_visit.owner", mapOf("owner" to ownerName)))
            lines.add(SubmissionProgressDisplay.line(player, storage, world.uuid))
            if (world.tags.isNotEmpty()) {
                lines.add(i18n(player, "favorite_visit.tags", mapOf("tags" to world.tags.joinToString(", ") { mwm().worldTagManager.getDisplayName(player, it) })))
            }
            lines.add("")
            lines.add(i18n(player, "favorite_visit.warp"))
            if (!isMember(player, world)) lines.add(i18n(player, "favorite_visit.toggle_favorite"))
            meta.lore(CCSystem.getAPI().buildLore(lines.filter { it.isNotBlank() }, closingSeparator = true))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun favoriteWorlds(player: Player): List<WorldData> {
        val stats = mwm().playerStatsRepository.findByUuid(player.uniqueId)
        return stats.favoriteWorlds.keys.mapNotNull { mwm().worldConfigRepository.findByUuid(it) }.filter { !it.isArchived }
    }

    private fun visitableWorlds(player: Player, ownerUuid: UUID): List<WorldData> {
        return mwm().worldConfigRepository.findAll().filter { world ->
            if (world.owner != ownerUuid || world.isArchived) return@filter false
            MyWorldManagerApi.getWorldAccessPolicy().canUseVisitEntry(player, world, isMember(player, world))
        }
    }

    private fun toggleFavorite(player: Player, worldData: WorldData) {
        val stats = mwm().playerStatsRepository.findByUuid(player.uniqueId)
        if (stats.favoriteWorlds.containsKey(worldData.uuid)) {
            stats.favoriteWorlds.remove(worldData.uuid)
            worldData.favorite = (worldData.favorite - 1).coerceAtLeast(0)
            player.sendMessage(mwm().languageManager.getMessage(player, "messages.favorite_removed"))
        } else {
            stats.favoriteWorlds[worldData.uuid] = LocalDate.now().toString()
            worldData.favorite++
            player.sendMessage(mwm().languageManager.getMessage(player, "messages.favorite_added"))
        }
        mwm().playerStatsRepository.save(stats)
        mwm().worldConfigRepository.save(worldData)
    }

    private fun favoriteControlItem(player: Player, key: String, material: Material, action: String, worldUuid: UUID?): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "favorite_menu.$key.display"))
            meta.lore(CCSystem.getAPI().buildLore(i18nList(player, "favorite_menu.$key.lore"), closingSeparator = true))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        if (worldUuid != null) ItemTag.setWorldUuid(item, worldUuid)
        return item
    }

    private fun favoriteToggleItem(player: Player, worldData: WorldData): ItemStack {
        val active = mwm().playerStatsRepository.findByUuid(player.uniqueId).favoriteWorlds.containsKey(worldData.uuid)
        val key = if (active) "remove" else "add"
        return favoriteControlItem(player, "toggle_$key", if (active) Material.RED_DYE else Material.GRAY_DYE, ACTION_TOGGLE_FAVORITE, worldData.uuid)
    }

    private fun worldInfoItem(player: Player, worldData: WorldData): ItemStack {
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(Component.text(worldData.name).decoration(TextDecoration.ITALIC, false))
            meta.lore(CCSystem.getAPI().buildLore(listOf(i18n(player, "favorite_visit.owner", mapOf("owner" to (Bukkit.getOfflinePlayer(worldData.owner).name ?: "-")))), closingSeparator = true))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun returnToWorld(player: Player, worldUuid: UUID?) {
        val worldData = worldUuid?.let { mwm().worldConfigRepository.findByUuid(it) }
        if (worldData != null) {
            mwm().menuEntryRouter.openWorldSettings(player, worldData, true)
        } else {
            mwm().menuEntryRouter.openPlayerWorld(player, 0, false)
        }
    }

    private fun applyFrame(inventory: Inventory) {
        val black = decoration(Material.BLACK_STAINED_GLASS_PANE)
        for (slot in 0..8) inventory.setItem(slot, black)
        for (slot in inventory.size - 9 until inventory.size) inventory.setItem(slot, black)
    }

    private fun fill(inventory: Inventory) {
        val gray = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0 until inventory.size) if (inventory.getItem(slot) == null) inventory.setItem(slot, gray)
    }

    private fun decoration(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun navItem(player: Player, page: Int, next: Boolean): ItemStack {
        val item = ItemStack(Material.ARROW)
        item.editMeta { meta -> meta.displayName(i18nComponent(player, if (next) "common.next" else "common.prev")) }
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        ItemTag.setTargetPage(item, page)
        return item
    }

    private fun returnItem(player: Player) = simpleItem(player, Material.REDSTONE, "common.back", ItemTag.TYPE_GUI_RETURN)

    private fun infoItem(player: Player, count: Int, page: Int, pages: Int): ItemStack {
        val item = ItemStack(Material.PAPER)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "favorite_visit.info.display"))
            meta.lore(CCSystem.getAPI().buildLore(i18nList(player, "favorite_visit.info.lore", mapOf("count" to count, "page" to page, "pages" to pages)), closingSeparator = true))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun simpleItem(player: Player, material: Material, key: String, type: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta -> meta.displayName(i18nComponent(player, key)) }
        ItemTag.tagItem(item, type)
        return item
    }

    private fun isMember(player: Player, worldData: WorldData): Boolean =
        worldData.owner == player.uniqueId || worldData.members.contains(player.uniqueId) || worldData.moderators.contains(player.uniqueId)

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    private class WorldListHolder(
        val mode: ListMode,
        val page: Int,
        val returnWorldUuid: UUID?,
        val ownerUuid: UUID?,
        val showBackButton: Boolean
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class FavoriteMenuHolder(val worldUuid: UUID?) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private enum class ListMode {
        FAVORITE,
        VISIT
    }

    companion object {
        private const val PROVIDER_ID = "mwmchanpon.favorite_visit"
        private const val TYPE_CONTROL = "mwmchanpon_favorite_control"
        private const val ACTION_KEY = "mwmchanpon_action"
        private const val ACTION_OTHER_WORLDS = "other_worlds"
        private const val ACTION_FAVORITE_LIST = "favorite_list"
        private const val ACTION_TOGGLE_FAVORITE = "toggle_favorite"
        private const val ITEMS_PER_PAGE = 28
        private val WORLD_SLOTS = (0 until 4).flatMap { row -> (0 until 7).map { col -> (row + 1) * 9 + 1 + col } }
    }
}
