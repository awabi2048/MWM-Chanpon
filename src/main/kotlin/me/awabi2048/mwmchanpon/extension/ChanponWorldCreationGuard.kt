package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.WorldCreationAvailability
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldCreationDecision
import me.awabi2048.myworldmanager.api.extension.WorldCreationGuard
import me.awabi2048.myworldmanager.api.extension.WorldCreationOperation
import me.awabi2048.myworldmanager.api.extension.WorldCreationRequest

class ChanponWorldCreationGuard(
    private val plugin: MWMChanponPlugin,
    private val globalStorage: ChanponGlobalStorage
) : WorldCreationGuard {
    override fun getId(): String = POLICY_ID

    override fun evaluate(request: WorldCreationRequest): WorldCreationDecision {
        val availability = globalStorage.getWorldCreationAvailability()
        val creationAllowed = when (request.operation) {
            WorldCreationOperation.NORMAL -> availability != WorldCreationAvailability.DENIED
            WorldCreationOperation.PRODUCTION -> availability == WorldCreationAvailability.PRODUCTION
        }
        if (!creationAllowed) {
            val key = if (request.operation == WorldCreationOperation.PRODUCTION) {
                "chanpon.controls.production_denied"
            } else {
                "chanpon.controls.creation_denied"
            }
            return WorldCreationDecision.deny(
                request.player?.let { CCSystem.getAPI().getI18nComponent(it, key) }
            )
        }

        val player = request.player
        val targetUuid = request.targetUuid ?: player?.uniqueId
        if (request.operation == WorldCreationOperation.NORMAL &&
            targetUuid != null &&
            (player == null || !canBypassWorldLimits(player))
        ) {
            val repository = MyWorldManagerApi.getWorldRepository()
            val ownerCount = repository?.findByOwner(targetUuid)?.size ?: 0
            val maxWorlds = plugin.config.getInt("creation.max_worlds_per_player", 50)
            if (ownerCount >= maxWorlds) {
                return WorldCreationDecision.deny(
                    request.actor?.let {
                        CCSystem.getAPI().getI18nComponent(
                        it as? org.bukkit.entity.Player,
                        "myworld.gui.creation.limit_reached",
                        mapOf("current" to ownerCount, "max" to maxWorlds)
                        )
                    }
                )
            }
        }
        return WorldCreationDecision.allow()
    }

    private fun canBypassWorldLimits(player: org.bukkit.entity.Player): Boolean =
        player.hasPermission("myworldmanager.admin.bypass_world_limits") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("craftercrossing.test")

    companion object {
        const val POLICY_ID = "mwmchanpon.world_creation"
    }
}
