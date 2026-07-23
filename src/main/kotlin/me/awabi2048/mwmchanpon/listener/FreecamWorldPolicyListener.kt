package me.awabi2048.mwmchanpon.listener

import me.awabi2048.chanponutilities.freecam.FreecamStartCheckEvent
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class FreecamWorldPolicyListener(
    private val storage: ChanponWorldStorage
) : Listener {
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onFreecamStartCheck(event: FreecamStartCheckEvent) {
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(event.player.world.name)
            ?: return
        if (!storage.isFreecamAllowed(worldData.uuid)) {
            event.isCancelled = true
        }
    }
}
