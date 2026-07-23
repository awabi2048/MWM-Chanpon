package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.service.ArchiveOperationGuard
import me.awabi2048.mwmchanpon.service.ChanponOperation
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.extension.MeetTargetAction
import me.awabi2048.myworldmanager.api.extension.WorldAccessPolicy
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

class ChanponWorldAccessPolicy(
    private val archiveOperationGuard: ArchiveOperationGuard,
    private val storage: ChanponWorldStorage
) : WorldAccessPolicy {
    override fun getId(): String = "mwmchanpon.world_access"

    override fun canShowInDiscovery(viewer: Player, worldData: WorldData): Boolean =
        !worldData.isArchived &&
            worldData.sourceWorld != "CONVERT" &&
            storage.isSubmissionCompleted(worldData.uuid) &&
            visibility(worldData) == ChanponWorldVisibility.PUBLIC

    override fun canShowInVisitWorldList(viewer: Player, worldData: WorldData): Boolean =
        !worldData.isArchived &&
            visibility(worldData) == ChanponWorldVisibility.PUBLIC

    override fun canUseVisitEntry(viewer: Player, worldData: WorldData, isMember: Boolean): Boolean =
        archiveOperationGuard.canPerform(viewer, ChanponOperation.WARP) &&
            (isMember || visibility(worldData) == ChanponWorldVisibility.PUBLIC)

    override fun canUseSharedEntry(viewer: Player, worldData: WorldData, isMember: Boolean): Boolean =
        canUseVisitEntry(viewer, worldData, isMember)

    override fun canInviteToWorld(sender: Player, worldData: WorldData): Boolean =
        archiveOperationGuard.canPerform(sender, ChanponOperation.EDIT)

    override fun canInviteTarget(sender: Player, worldData: WorldData, target: OfflinePlayer): Boolean {
        val targetPlayer = target as? Player ?: return false
        return archiveOperationGuard.canPerform(sender, ChanponOperation.EDIT) &&
            canCreateWorld(targetPlayer)
    }

    override fun canEnterWorld(player: Player, worldData: WorldData, isMember: Boolean): Boolean {
        if (!archiveOperationGuard.canPerform(player, ChanponOperation.WARP)) return false
        if (worldData.isArchived) return false
        if (player.hasPermission("myworldmanager.admin") || isMember) return true
        return visibility(worldData) != ChanponWorldVisibility.PRIVATE
    }

    override fun canShowMeetTarget(viewer: Player, target: Player, worldData: WorldData, isMember: Boolean): Boolean {
        if (target.world.uid == viewer.world.uid) return true
        return isMember || visibility(worldData) != ChanponWorldVisibility.PRIVATE
    }

    override fun getMeetTargetAction(viewer: Player, target: Player, worldData: WorldData, isMember: Boolean): MeetTargetAction {
        if (!archiveOperationGuard.canPerform(viewer, ChanponOperation.WARP)) return MeetTargetAction.DENY
        if (target.world.uid == viewer.world.uid || isMember) return MeetTargetAction.DIRECT
        return when (visibility(worldData)) {
            ChanponWorldVisibility.PUBLIC -> MeetTargetAction.DIRECT
            ChanponWorldVisibility.DEVELOPMENT -> MeetTargetAction.REQUEST
            ChanponWorldVisibility.PRIVATE -> MeetTargetAction.DENY
        }
    }

    override fun canDirectWorldWarp(player: Player, worldData: WorldData, isMember: Boolean): Boolean =
        archiveOperationGuard.canPerform(player, ChanponOperation.WARP) &&
            !worldData.isArchived &&
            (isMember || visibility(worldData) == ChanponWorldVisibility.PUBLIC)

    override fun canRequestWorldWarp(player: Player, worldData: WorldData, isMember: Boolean): Boolean =
        archiveOperationGuard.canPerform(player, ChanponOperation.WARP) &&
            !worldData.isArchived &&
            !isMember &&
            visibility(worldData) != ChanponWorldVisibility.PUBLIC

    private fun visibility(worldData: WorldData): ChanponWorldVisibility =
        storage.getVisibility(worldData.uuid)

    private fun canCreateWorld(player: Player): Boolean =
        player.hasPermission("myworldmanager.world.create") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin") ||
            player.hasPermission("craftercrossing.test")
}
