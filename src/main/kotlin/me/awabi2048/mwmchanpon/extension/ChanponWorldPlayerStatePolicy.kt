package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.myworldmanager.api.extension.WorldPlayerStateDecision
import me.awabi2048.myworldmanager.api.extension.WorldPlayerStatePolicy
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.GameMode
import org.bukkit.entity.Player

class ChanponWorldPlayerStatePolicy(
    private val globalStorage: ChanponGlobalStorage
) : WorldPlayerStatePolicy {
    override fun getId(): String = POLICY_ID

    override fun getPriority(): Int = 100

    override fun evaluate(player: Player, worldData: WorldData): WorldPlayerStateDecision {
        if (isAdmin(player)) {
            return WorldPlayerStateDecision(buildAllowed = true)
        }
        if (globalStorage.isBuildingAllowed()) {
            return WorldPlayerStateDecision(buildAllowed = true)
        }
        return WorldPlayerStateDecision(
            gameMode = GameMode.ADVENTURE,
            buildAllowed = false,
            flightAllowed = false
        )
    }

    private fun isAdmin(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin")

    companion object {
        const val POLICY_ID = "mwmchanpon.player_state"
    }
}
