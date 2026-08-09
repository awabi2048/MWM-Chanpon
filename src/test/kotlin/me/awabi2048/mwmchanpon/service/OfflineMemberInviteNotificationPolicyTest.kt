package me.awabi2048.mwmchanpon.service

import java.util.UUID
import me.awabi2048.myworldmanager.api.extension.PendingOfflineMemberInvite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OfflineMemberInviteNotificationPolicyTest {
    @Test
    fun `selects only unique unsent invites whose world name is available`() {
        val sentId = UUID.randomUUID()
        val missingWorldInviteId = UUID.randomUUID()
        val duplicateId = UUID.randomUUID()
        val visibleId = UUID.randomUUID()
        val worldUuid = UUID.randomUUID()
        val pending = listOf(
            PendingOfflineMemberInvite(sentId, worldUuid, 1L),
            PendingOfflineMemberInvite(missingWorldInviteId, UUID.randomUUID(), 2L),
            PendingOfflineMemberInvite(duplicateId, worldUuid, 3L),
            PendingOfflineMemberInvite(duplicateId, worldUuid, 4L),
            PendingOfflineMemberInvite(visibleId, worldUuid, 5L),
        )

        val result = OfflineMemberInviteNotificationPolicy.selectUnnotified(
            pendingInvites = pending,
            notifiedInviteIds = setOf(sentId),
            worldNameResolver = { uuid -> if (uuid == worldUuid) "招待ワールド" else null },
        )

        assertEquals(
            listOf(
                OfflineMemberInviteNotification(duplicateId, "招待ワールド"),
                OfflineMemberInviteNotification(visibleId, "招待ワールド"),
            ),
            result,
        )
    }
}
