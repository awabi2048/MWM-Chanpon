package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.api.localization.generated.ChanponSystemKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.api.extension.ReversibleWorldPublishPolicy
import me.awabi2048.myworldmanager.api.extension.WorldPublishReversibleRestoreResult
import me.awabi2048.myworldmanager.api.extension.WorldPublishReversibleState
import org.bukkit.entity.Player

private fun defaultPublishUpdated(player: Player, visibility: ChanponWorldVisibility) {
    player.sendMessage(
        CCSystem.getAPI().getLocalized(
            player,
            ChanponSystemKeys.CHANPON_PUBLISH_UPDATED,
            mapOf("level" to CCSystem.getAPI().getLocalized(player, publishVisibilityKey(visibility))),
        ),
    )
}

private fun publishVisibilityKey(visibility: ChanponWorldVisibility): LocalizationKey<String> = when (visibility) {
    ChanponWorldVisibility.PUBLIC -> ChanponSystemKeys.CHANPON_PUBLISH_PUBLIC
    ChanponWorldVisibility.DEVELOPMENT -> ChanponSystemKeys.CHANPON_PUBLISH_DEVELOPMENT
    ChanponWorldVisibility.PRIVATE -> ChanponSystemKeys.CHANPON_PUBLISH_PRIVATE
}

class ChanponWorldPublishPolicy(
    private val storage: ChanponWorldStorage,
    private val onPublishUpdated: (Player, ChanponWorldVisibility) -> Unit = ::defaultPublishUpdated,
) : ReversibleWorldPublishPolicy {
    private var canWriteConfiguration: () -> Boolean = { true }

    fun setConfigurationWriteGate(gate: () -> Boolean) {
        canWriteConfiguration = gate
    }
    override fun getId(): String = "mwmchanpon.world_publish"

    override fun getPublishDisplayName(
        player: Player,
        worldData: WorldData,
        defaultDisplayName: String
    ): String {
        val visibility = if (storage.hasCurrentWorldData(worldData.uuid)) {
            storage.getVisibility(worldData.uuid)
        } else {
            ChanponWorldVisibility.PRIVATE
        }
        return displayName(player, visibility)
    }

    override fun handlesPublishCycle(worldData: WorldData): Boolean =
        storage.hasCurrentWorldData(worldData.uuid)

    override fun cyclePublishLevel(player: Player, worldData: WorldData): Boolean {
        if (!canWriteConfiguration() || !storage.hasCurrentWorldData(worldData.uuid)) return false
        val next = next(storage.getVisibility(worldData.uuid))
        storage.setVisibility(worldData.uuid, next)
        onPublishUpdated(player, next)
        return true
    }

    override fun capturePublishCycleState(player: Player, worldData: WorldData): WorldPublishReversibleState =
        VisibilityState(
            worldData.uuid,
            storage.getVisibility(worldData.uuid),
            storage.captureWorldFileSnapshot(worldData.uuid),
        )

    override fun restorePublishCycleState(
        player: Player,
        worldData: WorldData,
        before: WorldPublishReversibleState,
        expectedAfter: WorldPublishReversibleState,
    ): WorldPublishReversibleRestoreResult {
        if (!canWriteConfiguration() || !storage.hasCurrentWorldData(worldData.uuid)) {
            return WorldPublishReversibleRestoreResult.Rejected(
                "Chanpon world data requires /mwm migration before restoring publish state",
            )
        }
        val beforeState = before as? VisibilityState
            ?: return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish before-state type is invalid")
        val expectedState = expectedAfter as? VisibilityState
            ?: return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish expected-state type is invalid")
        if (beforeState.worldUuid != worldData.uuid || expectedState.worldUuid != worldData.uuid) {
            return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish target changed")
        }
        val restored = storage.restoreWorldFileSnapshot(worldData.uuid, beforeState.worldFileBefore) {
            storage.getVisibility(worldData.uuid) == expectedState.visibility
        }
        if (restored == ChanponWorldStorage.WorldFileRestoreResult.CONCURRENT_CHANGE) {
            return WorldPublishReversibleRestoreResult.Rejected("Chanpon publish state changed concurrently")
        }
        storage.syncVisibility(worldData.uuid)
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
        val worldFileBefore: ChanponWorldStorage.WorldFileSnapshot,
    ) : WorldPublishReversibleState

    private fun displayName(player: Player?, visibility: ChanponWorldVisibility): String {
        val key = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> ChanponSystemKeys.CHANPON_PUBLISH_PUBLIC
            ChanponWorldVisibility.DEVELOPMENT -> ChanponSystemKeys.CHANPON_PUBLISH_DEVELOPMENT
            ChanponWorldVisibility.PRIVATE -> ChanponSystemKeys.CHANPON_PUBLISH_PRIVATE
        }
        return text(player, key)
    }

    private fun text(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getLocalized(player, key, placeholders)
    }

}
