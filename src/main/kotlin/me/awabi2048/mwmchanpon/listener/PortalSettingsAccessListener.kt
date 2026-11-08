package me.awabi2048.mwmchanpon.listener

import me.awabi2048.myworldmanager.api.event.MwmPortalSettingsAccessEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** 全体の建築停止中は、個人バイパスの有無にかかわらずポータル設定を管理者だけに限定します。 */
class PortalSettingsAccessListener(private val buildingAllowed: () -> Boolean) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAccess(event: MwmPortalSettingsAccessEvent) {
        val player = event.player
        if (player.hasPermission("myworldmanager.admin") || player.hasPermission("mwmchanpon.admin")) return
        if (!buildingAllowed()) event.isCancelled = true
    }
}
