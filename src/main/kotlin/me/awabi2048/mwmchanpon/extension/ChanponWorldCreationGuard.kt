package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponPhase
import me.awabi2048.myworldmanager.api.extension.WorldCreationDecision
import me.awabi2048.myworldmanager.api.extension.WorldCreationGuard
import me.awabi2048.myworldmanager.api.extension.WorldCreationOperation
import me.awabi2048.myworldmanager.api.extension.WorldCreationRequest

class ChanponWorldCreationGuard(
    private val globalStorage: ChanponGlobalStorage
) : WorldCreationGuard {
    override fun getId(): String = POLICY_ID

    override fun evaluate(request: WorldCreationRequest): WorldCreationDecision {
        val phase = globalStorage.getPhase()
        val allowed = when (request.operation) {
            WorldCreationOperation.NORMAL -> phase != ChanponPhase.BUILD_LOCKED
            WorldCreationOperation.PRODUCTION -> phase == ChanponPhase.PRODUCTION_ALLOWED
        }
        if (allowed) return WorldCreationDecision.allow()

        val key = if (request.operation == WorldCreationOperation.PRODUCTION) {
            "chanpon.phase.production_denied"
        } else {
            "chanpon.phase.creation_denied"
        }
        return WorldCreationDecision.deny(
            request.player?.let { CCSystem.getAPI().getI18nComponent(it, key) }
        )
    }

    companion object {
        const val POLICY_ID = "mwmchanpon.world_creation"
    }
}
