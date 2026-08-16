package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.MyworldMessagesKeys
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
        storage.hasCurrentWorldData(worldData.uuid) &&
            !worldData.isArchived &&
            worldData.sourceWorld != "CONVERT" &&
            storage.isSubmissionCompleted(worldData.uuid) &&
            visibility(worldData) == ChanponWorldVisibility.PUBLIC

    override fun canShowInVisitWorldList(viewer: Player, worldData: WorldData): Boolean =
        storage.hasCurrentWorldData(worldData.uuid) &&
            !worldData.isArchived &&
            visibility(worldData) == ChanponWorldVisibility.PUBLIC

    override fun canUseVisitEntry(viewer: Player, worldData: WorldData, isMember: Boolean): Boolean =
        storage.hasCurrentWorldData(worldData.uuid) &&
            archiveOperationGuard.canPerform(viewer, ChanponOperation.WARP) &&
            (isMember || visibility(worldData) == ChanponWorldVisibility.PUBLIC)

    override fun canUseSharedEntry(viewer: Player, worldData: WorldData, isMember: Boolean): Boolean =
        canUseVisitEntry(viewer, worldData, isMember)

    override fun canInviteToWorld(sender: Player, worldData: WorldData): Boolean =
        storage.hasCurrentWorldData(worldData.uuid) &&
            archiveOperationGuard.canPerform(sender, ChanponOperation.EDIT)

    override fun canInviteTarget(sender: Player, worldData: WorldData, target: OfflinePlayer): Boolean {
        return storage.hasCurrentWorldData(worldData.uuid) &&
            archiveOperationGuard.canPerform(sender, ChanponOperation.EDIT) &&
            (target !is Player || canCreateWorld(target))
    }

    override fun canInviteFavoriteGroup(sender: Player, worldData: WorldData, isMember: Boolean): Boolean =
        canDirectWorldWarp(sender, worldData, isMember)

    override fun canReceiveFavoriteGroupInvite(sender: Player, worldData: WorldData, target: Player): Boolean =
        storage.hasCurrentWorldData(worldData.uuid) &&
            archiveOperationGuard.canPerform(sender, ChanponOperation.EDIT)

    override fun canAcceptWorldInvite(target: Player, worldData: WorldData): Boolean =
        storage.hasCurrentWorldData(worldData.uuid) &&
            !worldData.isArchived && archiveOperationGuard.canPerform(target, ChanponOperation.WARP)

    override fun inviteToWorldDeniedMessageKey(sender: Player, worldData: WorldData): LocalizationKey<String>? =
        if (!archiveOperationGuard.canPerform(sender, ChanponOperation.EDIT)) MyworldMessagesKeys.MESSAGES_WORLD_OPERATION_LOCKED else null

    override fun inviteTargetDeniedMessageKey(sender: Player, worldData: WorldData, target: OfflinePlayer): LocalizationKey<String>? = when {
        !archiveOperationGuard.canPerform(sender, ChanponOperation.EDIT) -> MyworldMessagesKeys.MESSAGES_WORLD_OPERATION_LOCKED
        target is Player && !canCreateWorld(target) -> MyworldMessagesKeys.MESSAGES_MEMBER_INVITE_TARGET_CANNOT_CREATE
        else -> null
    }

    override fun visitDeniedMessageKey(viewer: Player, worldData: WorldData, isMember: Boolean): LocalizationKey<String>? = when {
        !archiveOperationGuard.canPerform(viewer, ChanponOperation.WARP) -> MyworldMessagesKeys.MESSAGES_WORLD_OPERATION_LOCKED
        worldData.isArchived -> MyworldMessagesKeys.MESSAGES_ARCHIVE_ACCESS_DENIED
        else -> null
    }

    override fun sharedEntryDeniedMessageKey(player: Player, worldData: WorldData, isMember: Boolean): LocalizationKey<String>? =
        visitDeniedMessageKey(player, worldData, isMember)

    override fun enterDeniedMessageKey(player: Player, worldData: WorldData, isMember: Boolean): LocalizationKey<String>? = when {
        !archiveOperationGuard.canPerform(player, ChanponOperation.WARP) -> MyworldMessagesKeys.MESSAGES_WORLD_OPERATION_LOCKED
        worldData.isArchived -> MyworldMessagesKeys.MESSAGES_ARCHIVE_ACCESS_DENIED
        else -> null
    }

    override fun meetDeniedMessageKey(viewer: Player, target: Player, worldData: WorldData, isMember: Boolean): LocalizationKey<String>? =
        if (!archiveOperationGuard.canPerform(viewer, ChanponOperation.WARP)) MyworldMessagesKeys.MESSAGES_WORLD_OPERATION_LOCKED else null

    override fun canEnterWorld(player: Player, worldData: WorldData, isMember: Boolean): Boolean {
        if (!storage.hasCurrentWorldData(worldData.uuid)) return false
        if (!archiveOperationGuard.canPerform(player, ChanponOperation.WARP)) return false
        if (worldData.isArchived) return false
        if (player.hasPermission("myworldmanager.admin") || isMember) return true
        return visibility(worldData) != ChanponWorldVisibility.PRIVATE
    }

    override fun canShowMeetTarget(viewer: Player, target: Player, worldData: WorldData, isMember: Boolean): Boolean {
        if (!storage.hasCurrentWorldData(worldData.uuid)) return false
        if (target.world.uid == viewer.world.uid) return true
        return isMember || visibility(worldData) != ChanponWorldVisibility.PRIVATE
    }

    override fun getMeetTargetAction(viewer: Player, target: Player, worldData: WorldData, isMember: Boolean): MeetTargetAction {
        if (!storage.hasCurrentWorldData(worldData.uuid)) return MeetTargetAction.DENY
        if (!archiveOperationGuard.canPerform(viewer, ChanponOperation.WARP)) return MeetTargetAction.DENY
        if (target.world.uid == viewer.world.uid || isMember) return MeetTargetAction.DIRECT
        return when (visibility(worldData)) {
            ChanponWorldVisibility.PUBLIC -> MeetTargetAction.DIRECT
            ChanponWorldVisibility.DEVELOPMENT -> MeetTargetAction.REQUEST
            ChanponWorldVisibility.PRIVATE -> MeetTargetAction.DENY
        }
    }

    override fun canDirectWorldWarp(player: Player, worldData: WorldData, isMember: Boolean): Boolean =
        storage.hasCurrentWorldData(worldData.uuid) &&
            archiveOperationGuard.canPerform(player, ChanponOperation.WARP) &&
            !worldData.isArchived &&
            (isMember || visibility(worldData) == ChanponWorldVisibility.PUBLIC)

    override fun canRequestWorldWarp(player: Player, worldData: WorldData, isMember: Boolean): Boolean =
        storage.hasCurrentWorldData(worldData.uuid) &&
            archiveOperationGuard.canPerform(player, ChanponOperation.WARP) &&
            !worldData.isArchived &&
            !isMember &&
            visibility(worldData) != ChanponWorldVisibility.PUBLIC

    private fun visibility(worldData: WorldData): ChanponWorldVisibility =
        storage.getVisibility(worldData.uuid)

    private fun canCreateWorld(player: Player): Boolean =
        player.hasPermission("myworldmanager.world.create") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin")
}
