package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.block.BlockState
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import me.awabi2048.mwmchanpon.api.ProtectedEntityClickEvent

class SubmittedWorldProtectionListener(
    private val plugin: MWMChanponPlugin,
    private val protectionService: SubmittedWorldProtectionService
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        val data = MyWorldManagerApi.getWorldRepository()?.findByWorldName(event.player.world.name) ?: return
        if (!protectionService.isProtected(data)) return
        // 他の保護が先に拒否した操作は通知せず、バニラ操作をキャンセルしたままクリックだけを渡します。
        event.isCancelled = true
        plugin.server.pluginManager.callEvent(ProtectedEntityClickEvent(event.player, event.rightClicked, event.hand))
    }
    /** ワールドや提出状態に依存せず、プレイヤーの収納操作だけを止めます。 */
    private fun isLockedStorage(inventory: Inventory): Boolean {
        val holder = inventory.holder
        if (holder !is BlockState && holder !is DoubleChest && (holder !is Entity || holder is Player)) return false
        // ホッパー等の自動搬送イベントは監視しないため、装置側の搬送は維持されます。
        return true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (event.player !is Player) return
        if (isLockedStorage(event.inventory)) event.isCancelled = true
    }

    // 保護の有効化より前に開いた収納や、シフトクリック・ドラッグも変更できないようにします。
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.whoClicked !is Player) return
        if (isLockedStorage(event.view.topInventory)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.whoClicked !is Player) return
        if (isLockedStorage(event.view.topInventory)) event.isCancelled = true
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(event.world.name)
            ?: return
        protectionService.sync(worldData)
    }
}
