package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldEvacuationProvider
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

class ChanponSpawnWorldService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponGlobalStorage
) : WorldEvacuationProvider {
    override fun getId(): String = "mwmchanpon.spawn_world"

    override fun getEvacuationLocation(): Location? {
        val worldUuid = storage.getSpawnWorldUuid() ?: return null
        val repository = MyWorldManagerApi.getWorldRepository() ?: return null
        val service = MyWorldManagerApi.getWorldService() ?: return null
        val worldData = repository.findByUuid(worldUuid) ?: return null
        if (worldData.isArchived) return null
        if (!service.loadWorld(worldUuid)) return null
        val world = Bukkit.getWorld(service.getWorldFolderName(worldData)) ?: return null
        return world.spawnLocation
    }

    fun handleFirstJoin(player: Player) {
        if (player.hasPlayedBefore()) return
        if (storage.isInitialSpawnVisitCompleted(player.uniqueId)) return
        val worldUuid = storage.getSpawnWorldUuid() ?: return
        val service = MyWorldManagerApi.getWorldService() ?: return
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        repository.findByUuid(worldUuid) ?: return

        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            service.teleportToWorld(player, worldUuid)
            storage.setInitialSpawnVisitCompleted(player.uniqueId)
        })
    }
}
