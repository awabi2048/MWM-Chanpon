package me.awabi2048.mwmchanpon.service

import java.util.UUID
import me.awabi2048.myworldmanager.api.extension.PendingOfflineMemberInvite

data class OfflineMemberInviteNotification(
    val inviteId: UUID,
    val worldName: String,
)

/**
 * 通知対象の判定をMWMやBukkitから切り離します。
 * これにより、送信済みIDの除外、重複IDの抑止、ワールド名欠落時のスキップを同じ規則で検証できます。
 */
object OfflineMemberInviteNotificationPolicy {
    fun selectUnnotified(
        pendingInvites: Iterable<PendingOfflineMemberInvite>,
        notifiedInviteIds: Set<UUID>,
        worldNameResolver: (UUID) -> String?,
    ): List<OfflineMemberInviteNotification> {
        val seen = mutableSetOf<UUID>()
        return pendingInvites.mapNotNull { invite ->
            if (invite.id in notifiedInviteIds || !seen.add(invite.id)) return@mapNotNull null
            val worldName = worldNameResolver(invite.worldUuid)?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            OfflineMemberInviteNotification(invite.id, worldName)
        }
    }
}
