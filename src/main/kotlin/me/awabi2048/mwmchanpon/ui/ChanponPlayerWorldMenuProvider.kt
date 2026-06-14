package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWarpReason
import me.awabi2048.myworldmanager.api.extension.PlayerWorldMenuProvider
import me.awabi2048.myworldmanager.api.extension.PlayerWorldMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.util.ItemTag
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
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin

class ChanponPlayerWorldMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val globalStorage: ChanponGlobalStorage,
    private val storage: ChanponWorldStorage
) : PlayerWorldMenuProvider, Listener {
    private val legacy = LegacyComponentSerializer.legacySection()

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, request: PlayerWorldMenuRequest): Boolean {
        val mwm = mwm()
        if (mwm.playerPlatformResolver.isBedrock(player)) return false
        if (globalStorage.isArchiveLocked() && !player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(i18n(player, "message.archive_locked"))
            return true
        }

        val session = mwm.playerWorldSessionManager.getSession(player.uniqueId)
        session.showBackButton = request.showBackButton
        session.currentPage = request.page

        val worlds = mwm.playerWorldGui.getPlayerWorlds(player)
        val totalPages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val page = request.page.coerceIn(0, totalPages - 1)
        val pageWorlds = worlds.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)
        val neededDataRows = if (pageWorlds.isEmpty()) 1 else (pageWorlds.size + WORLDS_PER_ROW - 1) / WORLDS_PER_ROW
        val rowCount = (neededDataRows + 2).coerceIn(3, 6)
        val footerStart = (rowCount - 1) * 9
        val title = inventoryTitle(mwm.languageManager.getMessage(player, "gui.player_world.title"))
        val holder = Holder(page, request.showBackButton)
        val inventory = Bukkit.createInventory(holder, rowCount * 9, title)
        holder.inv = inventory

        mwm.settingsSessionManager.updateSessionAction(
            player,
            player.uniqueId,
            SettingsAction.PLAYER_WORLD_GUI,
            true,
            null,
            null,
            null
        )

        applyFrame(inventory, neededDataRows, footerStart)
        pageWorlds.forEachIndexed { index, worldData ->
            val row = index / WORLDS_PER_ROW
            val col = index % WORLDS_PER_ROW
            inventory.setItem((row + 1) * 9 + 1 + col, createWorldItem(player, worldData))
        }

        val currentCreateCount = worlds.count { it.owner == player.uniqueId }
        val configuredMaxCreateCount = mwm.config.getInt("creation.max_create_count_default", 3)
        val maxCreateCount = MyWorldManagerApi.getWorldRuntimePolicy().getMaxCreateCountDefault(configuredMaxCreateCount)
        if (canCreateWorld(player) && (canBypassWorldLimits(player) || currentCreateCount < maxCreateCount)) {
            inventory.setItem(footerStart + 2, createCreationButton(player))
        }
        inventory.setItem(footerStart + 4, createPlayerInfoItem(player))
        inventory.setItem(footerStart + 6, createUserSettingsButton(player))

        if (page > 0) {
            inventory.setItem(footerStart + 1, createPageItem(player, page - 1, false))
        }
        if (request.showBackButton) {
            inventory.setItem(footerStart, createReturnItem(player))
        }
        if ((page + 1) * ITEMS_PER_PAGE < worlds.size) {
            inventory.setItem(footerStart + 8, createPageItem(player, page + 1, true))
        }

        mwm.soundManager.playMenuOpenSound(player, "player_world")
        player.openInventory(inventory)
        clearGuiTransitionLater(player)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.cancelWithDebug("ChanponPlayerWorldMenuProvider.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        val mwm = mwm()
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_NAV_NEXT, ItemTag.TYPE_GUI_NAV_PREV -> {
                val target = ItemTag.getTargetPage(item) ?: return
                mwm.soundManager.playClickSound(player, item, "player_world")
                open(player, PlayerWorldMenuRequest(target, holder.showBackButton))
            }
            ItemTag.TYPE_GUI_RETURN -> handleReturn(player, item)
            ItemTag.TYPE_GUI_CREATION_BUTTON -> {
                if (!canCreateWorld(player)) {
                    player.sendMessage(i18n(player, "message.world_creation_no_permission"))
                    return
                }
                mwm.soundManager.playClickSound(player, item, "player_world")
                val session = mwm.creationSessionManager.startSession(player.uniqueId)
                session.isDialogMode = true
                player.closeInventory()
                player.sendMessage(mwm.languageManager.getMessage(player, "messages.wizard_start"))
                mwm.creationGui.openTypeSelection(player)
            }
            ItemTag.TYPE_GUI_USER_SETTINGS_BUTTON -> {
                mwm.soundManager.playClickSound(player, item, "player_world")
                mwm.menuRouteHistory.pushPlayerWorld(player, holder.page, holder.showBackButton)
                mwm.userSettingsGui.open(player, true)
            }
            ItemTag.TYPE_GUI_WORLD_ITEM -> handleWorldClick(player, holder, item, event)
        }
    }

    private fun handleWorldClick(player: Player, holder: Holder, item: ItemStack, event: InventoryClickEvent) {
        val mwm = mwm()
        val uuid = ItemTag.getWorldUuid(item) ?: return
        val worldData = mwm.worldConfigRepository.findByUuid(uuid) ?: return
        if (event.isShiftClick && event.isLeftClick) {
            val stats = mwm.playerStatsRepository.findByUuid(player.uniqueId)
            stats.worldDisplayOrder.remove(uuid)
            stats.worldDisplayOrder.add(0, uuid)
            mwm.playerStatsRepository.save(stats)
            mwm.soundManager.playClickSound(player, item, "player_world")
            open(player, PlayerWorldMenuRequest(holder.page, holder.showBackButton))
            return
        }
        if (event.isLeftClick && !event.isShiftClick) {
            if (worldData.isArchived) return
            player.closeInventory()
            mwm.worldService.teleportToWorld(player, uuid, null, true, MwmWarpReason.DIRECT, null)
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.warp_success", mapOf("world" to worldData.name)))
            mwm.soundManager.playClickSound(player, item, "player_world")
            return
        }
        if (event.isRightClick && !event.isShiftClick && canOpenSettings(player, worldData)) {
            mwm.menuRouteHistory.pushPlayerWorld(player, holder.page, holder.showBackButton)
            mwm.worldSettingsGui.open(
                player,
                worldData,
                true,
                true,
                holder.showBackButton
            )
        }
    }

    private fun applyFrame(inventory: Inventory, neededDataRows: Int, footerStart: Int) {
        val blackPane = decoration(Material.BLACK_STAINED_GLASS_PANE)
        val grayPane = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0..8) inventory.setItem(slot, blackPane)
        for (row in 0 until neededDataRows) {
            val rowStart = (row + 1) * 9
            inventory.setItem(rowStart, grayPane)
            inventory.setItem(rowStart + 8, grayPane)
            for (col in 1..7) inventory.setItem(rowStart + col, grayPane)
        }
        for (slot in footerStart until footerStart + 9) inventory.setItem(slot, blackPane)
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
            val description = if (world.description.isNotBlank()) {
                lang.getMessage(player, "gui.common.world_desc", mapOf("description" to world.description))
            } else {
                ""
            }
            val ownerName = Bukkit.getOfflinePlayer(world.owner).name ?: lang.getMessage(player, "general.unknown")
            val ownerLine = lang.getMessage(player, "gui.player_world.world_item.owner", mapOf("owner" to ownerName))
            val publishLine = lang.getMessage(
                player,
                "gui.player_world.world_item.publish",
                mapOf("level" to visibilityName(player, storage.getVisibility(world.uuid)), "status_color" to "")
            )
            val progressLine = SubmissionProgressDisplay.line(player, storage, world.uuid)
            val tagLine = if (world.tags.isNotEmpty()) {
                val tagNames = world.tags.joinToString(", ") { mwm.worldTagManager.getDisplayName(player, it) }
                lang.getMessage(player, "gui.player_world.world_item.tag", mapOf("tags" to tagNames))
            } else {
                ""
            }
            val expiresAtLine = if (world.isArchived) {
                lang.getMessage(player, "gui.player_world.world_item.expired")
            } else {
                ""
            }
            val lines = lang.getMessageList(
                player,
                "gui.player_world.world_item.lore",
                mapOf(
                    "description" to description,
                    "owner_line" to ownerLine,
                    "publish_line" to publishLine,
                    "favorite_line" to progressLine,
                    "visitor_line" to "",
                    "tag_line" to tagLine,
                    "expires_at_line" to "",
                    "expired_line" to expiresAtLine,
                    "warp_action" to lang.getMessage(player, "gui.player_world.world_item.warp"),
                    "settings_action" to lang.getMessage(player, "gui.player_world.world_item.settings")
                )
            ).filterMeaningful()
            meta.lore(CCSystem.getAPI().buildLore(lines))
            if (world.isArchived) meta.setEnchantmentGlintOverride(true)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun createCreationButton(player: Player): ItemStack {
        val mwm = mwm()
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(mwm.languageManager.getComponent(player, "gui.player_world.creation_button.display"))
            meta.lore(mwm.languageManager.getComponentList(player, "gui.player_world.creation_button.lore"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_CREATION_BUTTON)
        return item
    }

    private fun createUserSettingsButton(player: Player): ItemStack {
        val mwm = mwm()
        val item = ItemStack(Material.WRITABLE_BOOK)
        item.editMeta { meta ->
            meta.displayName(mwm.languageManager.getComponent(player, "gui.user_settings.button.display"))
            meta.lore(mwm.languageManager.getComponentList(player, "gui.user_settings.button.lore"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_USER_SETTINGS_BUTTON)
        return item
    }

    private fun createPlayerInfoItem(player: Player): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { rawMeta ->
            val meta = rawMeta as? SkullMeta ?: return@editMeta
            meta.owningPlayer = player
            meta.displayName(Component.text(player.name).decoration(TextDecoration.ITALIC, false))
            meta.lore(emptyList())
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun createPageItem(player: Player, targetPage: Int, next: Boolean): ItemStack {
        val mwm = mwm()
        val item = ItemStack(Material.ARROW)
        item.editMeta { meta ->
            val key = if (next) "gui.common.next_page" else "gui.common.prev_page"
            meta.displayName(legacy.deserialize(mwm.languageManager.getMessage(player, key)).decoration(TextDecoration.ITALIC, false))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.setTargetPage(item, targetPage)
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
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
        mwm.soundManager.playClickSound(player, item, "player_world")
        if (!mwm.menuRouteHistory.openPrevious(player)) {
            player.closeInventory()
        }
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

    private fun visibilityName(player: Player, visibility: ChanponWorldVisibility): String {
        val key = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> "chanpon.publish.public"
            ChanponWorldVisibility.DEVELOPMENT -> "chanpon.publish.development"
            ChanponWorldVisibility.PRIVATE -> "chanpon.publish.private"
        }
        return plugin.getMessage(player, key)
    }

    private fun canOpenSettings(player: Player, world: WorldData): Boolean =
        world.owner == player.uniqueId || world.moderators.contains(player.uniqueId) || world.members.contains(player.uniqueId)

    private fun canBypassWorldLimits(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin.bypass_world_limits") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("craftercrossing.test")

    private fun canCreateWorld(player: Player): Boolean =
        globalStorage.isWorldCreationEnabled() &&
            (player.hasPermission("myworldmanager.command.mwm.create") ||
                player.hasPermission("myworldmanager.admin") ||
                player.hasPermission("craftercrossing.test"))

    private fun i18n(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")

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

    private class Holder(val page: Int, val showBackButton: Boolean) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.player_world_menu"
        private const val WORLDS_PER_ROW = 7
        private const val ITEMS_PER_PAGE = 28
    }
}
