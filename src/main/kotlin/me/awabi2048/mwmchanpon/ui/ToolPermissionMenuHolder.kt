package me.awabi2048.mwmchanpon.ui

import java.util.UUID
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import org.bukkit.inventory.Inventory

class ToolPermissionMenuHolder(
    val worldUuid: UUID,
    val targetPlayerUuid: UUID,
    val onBack: () -> Unit
) : WorldSettingsInventoryHolder {
    private var inventory: Inventory? = null

    fun bind(inventory: Inventory) {
        this.inventory = inventory
    }

    override fun getInventory(): Inventory {
        return inventory ?: throw IllegalStateException("Inventory is not bound")
    }
}
