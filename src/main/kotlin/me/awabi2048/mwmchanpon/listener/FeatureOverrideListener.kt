package me.awabi2048.mwmchanpon.listener

import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.gui.DiscoveryGui
import me.awabi2048.myworldmanager.session.DiscoverySort
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.ui.bedrock.BedrockPlayerWorldListHolder
import me.awabi2048.myworldmanager.ui.bedrock.BedrockWorldActionHolder
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class FeatureOverrideListener(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) : Listener {
    private val legacy = LegacyComponentSerializer.legacySection()
    private val plain = PlainTextComponentSerializer.plainText()

    fun sanitizeOpenInventories() {
        plugin.server.onlinePlayers.forEach { sanitizeOpenInventory(it) }
    }

    @EventHandler
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        sanitizeInventory(event.view.topInventory, player)
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (player.isOnline) sanitizeOpenInventory(player)
        })
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        val type = ItemTag.getType(item)

        if (event.view.topInventory.holder is BedrockWorldActionHolder) {
            val bedrockAction = ItemTag.getString(item, "bedrock_action")
            val uuid = ItemTag.getWorldUuid(item)
            val worldData = uuid?.let { mwm().worldConfigRepository.findByUuid(it) }
            when (bedrockAction) {
                "cycle_publish" -> {
                    if (worldData == null) return
                    event.isCancelled = true
                    cyclePublishLevel(worldData, forward = true)
                    event.view.topInventory.setItem(11, createBedrockPublishItem(worldData, player))
                    return
                }
                "toggle_archive" -> {
                    if (!archiveEnabled()) {
                        event.isCancelled = true
                        event.view.topInventory.setItem(event.slot, decoration(Material.BLACK_STAINED_GLASS_PANE))
                        return
                    }
                }
            }
        }

        if (type == ItemTag.TYPE_GUI_SETTING_PUBLISH) {
            val session = mwm().settingsSessionManager.getSession(player) ?: return
            val worldData = mwm().worldConfigRepository.findByUuid(session.worldUuid) ?: return
            event.isCancelled = true
            val forward = mwm().playerPlatformResolver.isBedrock(player) || event.isRightClick
            cyclePublishLevel(worldData, forward)
            mwm().soundManager.playClickSound(player, item, "world_settings")
            sanitizeInventory(event.view.topInventory, player)
            return
        }

        if (type in hiddenActionTypes()) {
            event.isCancelled = true
            event.clickedInventory?.setItem(event.slot, decoration(Material.GRAY_STAINED_GLASS_PANE))
            return
        }

        if (!archiveEnabled() && event.isShiftClick && event.isRightClick && isAdminWorldList(player)) {
            val uuid = ItemTag.getWorldUuid(item) ?: return
            if (mwm().worldConfigRepository.findByUuid(uuid) != null) {
                event.isCancelled = true
                event.clickedInventory?.setItem(event.slot, sanitizeItem(item, player))
                return
            }
        }

        if (type == ItemTag.TYPE_GUI_DISCOVERY_SORT) {
            event.isCancelled = true
            val session = mwm().discoverySessionManager.getSession(player.uniqueId)
            val forward = mwm().playerPlatformResolver.isBedrock(player) || event.isLeftClick
            session.sort = nextAllowedDiscoverySort(session.sort, forward)
            mwm().soundManager.playClickSound(player, item, "discovery")
            mwm().menuEntryRouter.openDiscovery(player, 0, false)
            sanitizeOpenInventory(player)
        }
    }

    private fun sanitizeOpenInventory(player: Player) {
        sanitizeInventory(player.openInventory.topInventory, player)
    }

    private fun sanitizeInventory(inventory: Inventory, player: Player) {
        val holder = inventory.holder
        val discovery = holder is DiscoveryGui.DiscoveryGuiHolder
        val session = mwm().settingsSessionManager.getSession(player)
        if (!isManagedInventory(inventory, session?.action)) return

        if (discovery) {
            val discoverySession = mwm().discoverySessionManager.getSession(player.uniqueId)
            if (discoverySession.sort == DiscoverySort.HOT || discoverySession.sort == DiscoverySort.FAVORITES) {
                discoverySession.sort = DiscoverySort.NEW
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (player.isOnline) {
                        mwm().menuEntryRouter.openDiscovery(player, 0, false)
                        sanitizeOpenInventory(player)
                    }
                })
                return
            }
            setItemIfPresent(inventory, 48, createDiscoverySortItem(player))
        }

        if (holder is BedrockPlayerWorldListHolder) {
            val footerStart = inventory.size - 9
            setItemIfPresent(inventory, footerStart + 4, decoration(Material.BLACK_STAINED_GLASS_PANE))
        }
        if (holder is BedrockWorldActionHolder) {
            val worldData = mwm().worldConfigRepository.findByUuid(holder.worldUuid)
            if (worldData != null) {
                setItemIfPresent(inventory, 11, createBedrockPublishItem(worldData, player))
            }
            if (!archiveEnabled()) {
                setItemIfPresent(inventory, 12, decoration(Material.BLACK_STAINED_GLASS_PANE))
            }
        }

        if (session?.action == SettingsAction.ADMIN_MENU) {
            setItemIfPresent(inventory, 20, decoration(Material.GRAY_STAINED_GLASS_PANE))
            if (!archiveEnabled()) setItemIfPresent(inventory, 23, decoration(Material.GRAY_STAINED_GLASS_PANE))
        }
        if (!archiveEnabled() && session?.action == SettingsAction.ADMIN_WORLD_GUI) {
            setItemIfPresent(inventory, 46, decoration(Material.BLACK_STAINED_GLASS_PANE))
        }

        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            val type = ItemTag.getType(item)
            if (type in hiddenActionTypes()) {
                inventory.setItem(slot, decoration(Material.GRAY_STAINED_GLASS_PANE))
                continue
            }
            val sanitized = if (type == ItemTag.TYPE_GUI_SETTING_PUBLISH && session != null) {
                sanitizePublishItem(item, session.worldUuid, player)
            } else {
                sanitizeItem(item, player)
            }
            inventory.setItem(slot, sanitized)
        }
    }

    private fun isManagedInventory(inventory: Inventory, action: SettingsAction?): Boolean {
        val holder = inventory.holder
        if (holder != null) {
            val holderClass = holder::class.java.name
            return holderClass.startsWith("me.awabi2048.myworldmanager.") ||
                holderClass.startsWith("me.awabi2048.mwmchanpon.")
        }
        return when (action) {
            SettingsAction.ADMIN_MENU -> inventory.size >= 24
            SettingsAction.ADMIN_WORLD_GUI -> inventory.size >= 47
            else -> false
        }
    }

    private fun setItemIfPresent(inventory: Inventory, slot: Int, item: ItemStack) {
        if (slot in 0 until inventory.size) {
            inventory.setItem(slot, item)
        }
    }

    private fun sanitizeItem(source: ItemStack, player: Player): ItemStack {
        val item = source.clone()
        val worldUuid = ItemTag.getWorldUuid(item)
        val lore = item.itemMeta?.lore() ?: return item
        val sanitized = lore.mapNotNull { line ->
            val text = plain.serialize(line)
            if (shouldDropLine(text)) {
                null
            } else {
                replacementLine(text, worldUuid, player) ?: line
            }
        }
        item.editMeta { meta -> meta.lore(sanitized) }
        return item
    }

    private fun sanitizePublishItem(source: ItemStack, worldUuid: UUID, player: Player): ItemStack {
        val state = resolvePublishState(worldUuid)
        val item = source.clone()
        item.editMeta { meta ->
            meta.lore(
                listOf(
                    text(player, "chanpon.publish.item.current", "level" to publishStateName(player, state)),
                    text(player, "chanpon.publish.item.public_desc", "prefix" to activePrefix(state, PublishState.PUBLIC)),
                    text(player, "chanpon.publish.item.development_desc", "prefix" to activePrefix(state, PublishState.DEVELOPMENT)),
                    text(player, "chanpon.publish.item.private_desc", "prefix" to activePrefix(state, PublishState.PRIVATE)),
                    text(player, "chanpon.publish.item.left_click"),
                    text(player, "chanpon.publish.item.right_click")
                ).map { legacy.deserialize(it) }
            )
        }
        return item
    }

    private fun replacementLine(text: String, worldUuid: UUID?, player: Player): Component? {
        if (worldUuid == null || !isPublishLine(text)) return null
        val label = publishStateName(player, resolvePublishState(worldUuid))
        return legacy.deserialize(text(player, "chanpon.publish.item.line", "level" to label))
    }

    private fun shouldDropLine(text: String): Boolean {
        val normalized = text.lowercase()
        if (!archiveEnabled() && isArchiveLine(normalized)) return true
        return normalized.contains("favorite") ||
            normalized.contains("visitor") ||
            normalized.contains("world point") ||
            normalized.contains("my world slot") ||
            normalized.contains("cost") ||
            normalized.contains("points you own") ||
            normalized.contains("consumption points")
    }

    private fun isArchiveLine(normalized: String): Boolean {
        return normalized.contains("archive") || normalized.contains("archived")
    }

    private fun isPublishLine(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("publish")
    }

    private fun nextAllowedDiscoverySort(current: DiscoverySort, forward: Boolean): DiscoverySort {
        val allowed = listOf(DiscoverySort.NEW, DiscoverySort.SPOTLIGHT, DiscoverySort.RANDOM)
        val currentIndex = allowed.indexOf(current).let { if (it == -1) 0 else it }
        val delta = if (forward) 1 else allowed.size - 1
        return allowed[(currentIndex + delta) % allowed.size]
    }

    private fun cyclePublishLevel(worldData: me.awabi2048.myworldmanager.model.WorldData, forward: Boolean) {
        val states = PublishState.entries
        val current = resolvePublishState(worldData.uuid)
        val delta = if (forward) 1 else states.size - 1
        val next = states[(current.ordinal + delta) % states.size]
        applyPublishState(worldData.uuid, next)
    }

    private fun resolvePublishState(worldUuid: UUID): PublishState {
        return when (storage.getVisibility(worldUuid)) {
            ChanponWorldVisibility.PUBLIC -> PublishState.PUBLIC
            ChanponWorldVisibility.DEVELOPMENT -> PublishState.DEVELOPMENT
            ChanponWorldVisibility.PRIVATE -> PublishState.PRIVATE
        }
    }

    private fun applyPublishState(worldUuid: UUID, state: PublishState) {
        val visibility = when (state) {
            PublishState.PUBLIC -> ChanponWorldVisibility.PUBLIC
            PublishState.DEVELOPMENT -> ChanponWorldVisibility.DEVELOPMENT
            PublishState.PRIVATE -> ChanponWorldVisibility.PRIVATE
        }
        storage.setVisibility(worldUuid, visibility)
    }

    private fun activePrefix(current: PublishState, target: PublishState): String {
        return if (current == target) "§a> " else "§8- "
    }

    private fun createBedrockPublishItem(worldData: me.awabi2048.myworldmanager.model.WorldData, player: Player): ItemStack {
        val state = publishStateName(player, resolvePublishState(worldData.uuid))
        val item = ItemStack(Material.ENDER_EYE)
        item.editMeta { meta ->
            meta.displayName(legacy.deserialize(text(player, "chanpon.publish.item.title", "level" to state)))
            meta.lore(
                listOf(
                    legacy.deserialize(text(player, "chanpon.publish.item.summary")),
                    legacy.deserialize(text(player, "chanpon.publish.item.cycle"))
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, "bedrock_menu_item")
        ItemTag.setString(item, "bedrock_action", "cycle_publish")
        ItemTag.setWorldUuid(item, worldData.uuid)
        return item
    }

    private fun createDiscoverySortItem(player: Player): ItemStack {
        val session = mwm().discoverySessionManager.getSession(player.uniqueId)
        val current = when (session.sort) {
            DiscoverySort.SPOTLIGHT -> text(player, "chanpon.discovery_sort.spotlight")
            DiscoverySort.RANDOM -> text(player, "chanpon.discovery_sort.random")
            else -> text(player, "chanpon.discovery_sort.new")
        }
        val item = ItemStack(Material.HOPPER)
        item.editMeta { meta ->
            meta.displayName(legacy.deserialize(text(player, "chanpon.discovery_sort.title")))
            meta.lore(
                listOf(
                    legacy.deserialize(text(player, "chanpon.discovery_sort.current", "sort" to current)),
                    legacy.deserialize(text(player, "chanpon.discovery_sort.new_line")),
                    legacy.deserialize(text(player, "chanpon.discovery_sort.spotlight_line")),
                    legacy.deserialize(text(player, "chanpon.discovery_sort.random_line")),
                    legacy.deserialize(text(player, "chanpon.discovery_sort.cycle"))
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DISCOVERY_SORT)
        return item
    }

    private fun hiddenActionTypes(): Set<String> {
        val base = mutableSetOf(
            ItemTag.TYPE_GUI_ADMIN_REPAIR_TEMPLATES,
            "world_seed_confirm_yes"
        )
        if (!archiveEnabled()) {
            base.add(ItemTag.TYPE_GUI_ADMIN_ARCHIVE_ALL)
            base.add(ItemTag.TYPE_GUI_ADMIN_FILTER_ARCHIVE)
        }
        return base
    }

    private fun isAdminWorldList(player: Player): Boolean {
        return mwm().settingsSessionManager.getSession(player)?.action == SettingsAction.ADMIN_WORLD_GUI
    }

    private fun archiveEnabled(): Boolean = plugin.config.getBoolean("archive.enabled", false)

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private fun decoration(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun publishStateName(player: Player?, state: PublishState): String {
        return text(player, "chanpon.publish.${state.key}")
    }

    private fun text(player: Player?, key: String, vararg placeholders: Pair<String, Any>): String {
        return plugin.getMessage(player, key, *placeholders)
    }

    private enum class PublishState(val key: String) {
        PUBLIC("public"),
        DEVELOPMENT("development"),
        PRIVATE("private")
    }
}
