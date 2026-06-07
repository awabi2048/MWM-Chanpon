package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Random
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmFavoriteAddSource
import me.awabi2048.myworldmanager.api.event.MwmWarpReason
import me.awabi2048.myworldmanager.api.event.MwmWorldFavoritedEvent
import me.awabi2048.myworldmanager.api.extension.DiscoveryMenuProvider
import me.awabi2048.myworldmanager.api.extension.DiscoveryMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.DiscoverySort
import me.awabi2048.myworldmanager.session.PreviewSessionManager
import me.awabi2048.myworldmanager.session.PreviewSource
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ChanponDiscoveryMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) : DiscoveryMenuProvider, Listener {
    private val legacy = LegacyComponentSerializer.legacySection()

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, request: DiscoveryMenuRequest): Boolean {
        val mwm = mwm()
        if (mwm.playerPlatformResolver.isBedrock(player)) return false

        val session = mwm.discoverySessionManager.getSession(player.uniqueId)
        session.showBackButton = request.showBackButton
        if (session.sort !in ALLOWED_SORTS) {
            session.sort = DiscoverySort.NEW
        }

        val selectedTag = session.selectedTag
        val allWorlds = mwm.worldConfigRepository.findAll()
            .filter { MyWorldManagerApi.getWorldAccessPolicy().canShowInDiscovery(player, it) }
            .filter { selectedTag == null || it.tags.contains(selectedTag) }
        val sortedWorlds = sortWorlds(mwm, allWorlds, session.sort)
        val title = inventoryTitle(mwm.languageManager.getMessage(player, "gui.discovery.title"))
        val holder = Holder(request.showBackButton)
        val inventory = Bukkit.createInventory(holder, 54, title)
        holder.inv = inventory

        applyFrame(inventory)
        val pageWorlds = sortedWorlds.take(WORLD_ITEM_SLOTS.size)
        if (pageWorlds.isEmpty()) {
            inventory.setItem(31, createNoResultItem(player, session.sort))
        } else {
            pageWorlds.forEachIndexed { index, worldData ->
                inventory.setItem(WORLD_ITEM_SLOTS[index], createWorldItem(player, worldData))
            }
        }
        inventory.setItem(48, createSortButton(player, session.sort))
        inventory.setItem(49, createStatsItem(player, session.sort, selectedTag, sortedWorlds.size))
        inventory.setItem(50, createTagFilterButton(player, selectedTag))
        if (request.showBackButton) {
            inventory.setItem(45, createReturnItem(player))
        }

        mwm.soundManager.playMenuOpenSound(player, "discovery")
        player.openInventory(inventory)
        clearGuiTransitionLater(player)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        val mwm = mwm()
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_WORLD_ITEM -> handleWorldClick(player, item, event)
            "discovery_spotlight_empty" -> handleSpotlightEmptyClick(player, item, event)
            ItemTag.TYPE_GUI_DISCOVERY_TAG -> {
                val session = mwm.discoverySessionManager.getSession(player.uniqueId)
                val tags = mwm.worldTagManager.getEnabledTagIds()
                if (event.isLeftClick) {
                    val currentIndex = if (session.selectedTag == null) -1 else tags.indexOf(session.selectedTag)
                    val nextIndex = (currentIndex + 1) % (tags.size + 1)
                    session.selectedTag = if (nextIndex == tags.size) null else tags[nextIndex]
                } else if (event.isRightClick) {
                    session.selectedTag = null
                }
                mwm.soundManager.playClickSound(player, item, "discovery")
                open(player, DiscoveryMenuRequest(0, holder.showBackButton))
            }
            ItemTag.TYPE_GUI_DISCOVERY_SORT -> {
                val session = mwm.discoverySessionManager.getSession(player.uniqueId)
                if (event.isShiftClick &&
                    event.isLeftClick &&
                    session.sort == DiscoverySort.SPOTLIGHT &&
                    canManageSpotlight(player)
                ) {
                    mwm.soundManager.playClickSound(player, item, "discovery")
                    openSpotlightDescriptionDialog(player)
                    return
                }

                val forward = event.isLeftClick
                session.sort = nextSort(session.sort, forward)
                mwm.soundManager.playClickSound(player, item, "discovery")
                open(player, DiscoveryMenuRequest(0, holder.showBackButton))
            }
            ItemTag.TYPE_GUI_RETURN -> handleReturn(player, item)
        }
    }

    private fun handleWorldClick(player: Player, item: ItemStack, event: InventoryClickEvent) {
        val mwm = mwm()
        val uuid = ItemTag.getWorldUuid(item) ?: return
        val worldData = mwm.worldConfigRepository.findByUuid(uuid) ?: return
        val currentWorldData = mwm.worldConfigRepository.findByWorldName(player.world.name)
        val isCurrentWorld = currentWorldData?.uuid == worldData.uuid
        val isMember = worldData.owner == player.uniqueId ||
            worldData.moderators.contains(player.uniqueId) ||
            worldData.members.contains(player.uniqueId)

        if (event.isLeftClick) {
            if (!event.isShiftClick && isCurrentWorld) return
            if (!MyWorldManagerApi.getWorldAccessPolicy().canUseVisitEntry(player, worldData, isMember)) {
                player.sendMessage(mwm.languageManager.getMessage(player, "error.world_not_public"))
                mwm.soundManager.playActionSound(player, "discovery", "access_denied")
                player.closeInventory()
                return
            }
            if (event.isShiftClick) {
                if (isMember) {
                    player.sendMessage(mwm.languageManager.getMessage(player, "error.member_request_already_member"))
                    return
                }
                mwm.soundManager.playClickSound(player, item, "discovery")
                mwm.memberRequestConfirmGui.open(player, worldData)
                return
            }
            mwm.soundManager.playClickSound(player, item, "discovery")
            player.closeInventory()
            mwm.worldService.teleportToWorld(player, uuid, null, true, MwmWarpReason.DIRECT, null)
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.warp_success", mapOf("world" to worldData.name)))
            return
        }

        if (event.isRightClick && event.isShiftClick) {
            if (mwm.discoverySessionManager.getSession(player.uniqueId).sort == DiscoverySort.SPOTLIGHT && canManageSpotlight(player)) {
                mwm.soundManager.playClickSound(player, item, "discovery")
                mwm.spotlightRemoveConfirmGui.open(player, worldData)
                return
            }
            toggleFavorite(player, worldData, isMember)
            return
        }

        if (event.isRightClick && !event.isShiftClick && !isCurrentWorld) {
            player.closeInventory()
            mwm.previewSessionManager.startPreview(
                player,
                PreviewSessionManager.PreviewTarget.World(worldData),
                PreviewSource.DISCOVERY_MENU
            )
        }
    }

    private fun toggleFavorite(player: Player, worldData: WorldData, isMember: Boolean) {
        val mwm = mwm()
        if (isMember) return

        val stats = mwm.playerStatsRepository.findByUuid(player.uniqueId)
        val uuid = worldData.uuid
        var favoriteAdded = false
        if (stats.favoriteWorlds.containsKey(uuid)) {
            stats.favoriteWorlds.remove(uuid)
            worldData.favorite = (worldData.favorite - 1).coerceAtLeast(0)
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.favorite_removed"))
            mwm.soundManager.playActionSound(player, "discovery", "favorite_remove")
        } else {
            val maxFav = mwm.config.getInt("favorite.max_count", 1000)
            if (stats.favoriteWorlds.size >= maxFav) {
                player.sendMessage(mwm.languageManager.getMessage(player, "error.favorite_limit_reached", mapOf("max" to maxFav)))
                return
            }
            stats.favoriteWorlds[uuid] = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            worldData.favorite++
            favoriteAdded = true
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.favorite_added"))
            mwm.soundManager.playActionSound(player, "discovery", "favorite_add")
        }
        mwm.playerStatsRepository.save(stats)
        mwm.worldConfigRepository.save(worldData)
        if (favoriteAdded) {
            Bukkit.getPluginManager().callEvent(
                MwmWorldFavoritedEvent(
                    worldData.uuid,
                    worldData.name,
                    player.uniqueId,
                    player.name,
                    MwmFavoriteAddSource.DISCOVERY_MENU
                )
            )
        }
        open(player, DiscoveryMenuRequest(0, mwm.discoverySessionManager.getSession(player.uniqueId).showBackButton))
    }

    private fun handleSpotlightEmptyClick(player: Player, item: ItemStack, event: InventoryClickEvent) {
        if (!event.isLeftClick || !canManageSpotlight(player)) return
        val mwm = mwm()
        val worldData = currentManagedWorld(player) ?: run {
            player.sendMessage(mwm.languageManager.getMessage(player, "error.spotlight_not_in_myworld"))
            return
        }
        mwm.soundManager.playClickSound(player, item, "discovery")
        mwm.spotlightConfirmGui.open(player, worldData)
    }

    private fun sortWorlds(mwm: MyWorldManager, worlds: List<WorldData>, sort: DiscoverySort): List<WorldData> =
        when (sort) {
            DiscoverySort.SPOTLIGHT -> mwm.spotlightRepository.findAll()
                .mapNotNull { mwm.worldConfigRepository.findByUuid(it) }
                .filter { world -> worlds.any { it.uuid == world.uuid } }
            DiscoverySort.RANDOM -> worlds.sortedBy { it.uuid.toString() }.shuffled(Random(LocalDate.now().toEpochDay()))
            else -> worlds.sortedByDescending { it.createdAt }
        }

    private fun applyFrame(inventory: Inventory) {
        val grayPane = decoration(Material.GRAY_STAINED_GLASS_PANE)
        val blackPane = decoration(Material.BLACK_STAINED_GLASS_PANE)
        val whitePane = decoration(Material.WHITE_STAINED_GLASS_PANE)
        for (slot in 0 until 54) inventory.setItem(slot, grayPane)
        for (slot in 0..8) inventory.setItem(slot, blackPane)
        for (slot in 45..53) inventory.setItem(slot, blackPane)
        WORLD_ITEM_SLOTS.forEach { inventory.setItem(it, whitePane) }
    }

    private fun createWorldItem(player: Player, world: WorldData): ItemStack {
        val mwm = mwm()
        val lang = mwm.languageManager
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(
                lang.getComponent(player, "gui.common.world_item_name", mapOf("world" to world.name))
                    .decoration(TextDecoration.ITALIC, false)
            )
            val ownerName = Bukkit.getOfflinePlayer(world.owner).name ?: lang.getMessage(player, "general.unknown")
            val description = if (world.description.isNotBlank()) {
                lang.getMessage(player, "gui.common.world_desc", mapOf("description" to world.description))
            } else {
                ""
            }
            val tagLine = if (world.tags.isNotEmpty()) {
                val tags = world.tags.joinToString(", ") { mwm.worldTagManager.getDisplayName(player, it) }
                lang.getMessage(player, "gui.discovery.world_item.tag", mapOf("tags" to tags))
            } else {
                ""
            }
            val currentWorldData = mwm.worldConfigRepository.findByWorldName(player.world.name)
            val isCurrentWorld = currentWorldData?.uuid == world.uuid
            val lines = listOf(
                description,
                lang.getMessage(player, "gui.player_world.world_item.owner", mapOf("owner" to ownerName)),
                SubmissionProgressDisplay.line(player, storage, world.uuid),
                tagLine,
                if (isCurrentWorld) "" else lang.getMessage(player, "gui.discovery.world_item.warp_hint"),
                if (isCurrentWorld) "" else lang.getMessage(player, "gui.discovery.world_item.preview_hint"),
                lang.getMessage(player, "gui.discovery.world_item.member_request_hint")
            ).filterMeaningful()
            meta.lore(CCSystem.getAPI().buildLore(lines))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun createSortButton(player: Player, currentSort: DiscoverySort): ItemStack {
        val mwm = mwm()
        val lang = mwm.languageManager
        val item = ItemStack(mwm.menuConfigManager.getIconMaterial("discovery", "sort", Material.HOPPER))
        item.editMeta { meta ->
            val currentPrefix = lang.getMessage(player, "gui.discovery.tag_filter.current_prefix")
            val sortName = lang.getMessage(player, "gui.discovery.sort.type.${currentSort.name.lowercase()}")
            val sortList = ALLOWED_SORTS.joinToString("\n") { sort ->
                val prefix = if (sort == currentSort) "§6» " else "§7  "
                val color = if (sort == currentSort) "§e" else "§7"
                "$prefix$color${lang.getMessage(player, "gui.discovery.sort.type.${sort.name.lowercase()}")}"
            }
            meta.displayName(lang.getComponent(player, "gui.discovery.sort.display"))
            meta.lore(
                lang.getComponentList(
                    player,
                    "gui.discovery.sort.lore",
                    mapOf(
                        "current_prefix" to currentPrefix,
                        "sort_name" to sortName,
                        "sort_desc" to lang.getMessage(player, "gui.discovery.sort_info.${currentSort.name.lowercase()}"),
                        "sort_list" to sortList
                    )
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DISCOVERY_SORT)
        return item
    }

    private fun createTagFilterButton(player: Player, selectedTag: String?): ItemStack {
        val mwm = mwm()
        val lang = mwm.languageManager
        val item = ItemStack(mwm.menuConfigManager.getIconMaterial("discovery", "tag_filter", Material.NAME_TAG))
        item.editMeta { meta ->
            val tagName = selectedTag?.let { mwm.worldTagManager.getDisplayName(player, it) }
                ?: lang.getMessage(player, "gui.discovery.tag_filter.no_selection")
            val tagList = mwm.worldTagManager.getEnabledTagIds().joinToString("\n") { tagId ->
                val prefix = if (tagId == selectedTag) lang.getMessage(player, "gui.discovery.tag_filter.active") else lang.getMessage(player, "gui.discovery.tag_filter.inactive")
                val color = if (tagId == selectedTag) "§e" else "§7"
                "$prefix$color${mwm.worldTagManager.getDisplayName(player, tagId)}"
            }
            meta.displayName(lang.getComponent(player, "gui.discovery.tag_filter.name"))
            meta.lore(
                lang.getComponentList(
                    player,
                    "gui.discovery.tag_filter.lore",
                    mapOf(
                        "tag" to tagName,
                        "tag_list" to tagList,
                        "click_left" to lang.getMessage(player, "gui.discovery.tag_filter.click_left"),
                        "click_right" to lang.getMessage(player, "gui.discovery.tag_filter.click_right")
                    )
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DISCOVERY_TAG)
        return item
    }

    private fun createStatsItem(player: Player, sort: DiscoverySort, tag: String?, count: Int): ItemStack {
        val mwm = mwm()
        val lang = mwm.languageManager
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(lang.getComponent(player, "gui.discovery.stats.name"))
            meta.lore(
                lang.getComponentList(
                    player,
                    "gui.discovery.stats.lore",
                    mapOf(
                        "sort" to lang.getMessage(player, "gui.discovery.sort.type.${sort.name.lowercase()}"),
                        "tag" to (tag?.let { mwm.worldTagManager.getDisplayName(player, it) }
                            ?: lang.getMessage(player, "gui.discovery.tag_filter.all")),
                        "count" to count,
                        "desc" to lang.getMessage(player, "gui.discovery.stats.desc")
                    )
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun createNoResultItem(player: Player, sort: DiscoverySort): ItemStack {
        val mwm = mwm()
        val item = ItemStack(if (sort == DiscoverySort.SPOTLIGHT) Material.GLASS_PANE else Material.GRAY_DYE)
        item.editMeta { meta ->
            if (sort == DiscoverySort.SPOTLIGHT) {
                meta.displayName(mwm.languageManager.getComponent(player, "gui.discovery.spotlight_empty.name"))
            } else {
                meta.displayName(mwm.languageManager.getComponent(player, "gui.discovery.no_result"))
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        if (sort == DiscoverySort.SPOTLIGHT) {
            ItemTag.tagItem(item, "discovery_spotlight_empty")
        }
        return item
    }

    private fun createReturnItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(mwm().languageManager.getComponent(player, "gui.common.return"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun handleReturn(player: Player, item: ItemStack) {
        val mwm = mwm()
        mwm.soundManager.playClickSound(player, item, "discovery")
        val command = mwm.config.getString("menu_command", "mwm")?.removePrefix("/") ?: "mwm"
        player.closeInventory()
        player.performCommand(command)
    }

    private fun decoration(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun inventoryTitle(title: String): Component =
        Component.text(title, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)

    private fun nextSort(current: DiscoverySort, forward: Boolean): DiscoverySort {
        val currentIndex = ALLOWED_SORTS.indexOf(current).let { if (it == -1) 0 else it }
        val delta = if (forward) 1 else ALLOWED_SORTS.size - 1
        return ALLOWED_SORTS[(currentIndex + delta) % ALLOWED_SORTS.size]
    }

    private fun currentManagedWorld(player: Player): WorldData? {
        val mwm = mwm()
        val worldName = player.world.name
        if (worldName.startsWith("my_world.")) {
            val uuid = runCatching { java.util.UUID.fromString(worldName.removePrefix("my_world.")) }.getOrNull()
            if (uuid != null) {
                mwm.worldConfigRepository.findByUuid(uuid)?.let { return it }
            }
        }
        return mwm.worldConfigRepository.findByWorldName(worldName)
    }

    private fun canManageSpotlight(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin")

    @Suppress("UnstableApiUsage")
    private fun openSpotlightDescriptionDialog(player: Player) {
        val mwm = mwm()
        val lang = mwm.languageManager
        val currentText = mwm.spotlightRepository.getDescription().orEmpty()
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(
                        Component.text(
                            lang.getMessage(player, "gui.discovery.spotlight_description_dialog.title"),
                            NamedTextColor.YELLOW
                        )
                    )
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    Component.text(
                                        lang.getMessage(
                                            player,
                                            "gui.discovery.spotlight_description_dialog.body",
                                            mapOf("max" to SPOTLIGHT_DESCRIPTION_MAX_LENGTH)
                                        )
                                    )
                                )
                            )
                        )
                        .inputs(
                            listOf(
                                DialogInput.text(
                                    "spotlight_description",
                                    Component.text(
                                        lang.getMessage(
                                            player,
                                            "gui.discovery.spotlight_description_dialog.input_label"
                                        )
                                    )
                                )
                                    .maxLength(SPOTLIGHT_DESCRIPTION_MAX_LENGTH)
                                    .initial(currentText)
                                    .build()
                            )
                        )
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        ActionButton.create(
                            Component.text(lang.getMessage(player, "gui.common.confirm"), NamedTextColor.GREEN),
                            null,
                            100,
                            DialogAction.customClick(Key.key("mwm:discovery/spotlight_description_submit"), null)
                        ),
                        ActionButton.create(
                            Component.text(lang.getMessage(player, "gui.common.cancel"), NamedTextColor.RED),
                            null,
                            200,
                            DialogAction.customClick(Key.key("mwm:discovery/spotlight_description_cancel"), null)
                        )
                    )
                )
        }
        player.showDialog(dialog)
    }

    private fun clearGuiTransitionLater(player: Player) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val session = mwm().settingsSessionManager.getSession(player)
            if (session != null && session.isGuiTransition) {
                session.isGuiTransition = false
            }
        }, 5L)
    }

    private fun List<String>.filterMeaningful(): List<String> =
        filter { line ->
            val stripped = line.replace(Regex("[§&][0-9A-FK-ORa-fk-or]"), "").trim()
            stripped.isNotBlank() && !stripped.all { it == '─' || it == '━' || it == '-' }
        }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private class Holder(val showBackButton: Boolean) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.discovery_menu"
        private const val SPOTLIGHT_DESCRIPTION_MAX_LENGTH = 100
        private val WORLD_ITEM_SLOTS = listOf(21, 22, 23, 28, 29, 30, 31, 32, 33, 34)
        private val ALLOWED_SORTS = listOf(DiscoverySort.NEW, DiscoverySort.SPOTLIGHT, DiscoverySort.RANDOM)
    }
}
