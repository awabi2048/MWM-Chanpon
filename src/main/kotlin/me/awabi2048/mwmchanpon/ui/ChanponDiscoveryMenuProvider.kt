package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWarpReason
import me.awabi2048.myworldmanager.api.extension.DiscoveryMenuProvider
import me.awabi2048.myworldmanager.api.extension.DiscoveryMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
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
import org.bukkit.plugin.java.JavaPlugin

class ChanponDiscoveryMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val globalStorage: ChanponGlobalStorage,
    private val storage: ChanponWorldStorage
) : DiscoveryMenuProvider, Listener {
    private val legacy = LegacyComponentSerializer.legacySection()

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, request: DiscoveryMenuRequest): Boolean {
        val mwm = mwm()
        if (mwm.playerPlatformResolver.isBedrock(player)) return false
        if (globalStorage.isArchiveLocked() && !player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(CCSystem.getAPI().getI18nString(player, "chanpon.backup.message.archive_locked"))
            return true
        }

        val holder = Holder()
        val inventory = Bukkit.createInventory(
            holder,
            54,
            Component.text(
                mwm.languageManager.getMessage(player, "gui.discovery.title"),
                NamedTextColor.DARK_GRAY
            ).decoration(TextDecoration.ITALIC, false)
        )
        holder.inv = inventory
        applyFrame(inventory)

        completedWorlds()
            .take(WORLD_ITEM_SLOTS.size)
            .forEachIndexed { index, worldData ->
                inventory.setItem(WORLD_ITEM_SLOTS[index], createWorldItem(player, worldData))
            }

        inventory.setItem(45, returnItem(player))

        fill(inventory)
        mwm.soundManager.playMenuOpenSound(player, "discovery")
        player.openInventory(inventory)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.cancelWithDebug("ChanponDiscoveryMenuProvider.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                val mwm = mwm()
                mwm.soundManager.playClickSound(player, item, "discovery")
                if (!mwm.menuRouteHistory.openPrevious(player)) {
                    player.closeInventory()
                }
            }
            ItemTag.TYPE_GUI_WORLD_ITEM -> {
                if (!event.isLeftClick) return
                val worldUuid = ItemTag.getWorldUuid(item) ?: return
                val worldData = mwm().worldConfigRepository.findByUuid(worldUuid) ?: return
                if (!MyWorldManagerApi.getWorldAccessPolicy().canUseVisitEntry(player, worldData, false)) {
                    player.sendMessage(mwm().languageManager.getMessage(player, "error.world_not_public"))
                    return
                }
                mwm().soundManager.playClickSound(player, item, "discovery")
                player.closeInventory()
                mwm().worldService.teleportToWorld(player, worldUuid, null, true, MwmWarpReason.DIRECT, null)
                player.sendMessage(mwm().languageManager.getMessage(player, "messages.warp_success", mapOf("world" to worldData.name)))
            }
        }
    }

    private fun completedWorlds(): List<WorldData> {
        val worlds = MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }

        data class Entry(val world: WorldData, val completedAt: java.time.LocalDateTime, val productionSetAt: java.time.LocalDateTime?)
        val entries = worlds.mapNotNull { w ->
            val completedAt = storage.getCompletedAt(w.uuid) ?: return@mapNotNull null
            val productionSetAt = storage.getProductionSetAt(w.uuid)
            Entry(w, completedAt, productionSetAt)
        }

        val sorted = entries.sortedWith { a: Entry, b: Entry ->
            val completedCompare = a.completedAt.compareTo(b.completedAt)
            if (completedCompare != 0) completedCompare
            else compareValues(b.productionSetAt ?: java.time.LocalDateTime.MIN, a.productionSetAt ?: java.time.LocalDateTime.MIN)
        }

        return sorted.map { it.world }
    }

    private fun createWorldItem(player: Player, worldData: WorldData): ItemStack {
        val mwm = mwm()
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(
                mwm.languageManager.getComponent(player, "gui.common.world_item_name", mapOf("world" to worldData.name))
                    .decoration(TextDecoration.ITALIC, false)
            )
            val ownerName = Bukkit.getOfflinePlayer(worldData.owner).name
                ?: mwm.languageManager.getMessage(player, "general.unknown")
            val lines = listOf(
                mwm.languageManager.getMessage(player, "gui.player_world.world_item.owner", mapOf("owner" to ownerName)),
                SubmissionProgressDisplay.line(player, storage, worldData.uuid),
                "",
                mwm.languageManager.getMessage(player, "gui.player_world.world_item.warp")
            )
            meta.lore(CCSystem.getAPI().buildLore(lines, closingSeparator = true))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, worldData.uuid)
        return item
    }

    private fun returnItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(legacy.deserialize(mwm().languageManager.getMessage(player, "gui.common.return")).decoration(TextDecoration.ITALIC, false))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun applyFrame(inventory: Inventory) {
        val black = decoration(Material.BLACK_STAINED_GLASS_PANE)
        for (slot in 0..8) inventory.setItem(slot, black)
        for (slot in 45..53) inventory.setItem(slot, black)
    }

    private fun fill(inventory: Inventory) {
        val gray = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 9..44) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, gray)
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

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private class Holder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.discovery_menu"
        private val WORLD_ITEM_SLOTS = listOf(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        )
    }
}
