package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent

class SubmittedWorldProtectionListener(
    private val protectionService: SubmittedWorldProtectionService
) : Listener {
    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(event.world.name)
            ?: return
        protectionService.sync(worldData)
    }
}
