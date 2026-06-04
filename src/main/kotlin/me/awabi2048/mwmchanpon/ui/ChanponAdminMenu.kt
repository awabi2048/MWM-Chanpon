package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class ChanponAdminMenu(
    private val plugin: MWMChanponPlugin,
    private val submissionAdminMenu: SubmissionAdminMenu,
    private val worldDataManagementMenu: WorldDataManagementMenu,
    private val automationSettingsMenu: AutomationSettingsMenu
) : Listener {
    fun open(player: Player) {
        if (!player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(i18nComponent(player, "admin.no_permission"))
            return
        }
        val holder = Holder()
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "admin.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(19, menuItem(player, Material.LIME_BANNER, "admin.submission", ACTION_SUBMISSION))
        inventory.setItem(21, menuItem(player, Material.CHEST, "admin.world_data", ACTION_WORLD_DATA))
        inventory.setItem(23, menuItem(player, Material.CLOCK, "admin.automation", ACTION_AUTOMATION))
        inventory.setItem(25, menuItem(player, Material.DISPENSER, "admin.export", ACTION_EXPORT))
        inventory.setItem(40, infoItem(player))
        fill(inventory)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_SUBMISSION -> submissionAdminMenu.open(player)
            ACTION_WORLD_DATA -> worldDataManagementMenu.open(player)
            ACTION_AUTOMATION -> automationSettingsMenu.open(player)
            ACTION_EXPORT -> player.sendMessage(i18nComponent(player, "admin.export.mock_message"))
        }
    }

    private fun applyFrame(inventory: Inventory) {
        val black = decoration(Material.BLACK_STAINED_GLASS_PANE)
        for (slot in 0..8) inventory.setItem(slot, black)
        for (slot in 36..44) inventory.setItem(slot, black)
    }

    private fun fill(inventory: Inventory) {
        val gray = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0 until inventory.size) if (inventory.getItem(slot) == null) inventory.setItem(slot, gray)
    }

    private fun menuItem(player: Player, material: Material, key: String, action: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(lore(i18nList(player, "$key.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun infoItem(player: Player): ItemStack {
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin.info.display"))
            meta.lore(lore(i18nList(player, "admin.info.lore", mapOf("version" to plugin.pluginMeta.version))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
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

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    private fun lore(lines: List<String>) = CCSystem.getAPI().buildLore(lines, closingSeparator = true)

    private class Holder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val TYPE_CONTROL = "chanpon_admin_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_SUBMISSION = "submission"
        private const val ACTION_WORLD_DATA = "world_data"
        private const val ACTION_AUTOMATION = "automation"
        private const val ACTION_EXPORT = "export"
    }
}
