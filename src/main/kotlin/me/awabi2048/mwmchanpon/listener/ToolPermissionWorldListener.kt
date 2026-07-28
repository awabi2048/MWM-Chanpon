package me.awabi2048.mwmchanpon.listener

import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent

class ToolPermissionWorldListener(
    private val controller: ToolPermissionController
) : Listener {
    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        if (MyWorldManagerApi.isLogoutRelocation(event.player)) return
        controller.syncPlayerLocation(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        controller.clearActivePermissions(event.player.uniqueId)
    }
}
