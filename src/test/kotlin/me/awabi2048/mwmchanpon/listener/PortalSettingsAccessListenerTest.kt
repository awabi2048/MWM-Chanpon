package me.awabi2048.mwmchanpon.listener

import java.lang.reflect.Proxy
import java.util.UUID
import me.awabi2048.myworldmanager.api.event.MwmPortalSettingsAccessEvent
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortalSettingsAccessListenerTest {
    private fun event(permissions: Set<String> = emptySet()): MwmPortalSettingsAccessEvent {
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "hasPermission" -> args!![0] in permissions
                else -> error("想定外の呼び出し: ${method.name}")
            }
        } as Player
        return MwmPortalSettingsAccessEvent(player, UUID.randomUUID())
    }

    @Test fun `全体設定に従い管理者だけが建築停止中も設定できる`() {
        val cases = listOf(
            Triple(true, emptySet<String>(), false),
            Triple(false, emptySet(), true),
            Triple(false, setOf("myworldmanager.admin"), false),
            Triple(false, setOf("mwmchanpon.admin"), false),
        )
        for ((allowed, permissions, rejected) in cases) {
            val event = event(permissions)
            PortalSettingsAccessListener { allowed }.onAccess(event)
            assertEquals(rejected, event.isCancelled)
        }
    }
}
