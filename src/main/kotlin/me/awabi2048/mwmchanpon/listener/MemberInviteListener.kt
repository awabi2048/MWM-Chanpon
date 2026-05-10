package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.service.MemberManagementService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class MemberInviteListener(
    private val memberManagementService: MemberManagementService,
) : Listener {
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        memberManagementService.notifyPendingInvites(event.player)
    }
}
