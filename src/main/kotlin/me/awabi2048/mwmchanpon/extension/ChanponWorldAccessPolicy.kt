package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.extension.MeetTargetAction
import me.awabi2048.myworldmanager.api.extension.WorldAccessPolicy
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

class ChanponWorldAccessPolicy(
    private val globalStorage: ChanponGlobalStorage,
    private val storage: ChanponWorldStorage
) : WorldAccessPolicy {
    override fun getId(): String = "mwmchanpon.world_access"

    override fun canShowInDiscovery(viewer: Player, worldData: WorldData): Boolean =
        canUseMyWorldSystem(viewer) &&
            !worldData.isArchived &&
            worldData.sourceWorld != "CONVERT" &&
            visibility(worldData) == ChanponWorldVisibility.PUBLIC

    override fun canShowInVisitWorldList(viewer: Player, worldData: WorldData): Boolean =
        canUseMyWorldSystem(viewer) &&
            !worldData.isArchived &&
            visibility(worldData) == ChanponWorldVisibility.PUBLIC

    override fun canUseVisitEntry(viewer: Player, worldData: WorldData, isMember: Boolean): Boolean =
        canUseMyWorldSystem(viewer) &&
            (isMember || visibility(worldData) == ChanponWorldVisibility.PUBLIC)

    override fun canUseSharedEntry(viewer: Player, worldData: WorldData, isMember: Boolean): Boolean =
        canUseVisitEntry(viewer, worldData, isMember)

    override fun canInviteToWorld(sender: Player, worldData: WorldData): Boolean =
        canUseMyWorldSystem(sender)

    override fun canInviteTarget(sender: Player, worldData: WorldData, target: OfflinePlayer): Boolean {
        val targetPlayer = target as? Player ?: return false
        return canUseMyWorldSystem(targetPlayer) && canCreateWorld(targetPlayer)
    }

    override fun canEnterWorld(player: Player, worldData: WorldData, isMember: Boolean): Boolean {
        if (!canUseMyWorldSystem(player)) return false
        if (worldData.isArchived) return false
        if (player.hasPermission("myworldmanager.admin") || isMember) return true
        return visibility(worldData) != ChanponWorldVisibility.PRIVATE
    }

    override fun canShowMeetTarget(viewer: Player, target: Player, worldData: WorldData, isMember: Boolean): Boolean {
        if (!canUseMyWorldSystem(viewer)) return false
        if (target.world.uid == viewer.world.uid) return true
        return isMember || visibility(worldData) != ChanponWorldVisibility.PRIVATE
    }

    override fun getMeetTargetAction(viewer: Player, target: Player, worldData: WorldData, isMember: Boolean): MeetTargetAction {
        if (!canUseMyWorldSystem(viewer)) return MeetTargetAction.DENY
        if (target.world.uid == viewer.world.uid || isMember) return MeetTargetAction.DIRECT
        return when (visibility(worldData)) {
            ChanponWorldVisibility.PUBLIC -> MeetTargetAction.DIRECT
            ChanponWorldVisibility.DEVELOPMENT -> MeetTargetAction.REQUEST
            ChanponWorldVisibility.PRIVATE -> MeetTargetAction.DENY
        }
    }

    override fun canDirectWorldWarp(player: Player, worldData: WorldData, isMember: Boolean): Boolean =
        canUseMyWorldSystem(player) &&
            !worldData.isArchived &&
            (isMember || visibility(worldData) == ChanponWorldVisibility.PUBLIC)

    override fun canRequestWorldWarp(player: Player, worldData: WorldData, isMember: Boolean): Boolean =
        canUseMyWorldSystem(player) &&
            !worldData.isArchived &&
            !isMember &&
            visibility(worldData) != ChanponWorldVisibility.PUBLIC

    private fun visibility(worldData: WorldData): ChanponWorldVisibility =
        storage.getVisibility(worldData.uuid)

    private fun canUseMyWorldSystem(player: Player): Boolean =
        !globalStorage.isArchiveLocked() || player.hasPermission("mwmchanpon.admin")

    private fun canCreateWorld(player: Player): Boolean =
        player.hasPermission("myworldmanager.command.mwm.create") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin") ||
            player.hasPermission("craftercrossing.test")
}
