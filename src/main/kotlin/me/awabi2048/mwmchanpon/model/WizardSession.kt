package me.awabi2048.mwmchanpon.model

import java.time.Instant
import java.util.UUID

data class WizardSession(
    val adminUuid: UUID,
    val targetPlayerUuid: UUID,
    val startedAt: Instant = Instant.now(),
    var creationMethod: CreationMethod? = null,
    var templateKey: String? = null,
    var seedText: String? = null,
    var worldName: String? = null,
)
