package me.awabi2048.mwmchanpon.util

import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent

fun InventoryClickEvent.cancelWithDebug(source: String, force: Boolean = false) {
    if (!force && !shouldCancelProtectedMenuClick()) return
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
