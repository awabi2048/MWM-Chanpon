package me.awabi2048.mwmchanpon.ui
import me.awabi2048.mwmchanpon.ui.ManagedMenuPresenter

import me.awabi2048.mwmchanpon.util.cancelWithDebug
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.service.BackupAutomationService
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
import org.bukkit.inventory.ItemStack

class AutomationSettingsMenu(
    private val plugin: MWMChanponPlugin,
    private val backupService: BackupService,
    private val automationService: BackupAutomationService,
    private val onSettingsChanged: () -> Unit = {}
) : Listener {
    private var parentMenuOpener: ((Player) -> Unit)? = null

    fun setParentMenuOpener(opener: (Player) -> Unit) {
        parentMenuOpener = opener
    }

    fun open(player: Player) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        val holder = Holder()
        val inv = Bukkit.createInventory(holder, 45, i18nComponent(player, "automation.title"))
        holder.inv = inv
        applyFrame(inv)
        inv.setItem(20, toggleItem(player, Material.CLOCK, "automation.auto_backup", ACTION_AUTO_BACKUP, plugin.config.getBoolean("backup.auto_backup.enabled", false)))
        inv.setItem(24, toggleItem(player, Material.RECOVERY_COMPASS, "automation.auto_rollback", ACTION_AUTO_ROLLBACK, plugin.config.getBoolean("backup.auto_rollback.enabled", false)))
        inv.setItem(36, backItem(player))
        if (plugin.config.getBoolean("backup.auto_rollback.enabled", false)) {
            inv.setItem(40, controlItem(player, Material.END_CRYSTAL, "automation.restore_point", ACTION_RESTORE_POINT))
        }
        fill(inv)
        ManagedMenuPresenter.open(player, inv)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        event.view.topInventory.holder as? Holder ?: return
        event.cancelWithDebug("AutomationSettingsMenu.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        if (ItemTag.getType(item) == ItemTag.TYPE_GUI_RETURN) {
            if (!mwm().menuRouteHistory.openPrevious(player)) {
                parentMenuOpener?.invoke(player) ?: ManagedMenuPresenter.close(player)
            }
            return
        }
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_AUTO_BACKUP -> openToggleConfirm(player, "backup.auto_backup.enabled", "confirm.auto_backup_toggle")
            ACTION_AUTO_ROLLBACK -> openToggleConfirm(player, "backup.auto_rollback.enabled", "confirm.auto_rollback_toggle")
            ACTION_RESTORE_POINT -> openRestorePointConfirm(player)
        }
    }

    @EventHandler
    fun onToggleConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? ToggleConfirmHolder ?: return
        event.cancelWithDebug("AutomationSettingsMenu.onToggleConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player)
            ItemTag.TYPE_GUI_CONFIRM -> {
                toggle(holder.configPath)
                open(player)
            }
        }
    }

    @EventHandler
    fun onRestorePointConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        event.view.topInventory.holder as? RestorePointConfirmHolder ?: return
        event.cancelWithDebug("AutomationSettingsMenu.onRestorePointConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player)
            ItemTag.TYPE_GUI_CONFIRM -> {
                ManagedMenuPresenter.close(player)
                backupService.createAllBackup(
                    automationService.productionWorldUuids(),
                    BackupKind.RESTORE_POINT,
                    player.uniqueId,
                    player.name,
                    "restore_point",
                    null
                ).thenAccept { result ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(i18nComponent(player, if (result.status == BackupOperationStatus.SUCCESS) "message.restore_point_success" else "message.backup_failed"))
                        open(player)
                    })
                }
            }
        }
    }

    private fun toggle(path: String) {
        plugin.config.set(path, !plugin.config.getBoolean(path, false))
        plugin.saveConfig()
        automationService.start()
        onSettingsChanged()
    }

    private fun openToggleConfirm(player: Player, configPath: String, key: String) {
        val current = plugin.config.getBoolean(configPath, false)
        val holder = ToggleConfirmHolder(configPath)
        val layout = ChanponGuiElements.confirmationLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "$key.title"))
        holder.inv = inv
        applyFrame(inv)
        ChanponGuiElements.setConfirmationItems(
            inv,
            centerItem(
                player,
                Material.LEVER,
                key,
                mapOf("state" to i18n(player, if (!current) "common.on" else "common.off"))
            ),
            button(player, true),
            button(player, false)
        )
        fill(inv)
        ManagedMenuPresenter.open(player, inv)
    }

    private fun openRestorePointConfirm(player: Player) {
        val holder = RestorePointConfirmHolder()
        val layout = ChanponGuiElements.confirmationLayout()
        val inv = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "confirm.restore_point.title"))
        holder.inv = inv
        applyFrame(inv)
        ChanponGuiElements.setConfirmationItems(
            inv,
            centerItem(player, Material.END_CRYSTAL, "confirm.restore_point"),
            button(player, true),
            button(player, false)
        )
        fill(inv)
        ManagedMenuPresenter.open(player, inv)
    }

    private fun applyFrame(inv: Inventory) {
        ChanponGuiElements.applyStandardFrame(inv)
    }

    private fun fill(inv: Inventory) {
        ChanponGuiElements.fill(inv)
    }

    private fun toggleItem(player: Player, material: Material, key: String, action: String, enabled: Boolean): ItemStack {
        val item = controlItem(player, material, key, action, mapOf("state" to i18n(player, if (enabled) "common.on" else "common.off")))
        if (enabled) item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1)
        return item
    }

    private fun controlItem(player: Player, material: Material, key: String, action: String, placeholders: Map<String, Any> = emptyMap()): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display", placeholders))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore", placeholders).map(GuiLoreLine::Text)))
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun centerItem(player: Player, material: Material, key: String, placeholders: Map<String, Any> = emptyMap()): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display", placeholders))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore", placeholders).map(GuiLoreLine::Text)))
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun button(player: Player, confirm: Boolean): ItemStack {
        val item = ChanponGuiElements.confirm(i18n(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel"), confirm)
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ChanponGuiElements.back(i18n(player, "common.back"))
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun i18n(player: Player?, key: String) = CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")
    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)
    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)

    private class Holder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class ToggleConfirmHolder(val configPath: String) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class RestorePointConfirmHolder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val TYPE_CONTROL = "chanpon_automation_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_AUTO_BACKUP = "auto_backup"
        private const val ACTION_AUTO_ROLLBACK = "auto_rollback"
        private const val ACTION_RESTORE_POINT = "restore_point"
    }
}
