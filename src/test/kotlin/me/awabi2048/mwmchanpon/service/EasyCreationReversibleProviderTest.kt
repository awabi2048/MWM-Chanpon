package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleInteractionContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateRestoreContext
import com.awabi2048.ccsystem.api.gui.MenuRoute
import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EasyCreationReversibleProviderTest {
    @Test
    fun `easy creation provider restores only the expected flow state`() {
        val playerId = UUID.randomUUID()
        val player = player(playerId)
        val before = EasyVoidWorldCreationService.FlowState.of(
            "before",
            MenuRoute("mwm-chanpon", "previous", mapOf("selection" to "one")),
        )
        val route = MenuRoute("mwm-chanpon", "player_world", mapOf("mode" to "easy"))
        val expectedAfter = EasyVoidWorldCreationService.FlowState.of(null, route)
        val store = FakeFlowStateStore(before, expectedAfter)
        val provider = EasyCreationReversibleProvider(store)
        val capture = provider.capture(captureContext(player, route))
        val captured = assertInstanceOf(MenuReversibleProviderCaptureResult.Captured::class.java, capture)

        store.current = expectedAfter
        assertEquals(
            MenuReversibleProviderRestoreResult.Restored,
            provider.restore(restoreContext(player, route, captured.state)),
        )
        assertEquals(before, store.current)

        val second = assertInstanceOf(
            MenuReversibleProviderCaptureResult.Captured::class.java,
            provider.capture(captureContext(player, route)),
        )
        store.current = EasyVoidWorldCreationService.FlowState.of("changed", route)
        assertInstanceOf(
            MenuReversibleProviderRestoreResult.Rejected::class.java,
            provider.restore(restoreContext(player, route, second.state)),
        )
        assertEquals("changed", store.current.pendingWorldName)
    }

    @Test
    fun `flow state copies route payload at its boundary`() {
        val payload = mutableMapOf("selection" to "one")
        val state = EasyVoidWorldCreationService.FlowState.of(
            null,
            MenuRoute("mwm-chanpon", "player_world", payload),
        )
        payload["selection"] = "changed"

        val restoredRoute = requireNotNull(state.returnRoute())
        assertEquals("one", restoredRoute.payload["selection"])
        @Suppress("UNCHECKED_CAST")
        val immutablePayload = restoredRoute.payload as MutableMap<String, String>
        assertThrows(UnsupportedOperationException::class.java) {
            immutablePayload["selection"] = "invalid"
        }
    }

    private fun captureContext(player: Player, route: MenuRoute): MenuReversibleStateCaptureContext {
        val interaction = interaction(route)
        return MenuReversibleStateCaptureContext(player, route, "run", interaction)
    }

    private fun restoreContext(
        player: Player,
        route: MenuRoute,
        state: com.awabi2048.ccsystem.api.gui.MenuReversibleProviderState,
    ): MenuReversibleStateRestoreContext =
        MenuReversibleStateRestoreContext(player, route, "run", interaction(route), state)

    private fun interaction(route: MenuRoute) = MenuReversibleInteractionContext(
        slot = 0,
        click = ClickType.RIGHT,
        actionId = "easy-create",
        capabilityId = "mwm-chanpon:player-world-creation",
        contract = EasyCreationReversibleProvider.contract(),
        revision = 1L,
        arguments = emptyMap(),
        attributes = emptyMap(),
        routePayload = route.payload,
    )

    private fun player(playerId: UUID): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getUniqueId" -> playerId
            "toString" -> "test-player:$playerId"
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Short::class.javaPrimitiveType -> 0.toShort()
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0F
                Double::class.javaPrimitiveType -> 0.0
                Char::class.javaPrimitiveType -> '\u0000'
                else -> null
            }
        }
    } as Player

    private class FakeFlowStateStore(
        private val before: EasyVoidWorldCreationService.FlowState,
        private val expected: EasyVoidWorldCreationService.FlowState,
    ) : EasyCreationFlowStateStore {
        var current: EasyVoidWorldCreationService.FlowState = before

        override fun captureFlowState(playerId: UUID): EasyVoidWorldCreationService.FlowState = current

        override fun expectedFlowStateAfterStart(returnRoute: MenuRoute): EasyVoidWorldCreationService.FlowState = expected

        override fun restoreFlowState(playerId: UUID, state: EasyVoidWorldCreationService.FlowState) {
            current = state
        }
    }
}
