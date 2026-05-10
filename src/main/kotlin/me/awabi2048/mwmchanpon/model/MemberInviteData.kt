package me.awabi2048.mwmchanpon.model

import java.util.UUID

data class MemberInviteData(
    val id: UUID,
    val worldUuid: UUID,
    val senderUuid: UUID,
    val targetUuid: UUID,
    val createdAt: Long,
)
