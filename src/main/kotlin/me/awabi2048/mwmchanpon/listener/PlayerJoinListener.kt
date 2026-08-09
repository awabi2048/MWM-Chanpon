package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.service.ChanponSpawnWorldService
import me.awabi2048.mwmchanpon.service.OfflineMemberInviteNotifier
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(
    private val controller: ToolPermissionController,
    private val spawnWorldService: ChanponSpawnWorldService,
    private val offlineMemberInviteNotifier: OfflineMemberInviteNotifier,
) : Listener {
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        spawnWorldService.handleFirstJoin(player)
        controller.syncPlayerLocation(player)
        // 初回スポーンと位置同期の既存順序を維持し、その後にログイン通知だけを追加します。
        offlineMemberInviteNotifier.notifyOnJoin(player)
    }
}
