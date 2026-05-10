package me.awabi2048.mwmchanpon.ui

import java.util.UUID

enum class MemberManagementEntryType {
    OWNER,
    MEMBER,
    INVITE,
}

data class MemberManagementEntry(
    val type: MemberManagementEntryType,
    val playerUuid: UUID,
    val inviteId: UUID? = null,
    val createdAt: Long? = null,
)
