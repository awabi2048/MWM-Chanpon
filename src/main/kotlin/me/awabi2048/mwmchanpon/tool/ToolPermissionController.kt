package me.awabi2048.mwmchanpon.tool

import java.util.UUID
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.entity.Player

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
        storage.setToolEnabled(worldData.uuid, playerUuid, toolId, storedEnabledAfterSet(worldData, enabled))
        Bukkit.getPlayer(playerUuid)?.let(::syncPlayerLocation)
    }

    /** 実際に保存される値を公開し、可逆操作のCAS期待値と通常操作を一致させます。 */
    fun storedEnabledAfterSet(worldData: WorldData, enabled: Boolean): Boolean =
        if (isSubmissionLocked(worldData.uuid)) false else enabled

    fun syncWorld(worldData: WorldData) {
        val participants = linkedSetOf<UUID>()
        participants.add(worldData.owner)
        participants.addAll(worldData.members)
        participants.addAll(worldData.moderators)
        participants.forEach { playerUuid ->
            removeLegacyScopedPermissions(worldData, playerUuid)
            removeLegacyBuilderAssignment(playerUuid, worldName(worldData))
            Bukkit.getPlayer(playerUuid)?.let(::syncPlayerLocation)
        }
        // MWM owns persistent work-group nodes; Chanpon only changes the policy decision.
        MyWorldManagerApi.syncWorldWorkPermissions(worldData.uuid)
    }

    fun cleanupPlayer(worldData: WorldData, playerUuid: UUID) {
        storage.removeMember(worldData.uuid, playerUuid)
        removeLegacyScopedPermissions(worldData, playerUuid)
        removeLegacyBuilderAssignment(playerUuid, worldName(worldData))
        Bukkit.getPlayer(playerUuid)?.let(::syncPlayerLocation)
    }

    fun cleanupPlayerPermissions(worldData: WorldData, playerUuid: UUID) {
        removeLegacyScopedPermissions(worldData, playerUuid)
        removeLegacyBuilderAssignment(playerUuid, worldName(worldData))
        Bukkit.getPlayer(playerUuid)?.let(::syncPlayerLocation)
    }

    fun cleanupLegacyWorldAssignment(playerUuid: UUID, worldName: String) {
        removeLegacyBuilderAssignment(playerUuid, worldName)
    }

    fun syncPlayerLocation(player: Player) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)
        if (worldData == null || !isParticipant(worldData, player.uniqueId) || storage.isImportedArchive(worldData.uuid)) {
            clearActivePermissions(player.uniqueId)
            return
        }

        val enabled = getEffectiveEnabledTools(worldData, player.uniqueId)
        luckPermsService.applyActiveGroups(player.uniqueId, definitions, enabled)
    }

    fun clearActivePermissions(playerUuid: UUID) {
        luckPermsService.clearActiveGroups(playerUuid, definitions)
    }

    fun clearOnlineActivePermissions() {
        Bukkit.getOnlinePlayers().forEach { player ->
            clearActivePermissions(player.uniqueId)
        }
    }

    fun syncOnlinePlayers() {
        Bukkit.getOnlinePlayers().forEach(::syncPlayerLocation)
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

    private fun removeLegacyScopedPermissions(worldData: WorldData, playerUuid: UUID) {
        luckPermsService.removeScopedPermissions(playerUuid, worldName(worldData), definitions)
    }

    private fun removeLegacyBuilderAssignment(playerUuid: UUID, worldName: String) {
        luckPermsService.removePersistentGroupAssignment(playerUuid, worldName, LEGACY_BUILDER_GROUP)
    }

    private fun isParticipant(worldData: WorldData, playerUuid: UUID): Boolean {
        return playerUuid == worldData.owner || playerUuid in worldData.members || playerUuid in worldData.moderators
    }

    private fun worldName(worldData: WorldData): String {
        return worldData.customWorldName ?: "my_world.${worldData.uuid}"
    }

    private companion object {
        const val LEGACY_BUILDER_GROUP = "mwmchanpon_builder"
    }
}
