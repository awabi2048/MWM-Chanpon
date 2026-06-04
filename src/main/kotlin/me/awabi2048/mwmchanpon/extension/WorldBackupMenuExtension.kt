package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
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
import org.bukkit.plugin.Plugin
import java.util.UUID

class WorldBackupMenuExtension(
    private val plugin: Plugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService
) : MenuExtension, Listener {
    override fun getId(): String = EXTENSION_ID

    override fun onRender(inventory: Inventory, player: Player, context: MenuExtensionContext) {
        if (context.menuType != "world_settings") return
        val worldData = context.get<WorldData>("worldData") ?: return
        if (!storage.isProductionWorld(worldData.uuid)) return
        if (worldData.owner != player.uniqueId) return
        inventory.setItem(6, item(player, worldData.uuid))
    }

    override fun onClick(event: InventoryClickEvent, player: Player, context: MenuExtensionContext): Boolean {
        val item = event.currentItem ?: return false
        if (ItemTag.getExtensionId(item) != EXTENSION_ID) return false
        val worldUuid = ItemTag.getWorldUuid(item) ?: return false
        openConfirm(player, worldUuid)
        return true
    }

    @EventHandler
    fun onConfirmClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? ConfirmHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> player.closeInventory()
            ItemTag.TYPE_GUI_CONFIRM -> {
                player.closeInventory()
                backupService.createWorldBackup(holder.worldUuid, BackupKind.USER_MANUAL, player.uniqueId, player.name, "owner_manual")
                    .thenAccept { result ->
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.sendMessage(i18nComponent(player, if (result.status == BackupOperationStatus.SUCCESS) "message.backup_success" else "message.backup_failed"))
                        })
                    }
            }
        }
    }

    private fun openConfirm(player: Player, worldUuid: UUID) {
        val holder = ConfirmHolder(worldUuid)
        val inv = Bukkit.createInventory(holder, 45, i18nComponent(player, "confirm.owner_backup.title"))
        holder.inv = inv
        val black = decoration(Material.BLACK_STAINED_GLASS_PANE)
        val gray = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0..8) inv.setItem(slot, black)
        for (slot in 36..44) inv.setItem(slot, black)
        inv.setItem(22, centerItem(player))
        inv.setItem(20, button(player, true))
        inv.setItem(24, button(player, false))
        for (slot in 0 until inv.size) if (inv.getItem(slot) == null) inv.setItem(slot, gray)
        player.openInventory(inv)
    }

    private fun item(player: Player, worldUuid: UUID): ItemStack {
        val item = ItemStack(Material.CHEST)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "owner_backup.display"))
            meta.lore(lore(i18nList(player, "owner_backup.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, EXTENSION_ID)
        ItemTag.setWorldUuid(item, worldUuid)
        return item
    }

    private fun centerItem(player: Player): ItemStack {
        val item = ItemStack(Material.CHEST)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "confirm.owner_backup.display"))
            meta.lore(lore(i18nList(player, "confirm.owner_backup.lore")))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun button(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        item.editMeta { it.displayName(i18nComponent(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel")) }
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun decoration(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { it.displayName(net.kyori.adventure.text.Component.empty()); it.isHideTooltip = true }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)
    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)
    private fun lore(lines: List<String>) = CCSystem.getAPI().buildLore(lines, closingSeparator = true)

    private class ConfirmHolder(val worldUuid: UUID) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val EXTENSION_ID = "chanpon.world_backup"
    }
}
