package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.protection.PublicWorldFawePermissionService
import me.awabi2048.myworldmanager.api.event.MwmWorldDeletedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class WorldCleanupListener(
    private val storage: ChanponWorldStorage,
    private val publicWorldFawePermissionService: PublicWorldFawePermissionService,
) : Listener {
    @EventHandler
    fun onWorldDeleted(event: MwmWorldDeletedEvent) {
        publicWorldFawePermissionService.clear(event.worldName)
        storage.deleteWorld(event.worldUuid)
    }
}
