package me.awabi2048.mwmchanpon.util

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.plugin.java.JavaPlugin

fun InventoryClickEvent.cancelWithDebug(source: String, force: Boolean = false) {
    val player = this.whoClicked as? Player ?: return
    val plugin = JavaPlugin.getPlugin(MWMChanponPlugin::class.java)
    if (!force && !shouldCancelProtectedMenuClick()) return
    val parts = source.split(": ", limit = 2)
    val path = parts[0]
    val reason = parts.getOrElse(1) { "unspecified" }
    plugin.logger.info("[ClickCancel] player=${player.name} | source=${path} | reason=${reason} | title=${this.view.title()} | slot=${this.slot} | click=${this.click} | action=${this.action}")
    this.isCancelled = true
}

private fun InventoryClickEvent.shouldCancelProtectedMenuClick(): Boolean {
    if (clickedInventory == view.topInventory) return true
    if (clickedInventory != view.bottomInventory) return false
    return action == InventoryAction.MOVE_TO_OTHER_INVENTORY ||
        action == InventoryAction.COLLECT_TO_CURSOR ||
        action == InventoryAction.HOTBAR_SWAP ||
        action == InventoryAction.HOTBAR_MOVE_AND_READD ||
        action == InventoryAction.UNKNOWN
}
