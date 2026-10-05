package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent

/**
 * ワールドロードのたびに環境設定の建築許可（保護モード）を WorldGuard へ再適用します。
 *
 * passthrough フラグは WorldGuard 側へ永続化されますが、リージョンの喪失、復元先の
 * 新規 __global__、外部ツールによる直接編集で protection.mode と乖離し得るため、
 * ロード時に実効モードへ収束させます。
 */
class WorldBuildPermissionListener(
    private val plugin: MWMChanponPlugin,
    private val buildPermissionService: WorldBuildPermissionService,
) : Listener {
    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(event.world.name)
            ?: return
        buildPermissionService.sync(worldData)
    }
}
