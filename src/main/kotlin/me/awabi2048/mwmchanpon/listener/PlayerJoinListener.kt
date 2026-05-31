package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(
    private val controller: ToolPermissionController
) : Listener {
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        val myWorlds = repository.findByOwner(player.uniqueId) +
            repository.findAll().filter { player.uniqueId in it.members }
        myWorlds.forEach { worldData ->
            controller.syncWorld(worldData)
        }
    }
}
