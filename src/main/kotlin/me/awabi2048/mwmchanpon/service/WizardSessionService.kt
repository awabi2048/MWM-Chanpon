package me.awabi2048.mwmchanpon.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

data class WizardSession(
    val adminUuid: UUID,
    val targetPlayerName: String,
    var targetPlayerUuid: UUID? = null,
    var selectedTemplateId: String? = null,
    var worldName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class WizardStep {
    TEMPLATE_SELECT,
    NAME_INPUT,
    CONFIRM
}

class WizardSessionService(
    private val timeoutSeconds: Long,
    private val logger: Logger
) {
    private val sessions = ConcurrentHashMap<UUID, WizardSession>()

    fun createSession(adminUuid: UUID, targetPlayerName: String): WizardSession {
        val session = WizardSession(adminUuid = adminUuid, targetPlayerName = targetPlayerName)
        sessions[adminUuid] = session
        return session
    }

    fun getSession(adminUuid: UUID): WizardSession? {
        val session = sessions[adminUuid]
        if (session != null && isExpired(session)) {
            sessions.remove(adminUuid)
            return null
        }
        return session
    }

    fun removeSession(adminUuid: UUID) {
        sessions.remove(adminUuid)
    }

    fun clearAll() {
        sessions.clear()
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) ->
            (now - session.createdAt) / 1000 > timeoutSeconds
        }
    }

    private fun isExpired(session: WizardSession): Boolean {
        return (System.currentTimeMillis() - session.createdAt) / 1000 > timeoutSeconds
    }
}
