package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.api.extension.ReversibleWorldPublishPolicy
import me.awabi2048.myworldmanager.api.extension.WorldPublishReversibleRestoreResult
import me.awabi2048.myworldmanager.api.extension.WorldPublishReversibleState
import org.bukkit.entity.Player

class ChanponWorldPublishPolicy(
    private val storage: ChanponWorldStorage
) : ReversibleWorldPublishPolicy {
    override fun getId(): String = "mwmchanpon.world_publish"

    override fun getPublishDisplayName(
        player: Player,
        worldData: WorldData,
        defaultDisplayName: String
    ): String {
        return displayName(player, storage.getVisibility(worldData.uuid))
    }

    override fun handlesPublishCycle(worldData: WorldData): Boolean = true

    override fun cyclePublishLevel(player: Player, worldData: WorldData): Boolean {
        val next = next(storage.getVisibility(worldData.uuid))
        storage.setVisibility(worldData.uuid, next)
        player.sendMessage(
            text(player, "chanpon.publish.updated", mapOf("level" to displayName(player, next)))
        )
        return true
    }

    override fun capturePublishCycleState(player: Player, worldData: WorldData): WorldPublishReversibleState =
        VisibilityState(worldData.uuid, storage.getVisibility(worldData.uuid))

    override fun expectedPublishCycleState(
        player: Player,
        worldData: WorldData,
        before: WorldPublishReversibleState,
    ): WorldPublishReversibleState {
        val state = before as? VisibilityState
            ?: throw IllegalArgumentException("Chanpon publish state type is invalid")
        require(state.worldUuid == worldData.uuid) { "Chanpon publish state target does not match" }
        return state.copy(visibility = next(state.visibility))
    }

    override fun restorePublishCycleState(
        player: Player,
        worldData: WorldData,
        before: WorldPublishReversibleState,
        expectedAfter: WorldPublishReversibleState,
    ): WorldPublishReversibleRestoreResult {
        val beforeState = before as? VisibilityState
            ?: return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish before-state type is invalid")
        val expectedState = expectedAfter as? VisibilityState
            ?: return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish expected-state type is invalid")
        if (beforeState.worldUuid != worldData.uuid || expectedState.worldUuid != worldData.uuid) {
            return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish target changed")
        }
        if (storage.getVisibility(worldData.uuid) != expectedState.visibility) {
            return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish state changed concurrently")
        }
        storage.setVisibility(worldData.uuid, beforeState.visibility)
        return WorldPublishReversibleRestoreResult.Restored
    }

    private fun next(current: ChanponWorldVisibility): ChanponWorldVisibility = when (current) {
        ChanponWorldVisibility.PUBLIC -> ChanponWorldVisibility.DEVELOPMENT
        ChanponWorldVisibility.DEVELOPMENT -> ChanponWorldVisibility.PRIVATE
        ChanponWorldVisibility.PRIVATE -> ChanponWorldVisibility.PUBLIC
    }

    private data class VisibilityState(
        val worldUuid: java.util.UUID,
        val visibility: ChanponWorldVisibility,
    ) : WorldPublishReversibleState

    private fun displayName(player: Player?, visibility: ChanponWorldVisibility): String {
        val key = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> "public"
            ChanponWorldVisibility.DEVELOPMENT -> "development"
            ChanponWorldVisibility.PRIVATE -> "private"
        }
        return text(player, "chanpon.publish.$key")
    }

    private fun text(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, key, placeholders)
    }
}
