package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent

/**
 * ワールドロードのたびに環境設定の建築許可（保護モード）を WorldGuard へ再適用します。
 *
 * 専用建築リージョンとその所属は WorldGuard 側へ永続化されますが、リージョンの喪失、
 * 復元先の新規 __global__、外部ツールによる編集で protection.mode と乖離し得るため、
 * 作成・ロード時に実効モードへ収束させます。
 */
class WorldBuildPermissionListener(
    private val plugin: MWMChanponPlugin,
    private val buildPermissionService: WorldBuildPermissionService,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWorldCreated(event: MwmWorldCreatedEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(event.worldUuid) ?: return

        // MWMの作成トランザクションをWorldGuardの準備状態に依存させず、次tickに同期します。
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (plugin.isConfigurationMigrationCurrent()) buildPermissionService.sync(worldData)
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWorldLoad(event: WorldLoadEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(event.world.name)
            ?: return
        buildPermissionService.sync(worldData)
    }
}
