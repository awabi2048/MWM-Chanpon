package me.awabi2048.mwmchanpon.tool

import java.util.UUID
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.model.WorldData

class ToolPermissionController(
    private val storage: ChanponWorldStorage,
    private val luckPermsService: LuckPermsToolPermissionService,
    private val definitions: List<ToolPermissionDefinition>,
    private val autoRollbackLocked: () -> Boolean = { false }
) {
    fun isEnabled(worldData: WorldData, playerUuid: UUID, toolId: String): Boolean {
        if (isSubmissionLocked(worldData.uuid)) return false
        return storage.getToolEnabledState(worldData.uuid, playerUuid, toolId)
            ?: (playerUuid == worldData.owner)
    }

    fun setEnabled(worldData: WorldData, playerUuid: UUID, toolId: String, enabled: Boolean) {
        storage.setToolEnabled(worldData.uuid, playerUuid, toolId, if (isSubmissionLocked(worldData.uuid)) false else enabled)
        val worldName = worldData.customWorldName ?: "my_world.${worldData.uuid}"
        val enabledSet = getEffectiveEnabledTools(worldData, playerUuid)
        luckPermsService.syncPlayer(playerUuid, worldName, definitions, enabledSet)
    }

    fun syncWorld(worldData: WorldData) {
        val worldName = worldData.customWorldName ?: "my_world.${worldData.uuid}"
        val participants = linkedSetOf<UUID>()
        participants.add(worldData.owner)
        participants.addAll(worldData.members)
        participants.forEach { playerUuid ->
            val enabled = getEffectiveEnabledTools(worldData, playerUuid)
            luckPermsService.syncPlayer(playerUuid, worldName, definitions, enabled)
        }
    }

    fun cleanupPlayer(worldData: WorldData, playerUuid: UUID) {
        val worldName = worldData.customWorldName ?: "my_world.${worldData.uuid}"
        storage.removeMember(worldData.uuid, playerUuid)
        luckPermsService.removePermissions(playerUuid, worldName, definitions)
    }

    fun cleanupPlayerPermissions(worldData: WorldData, playerUuid: UUID) {
        val worldName = worldData.customWorldName ?: "my_world.${worldData.uuid}"
        luckPermsService.removePermissions(playerUuid, worldName, definitions)
    }

    fun getMemberToolStates(worldData: WorldData, playerUuid: UUID): Map<String, Boolean> {
        if (isSubmissionLocked(worldData.uuid)) {
            return definitions.associate { it.id to false }
        }
        val stored = storage.getMemberToolStates(worldData.uuid, playerUuid)
        return definitions.associate { definition ->
            definition.id to (stored[definition.id] ?: (playerUuid == worldData.owner))
        }
    }

    private fun getEffectiveEnabledTools(worldData: WorldData, playerUuid: UUID): Set<String> {
        if (isSubmissionLocked(worldData.uuid)) return emptySet()
        return definitions
            .filter { isEnabled(worldData, playerUuid, it.id) }
            .map { it.id }
            .toSet()
    }

    private fun isSubmissionLocked(worldUuid: UUID): Boolean {
        if (storage.isProductionWorld(worldUuid) && autoRollbackLocked()) return true
        return try {
            when (storage.getSubmissionState(worldUuid)) {
                SubmissionState.SUBMITTED, SubmissionState.COMPLETED -> true
                SubmissionState.UNSUBMITTED -> false
            }
        } catch (ex: IllegalStateException) {
            false
        }
    }
}
