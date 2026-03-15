package me.awabi2048.mwmchanpon.model

import java.util.UUID

data class PlayerSettingsData(
    val playerUuid: UUID,
    val language: String? = null,
)
