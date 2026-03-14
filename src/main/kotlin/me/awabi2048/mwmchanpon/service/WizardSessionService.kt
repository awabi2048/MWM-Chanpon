package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.WizardSession
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WizardSessionService(private val plugin: MWMChanponPlugin) {
    private val sessions = ConcurrentHashMap<UUID, WizardSession>()

    fun startSession(adminUuid: UUID, targetPlayerUuid: UUID): WizardSession {
        val session = WizardSession(adminUuid = adminUuid, targetPlayerUuid = targetPlayerUuid)
        sessions[adminUuid] = session
        return session
    }

    fun getSession(adminUuid: UUID): WizardSession? {
        val session = sessions[adminUuid] ?: return null
        val timeout = plugin.config.getLong("creation_session.timeout_seconds", 300L)
        if (Duration.between(session.startedAt, Instant.now()).seconds > timeout) {
            sessions.remove(adminUuid)
            return null
        }
        return session
    }

    fun clear(adminUuid: UUID) {
        sessions.remove(adminUuid)
    }

    fun clearAll() {
        sessions.clear()
    }
}
