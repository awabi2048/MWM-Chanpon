package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.MenuAvailabilityResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailabilityProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import java.lang.reflect.Proxy
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class MenuCapabilityAvailabilitySemanticsTest {
    private val player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    } as Player

    @Test
    fun `reasoned availability evaluates predicate once and retains reason`() {
        var evaluations = 0
        val availability = reasonedAvailability(
            reason = { Component.text("disabled") },
            predicate = {
                evaluations++
                false
            },
        ) as MenuCapabilityAvailabilityProvider

        val result = availability.resolve(MenuCapabilityContext(player))

        assertEquals(1, evaluations)
        assertEquals(Component.text("disabled"), assertInstanceOf(MenuAvailabilityResult.Unavailable::class.java, result).reason)
    }

    @Test
    fun `optional absence is distinct from disabled availability`() {
        val availability = optionalAvailability { false } as MenuCapabilityAvailabilityProvider

        val result = availability.resolve(MenuCapabilityContext(player))

        assertEquals(MenuAvailabilityResult.UnavailableUnknown, result)
    }
}
