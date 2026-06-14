package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.service.ChanponSpawnWorldService
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(
    private val controller: ToolPermissionController,
    private val spawnWorldService: ChanponSpawnWorldService
) : Listener {
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        spawnWorldService.handleFirstJoin(player)
        controller.syncPlayerLocation(player)
    }
}
