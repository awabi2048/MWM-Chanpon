package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.MyWorldSafetyDefaultsService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent

/** マイワールド作成・ロード時に安全既定値を適用し、WorldGuardの一時的な未準備も次tickに再試行します。 */
class MyWorldSafetyDefaultsListener(
    private val plugin: MWMChanponPlugin,
    private val service: MyWorldSafetyDefaultsService,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWorldCreated(event: MwmWorldCreatedEvent) {
        // event.worldName は表示名のため、実ワールド名を直接組み立てず UUID から WorldData を引きます。
        MyWorldManagerApi.getWorldRepository()
            ?.findByUuid(event.worldUuid)
            ?.let(::syncWithRetry)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWorldLoaded(event: WorldLoadEvent) {
        MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(event.world.name)
            ?.let(::syncWithRetry)
    }

    private fun syncWithRetry(worldData: me.awabi2048.myworldmanager.model.WorldData) {
        if (service.sync(worldData)) return

        // WorldGuardのRegionManagerがロード途中だった場合だけ、作成トランザクションを止めずに再試行します。
        plugin.server.scheduler.runTask(plugin, Runnable { service.sync(worldData) })
    }
}
