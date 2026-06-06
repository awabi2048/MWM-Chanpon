package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenuHolder
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.util.ItemTag
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

class ToolPermissionMenuListener(
    private val menu: ToolPermissionMenu,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>
) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ToolPermissionMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return

        val player = event.whoClicked as? Player ?: return
        val item = event.currentItem ?: return
        val type = ItemTag.getType(item)
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return

        if (type == ItemTag.TYPE_GUI_RETURN) {
            holder.onBack()
            return
        }

        if (type != ItemTag.TYPE_GUI_EXTENSION) return
        if (ItemTag.getExtensionId(item) != ToolPermissionMenu.EXTENSION_ID) return
        if (ItemTag.getString(item, "chanpon_action") != "toggle_tool") return
        if (worldData.owner != player.uniqueId) {
            player.sendMessage("§cオーナーのみ操作できます")
            return
        }

        val toolId = ItemTag.getString(item, "chanpon_tool") ?: return
        val definition = definitions.firstOrNull { it.id == toolId } ?: return
        val next = !controller.isEnabled(worldData, holder.targetPlayerUuid, definition.id)
        controller.setEnabled(worldData, holder.targetPlayerUuid, definition.id, next)
        menu.open(player, worldData, holder.targetPlayerUuid, holder.onBack)
    }
}
