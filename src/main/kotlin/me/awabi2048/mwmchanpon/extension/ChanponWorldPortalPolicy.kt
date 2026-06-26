package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.extension.WorldPortalPolicy
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.entity.Player

class ChanponWorldPortalPolicy(
    private val storage: ChanponWorldStorage
) : WorldPortalPolicy {
    override fun getId(): String = "mwmchanpon.world_portal"

    override fun isPortalOpen(destination: WorldData): Boolean {
        return storage.getVisibility(destination.uuid) == ChanponWorldVisibility.PUBLIC
    }

    override fun canUsePortal(player: Player, destination: WorldData): Boolean {
        return isPortalOpen(destination)
    }

    override fun blockedMessages(player: Player, destination: WorldData): List<String> {
        return listOf(
            text(player, "chanpon.portal.public_only"),
            text(player, "chanpon.portal.meet_hint")
        )
    }

    private fun text(player: Player, key: String): String {
        return CCSystem.getAPI().getI18nString(player, key)
    }
}
