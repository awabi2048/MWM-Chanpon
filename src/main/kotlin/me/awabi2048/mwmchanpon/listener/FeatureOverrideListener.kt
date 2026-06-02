package me.awabi2048.mwmchanpon.listener

import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.gui.DiscoveryGui
import me.awabi2048.myworldmanager.gui.MeetGui
import me.awabi2048.myworldmanager.model.PublishLevel
import me.awabi2048.myworldmanager.session.DiscoverySort
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.ui.bedrock.BedrockPlayerWorldListHolder
import me.awabi2048.myworldmanager.ui.bedrock.BedrockWorldActionHolder
import me.awabi2048.myworldmanager.util.ItemTag
import org.bukkit.inventory.ItemFlag
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.event.server.TabCompleteEvent
import org.bukkit.inventory.Inventory
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val disabledReason = disabledMwmCommandReason(event.message) ?: return
        event.isCancelled = true
        event.player.sendMessage("§cMWM-Chanpon: $disabledReason is disabled.")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        val disabledReason = disabledMwmCommandReason(event.command) ?: return
        event.isCancelled = true
        event.sender.sendMessage("MWM-Chanpon: $disabledReason is disabled.")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTabComplete(event: TabCompleteEvent) {
        val parts = commandParts(event.buffer)
        if (parts.isEmpty() || normalizeCommand(parts[0]) != "mwm") return
        if (parts.size == 1) {
            event.completions.removeIf { it.equals("stats", ignoreCase = true) }
            if (!archiveEnabled()) {
                event.completions.removeIf { it.equals("update-day", ignoreCase = true) }
            }
            return
        }
        if (parts[1].equals("give", ignoreCase = true) && parts.size >= 3) {
            event.completions.removeIf { it.equals("world_seed", ignoreCase = true) }
        }
        if (parts[1].equals("stats", ignoreCase = true) ||
            (!archiveEnabled() && parts[1].equals("update-day", ignoreCase = true))
        ) {
            event.completions.clear()
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        val type = ItemTag.getType(item)

        if (event.view.topInventory.holder is DiscoveryGui.DiscoveryGuiHolder) {
            val uuid = ItemTag.getWorldUuid(item)
            if (uuid != null && storage.isDevelopmentWorld(uuid)) {
                event.isCancelled = true
                event.clickedInventory?.setItem(event.slot, decoration(Material.WHITE_STAINED_GLASS_PANE))
                return
            }
        }

        val bedrockAction = ItemTag.getString(item, "bedrock_action")
        if (event.view.topInventory.holder is BedrockWorldActionHolder) {
            val uuid = ItemTag.getWorldUuid(item)
            val worldData = uuid?.let { mwm().worldConfigRepository.findByUuid(it) }
            when (bedrockAction) {
                "cycle_publish" -> {
                    if (worldData == null) return
                    event.isCancelled = true
                    cyclePublishLevel(worldData, forward = true)
                    event.view.topInventory.setItem(11, createBedrockPublishItem(worldData))
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
            return
        }

        if (type == "gui_meet_target_head" && event.view.topInventory.holder is MeetGui.MeetGuiHolder) {
            val target = (item.itemMeta as? org.bukkit.inventory.meta.SkullMeta)?.owningPlayer?.player ?: return
            val worldData = mwm().worldConfigRepository.findByWorldName(target.world.name) ?: return
            if (worldData.publishLevel == PublishLevel.PUBLIC && storage.isDevelopmentWorld(worldData.uuid)) {
                event.isCancelled = true
                sendMeetRequest(player, target)
            }
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
        }

        if (discovery) {
            setItemIfPresent(inventory, 48, createDiscoverySortItem(player))
        }
        if (holder is BedrockPlayerWorldListHolder) {
            val footerStart = inventory.size - 9
            setItemIfPresent(inventory, footerStart + 4, decoration(Material.BLACK_STAINED_GLASS_PANE))
        }
        if (holder is BedrockWorldActionHolder) {
            val worldData = mwm().worldConfigRepository.findByUuid(holder.worldUuid)
            if (worldData != null) {
                setItemIfPresent(inventory, 11, createBedrockPublishItem(worldData))
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
            val uuid = ItemTag.getWorldUuid(item)
            if (discovery && uuid != null && storage.isDevelopmentWorld(uuid)) {
                inventory.setItem(slot, decoration(Material.WHITE_STAINED_GLASS_PANE))
                continue
            }

            val type = ItemTag.getType(item)
            if (type in hiddenActionTypes()) {
                inventory.setItem(slot, decoration(Material.GRAY_STAINED_GLASS_PANE))
                continue
            }
            val sanitized = if (type == ItemTag.TYPE_GUI_SETTING_PUBLISH && session != null) {
                sanitizePublishItem(item, session.worldUuid)
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
        val sanitized = mutableListOf<Component>()

        for (line in lore) {
            val text = plain.serialize(line)
            if (shouldDropLine(text)) continue
            val replacement = replacementLine(text, worldUuid, player)
            sanitized.add(replacement ?: line)
        }

        item.editMeta { meta -> meta.lore(sanitized) }
        return item
    }

    private fun sanitizePublishItem(source: ItemStack, worldUuid: UUID): ItemStack {
        val world = mwm().worldConfigRepository.findByUuid(worldUuid) ?: return source
        val state = resolvePublishState(world)
        val lines = listOf(
            "§f§l| §7現在 §e${state.displayName}",
            "§f§l| §7${activePrefix(state, PublishState.PUBLIC)}§a公開中 §fディスカバリーに表示し、通常どおり訪問できます。",
            "§f§l| §7${activePrefix(state, PublishState.DEVELOPMENT)}§e制作中 §fディスカバリーに表示せず、/meet は申請扱いです。",
            "§f§l| §7${activePrefix(state, PublishState.PRIVATE)}§7非公開 §fメンバー以外は訪問できません。",
            "§e§l| §e左クリック §7前の公開レベルへ",
            "§e§l| §e右クリック §7次の公開レベルへ"
        )
        val item = source.clone()
        item.editMeta { meta ->
            meta.lore(lines.map { legacy.deserialize(it) })
        }
        return item
    }

    private fun replacementLine(text: String, worldUuid: UUID?, player: Player): Component? {
        if (worldUuid == null || !isPublishLine(text)) return null
        val world = mwm().worldConfigRepository.findByUuid(worldUuid) ?: return null
        val label = when {
            world.publishLevel == PublishLevel.PUBLIC && storage.isDevelopmentWorld(worldUuid) -> "§e制作中"
            world.publishLevel == PublishLevel.PUBLIC -> "§a公開中"
            else -> "§7非公開"
        }
        return legacy.deserialize("§f§l| §7公開レベル $label")
    }

    private fun shouldDropLine(text: String): Boolean {
        val normalized = text.lowercase()
        if (isFavoriteAggregateLine(text)) return true
        if (isVisitorAggregateLine(text)) return true
        if (isPointOrSlotLine(text)) return true
        if (!archiveEnabled() && isArchiveLine(normalized)) return true
        return false
    }

    private fun isFavoriteAggregateLine(text: String): Boolean {
        val normalized = text.lowercase()
        val hasNumber = text.any { it.isDigit() }
        return hasNumber && (
            text.contains("お気に入り数") ||
                text.contains("お気に入り ") ||
                normalized.contains("favorites")
            )
    }

    private fun isVisitorAggregateLine(text: String): Boolean {
        val normalized = text.lowercase()
        val hasNumber = text.any { it.isDigit() }
        return hasNumber && (
            text.contains("最近") ||
                text.contains("来訪者") ||
                text.contains("訪れたプレイヤー") ||
                normalized.contains("recent visitors") ||
                normalized.contains("recently visited")
            )
    }

    private fun isPointOrSlotLine(text: String): Boolean {
        val normalized = text.lowercase()
        return text.contains("🛖") ||
            text.contains("ワールドポイント") ||
            text.contains("ポイント") ||
            text.contains("ワールドスロット") ||
            text.contains("コスト") ||
            normalized.contains("world point") ||
            normalized.contains("my world slot") ||
            normalized.contains("cost") ||
            normalized.contains("points you own") ||
            normalized.contains("consumption points")
    }

    private fun isArchiveLine(normalized: String): Boolean {
        return normalized.contains("アーカイブ") ||
            normalized.contains("archive") ||
            normalized.contains("archived")
    }

    private fun isPublishLine(text: String): Boolean {
        val normalized = text.lowercase()
        return text.contains("公開レベル") ||
            text.contains("公開設定") ||
            normalized.contains("publish")
    }

    private fun sendMeetRequest(player: Player, target: Player) {
        val lang = mwm().languageManager
        player.closeInventory()
        player.sendMessage(lang.getMessage(player, "general.meet_request.sent", mapOf("player" to target.name)))
        target.sendMessage(lang.getMessage(target, "general.meet_request.received", mapOf("player" to player.name)))
        val result = mwm().pendingDecisionManager.enqueueMeetRequest(target, player.uniqueId, target.world.uid, 60)
        if (mwm().playerPlatformResolver.isBedrock(target)) {
            mwm().pendingDecisionManager.sendPendingHint(target, result.count)
        }
    }

    private fun nextAllowedDiscoverySort(current: DiscoverySort, forward: Boolean): DiscoverySort {
        val allowed = listOf(DiscoverySort.NEW, DiscoverySort.SPOTLIGHT, DiscoverySort.RANDOM)
        val currentIndex = allowed.indexOf(current).let { if (it == -1) 0 else it }
        val delta = if (forward) 1 else allowed.size - 1
        return allowed[(currentIndex + delta) % allowed.size]
    }

    private fun cyclePublishLevel(worldData: me.awabi2048.myworldmanager.model.WorldData, forward: Boolean) {
        val states = PublishState.entries
        val current = resolvePublishState(worldData)
        val delta = if (forward) 1 else states.size - 1
        val next = states[(current.ordinal + delta) % states.size]
        applyPublishState(worldData, next)
        mwm().worldConfigRepository.save(worldData)
    }

    private fun resolvePublishState(worldData: me.awabi2048.myworldmanager.model.WorldData): PublishState {
        if (worldData.publishLevel == PublishLevel.PUBLIC && storage.isDevelopmentWorld(worldData.uuid)) {
            return PublishState.DEVELOPMENT
        }
        if (worldData.publishLevel == PublishLevel.PUBLIC) {
            return PublishState.PUBLIC
        }
        return PublishState.PRIVATE
    }

    private fun applyPublishState(worldData: me.awabi2048.myworldmanager.model.WorldData, state: PublishState) {
        when (state) {
            PublishState.PUBLIC -> {
                worldData.publishLevel = PublishLevel.PUBLIC
                storage.setDevelopmentWorld(worldData.uuid, false)
            }
            PublishState.DEVELOPMENT -> {
                worldData.publishLevel = PublishLevel.PUBLIC
                storage.setDevelopmentWorld(worldData.uuid, true)
            }
            PublishState.PRIVATE -> {
                worldData.publishLevel = PublishLevel.LOCKED
                storage.setDevelopmentWorld(worldData.uuid, false)
            }
        }
    }

    private fun activePrefix(current: PublishState, target: PublishState): String {
        return if (current == target) "§a» " else "§8- "
    }

    private fun createBedrockPublishItem(worldData: me.awabi2048.myworldmanager.model.WorldData): ItemStack {
        val state = resolvePublishState(worldData).displayName
        val item = ItemStack(Material.ENDER_EYE)
        item.editMeta { meta ->
            meta.displayName(legacy.deserialize("§e公開レベル: §f$state"))
            meta.lore(
                listOf(
                    legacy.deserialize("§7公開中 / 制作中 / 非公開を切り替えます。"),
                    legacy.deserialize("§e§l| §eクリック §7次の公開レベルへ")
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
            DiscoverySort.SPOTLIGHT -> "スポットライト"
            DiscoverySort.RANDOM -> "ランダム"
            else -> "新着順"
        }
        val item = ItemStack(Material.HOPPER)
        item.editMeta { meta ->
            meta.displayName(legacy.deserialize("§e並び順"))
            meta.lore(
                listOf(
                    legacy.deserialize("§f§l| §7現在 §e$current"),
                    legacy.deserialize("§7- §a新着順"),
                    legacy.deserialize("§7- §dスポットライト"),
                    legacy.deserialize("§7- §bランダム"),
                    legacy.deserialize("§e§l| §eクリック §7並び順を切り替え")
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

    private fun disabledMwmCommandReason(raw: String): String? {
        val parts = commandParts(raw)
        if (parts.size < 2 || normalizeCommand(parts[0]) != "mwm") return null
        if (parts[1].equals("stats", ignoreCase = true)) return "/mwm stats"
        if (!archiveEnabled() && parts[1].equals("update-day", ignoreCase = true)) return "/mwm update-day"
        if (parts[1].equals("give", ignoreCase = true) &&
            parts.size >= 4 &&
            parts[3].equals("world_seed", ignoreCase = true)
        ) {
            return "/mwm give world_seed"
        }
        return null
    }

    private fun commandParts(raw: String): List<String> {
        return raw.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun normalizeCommand(command: String): String {
        return command.substringAfter(':').lowercase()
    }

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

    private enum class PublishState(val displayName: String) {
        PUBLIC("公開中"),
        DEVELOPMENT("制作中"),
        PRIVATE("非公開")
    }
}
