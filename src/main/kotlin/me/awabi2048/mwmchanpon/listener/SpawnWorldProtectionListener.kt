package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent

class SpawnWorldProtectionListener(
    private val plugin: me.awabi2048.mwmchanpon.MWMChanponPlugin,
    private val storage: ChanponGlobalStorage,
    private val protection: SpawnWorldProtectionService
) : Listener {
    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        val spawnUuid = storage.getSpawnWorldUuid() ?: return
        if (storage.isSpawnPolicyInitialized(spawnUuid)) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(spawnUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.${worldData.uuid}"
        if (event.world.name == worldName && protection.applyDefaults(worldData)) {
            runCatching { storage.setSpawnPolicyInitialized(spawnUuid) }
        }
    }
}
