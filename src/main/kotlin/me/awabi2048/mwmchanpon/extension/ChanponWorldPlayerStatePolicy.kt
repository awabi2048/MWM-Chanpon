package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.extension.WorldPlayerStateDecision
import me.awabi2048.myworldmanager.api.extension.WorldPlayerStatePolicy
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.entity.Player

class ChanponWorldPlayerStatePolicy(
    private val globalStorage: ChanponGlobalStorage,
    private val worldStorage: ChanponWorldStorage,
) : WorldPlayerStatePolicy {
    private var configurationAvailable: () -> Boolean = { true }

    fun setConfigurationAvailability(gate: () -> Boolean) {
        configurationAvailable = gate
    }
    override fun getId(): String = POLICY_ID

    override fun getPriority(): Int = 100

    override fun evaluate(player: Player, worldData: WorldData): WorldPlayerStateDecision {
        // MWM-Chanponは建築・飛行制約を提供しますが、ゲームモードは変更しません。
        if (!configurationAvailable() || !worldStorage.hasCurrentWorldData(worldData.uuid)) {
            return WorldPlayerStateDecision(
                buildAllowed = false,
                flightAllowed = false,
            )
        }
        if (isAdmin(player)) {
            return WorldPlayerStateDecision(buildAllowed = true)
        }
        if (globalStorage.isBuildingAllowed()) {
            return WorldPlayerStateDecision(buildAllowed = true)
        }
        return WorldPlayerStateDecision(
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
