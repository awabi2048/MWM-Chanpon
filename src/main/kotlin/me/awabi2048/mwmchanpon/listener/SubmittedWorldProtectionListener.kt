package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.world.WorldLoadEvent

class SubmittedWorldProtectionListener(
    private val plugin: MWMChanponPlugin,
    private val protectionService: SubmittedWorldProtectionService
) : Listener {
    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(event.world.name)
            ?: return
        protectionService.sync(worldData)
    }
    /** ログイン・移動時に権限を再評価し、シミュレーションの管理者所属を更新します。 */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = refreshLater(event.player)

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = refreshLater(event.player)

    private fun refreshLater(player: org.bukkit.entity.Player) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline || !plugin.isConfigurationMigrationCurrent()) return@Runnable
            MyWorldManagerApi.getWorldRepository()?.findByWorldKey(player.world.key.toString())
                ?.let(protectionService::sync)
        })
    }
}
