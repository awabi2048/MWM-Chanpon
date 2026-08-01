package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleInteractionContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderDefinition
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderRegistration
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderRegistry
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
    fun `easy creation contract explicitly binds the normalized return route attributes`() {
        val contract = EasyCreationReversibleProvider.contract()
        assertEquals(
            EasyCreationReturnRouteContract.arguments,
            contract.arguments.filterKeys { it.startsWith("return_route_") },
        )

        val registry = RecordingProviderRegistry()
        EasyCreationReversibleProvider.register(registry, FakeFlowStateStore(EasyVoidWorldCreationService.FlowState.of(null, null)))
        assertEquals(contract.providerId, requireNotNull(registry.definition(contract.providerId)).providerId)
    }

    @Test
    fun `easy creation provider restores only the expected flow state`() {
        val playerId = UUID.randomUUID()
        val player = player(playerId)
        val before = EasyVoidWorldCreationService.FlowState.of(
            "before",
            MenuRoute("mwm-chanpon", "previous", mapOf("selection" to "one")),
        )
        val route = MenuRoute("mwm-chanpon", "player_world", mapOf("mode" to "easy"))
        val store = FakeFlowStateStore(before)
        val provider = EasyCreationReversibleProvider(store)
        val capture = provider.capture(captureContext(player, route, route))
        val captured = assertInstanceOf(MenuReversibleProviderCaptureResult.Captured::class.java, capture)

        store.start(route)
        assertEquals(
            MenuReversibleProviderRestoreResult.Restored,
            provider.restore(restoreContext(player, route, route, captured.state)),
        )
        assertEquals(before, store.current)

        val second = assertInstanceOf(
            MenuReversibleProviderCaptureResult.Captured::class.java,
            provider.capture(captureContext(player, route, route)),
        )
        store.current = EasyVoidWorldCreationService.FlowState.of("changed", route)
        assertInstanceOf(
            MenuReversibleProviderRestoreResult.Rejected::class.java,
            provider.restore(restoreContext(player, route, route, second.state)),
        )
        assertEquals("changed", store.current.pendingWorldName)
    }

    @Test
    fun `easy creation uses the explicitly supplied normalized return route instead of the current route`() {
        val playerId = UUID.randomUUID()
        val player = player(playerId)
        val before = EasyVoidWorldCreationService.FlowState.of(null, null)
        val rawRoute = MenuRoute("mwm-chanpon", "player-worlds", mapOf("page" to "-1", "target" to "player"))
        val normalizedReturnRoute = MenuRoute("mwm-chanpon", "player-worlds", mapOf("page" to "0", "target" to "player"))
        val store = FakeFlowStateStore(before)
        val provider = EasyCreationReversibleProvider(store)

        val captured = assertInstanceOf(
            MenuReversibleProviderCaptureResult.Captured::class.java,
            provider.capture(captureContext(player, rawRoute, normalizedReturnRoute)),
        )
        store.start(normalizedReturnRoute)
        assertEquals(
            MenuReversibleProviderRestoreResult.Restored,
            provider.restore(restoreContext(player, rawRoute, normalizedReturnRoute, captured.state)),
        )
        assertEquals(before, store.current)

        val stale = assertInstanceOf(
            MenuReversibleProviderCaptureResult.Captured::class.java,
            provider.capture(captureContext(player, rawRoute, normalizedReturnRoute)),
        )
        store.start(MenuRoute("mwm-chanpon", "player-worlds", mapOf("page" to "8", "target" to "player")))
        assertInstanceOf(
            MenuReversibleProviderRestoreResult.Rejected::class.java,
            provider.restore(restoreContext(player, rawRoute, normalizedReturnRoute, stale.state)),
        )
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

    private fun captureContext(
        player: Player,
        currentRoute: MenuRoute,
        normalizedReturnRoute: MenuRoute,
    ): MenuReversibleStateCaptureContext {
        val interaction = interaction(currentRoute, normalizedReturnRoute)
        return MenuReversibleStateCaptureContext(player, currentRoute, "run", interaction)
    }

    private fun restoreContext(
        player: Player,
        currentRoute: MenuRoute,
        normalizedReturnRoute: MenuRoute,
        state: com.awabi2048.ccsystem.api.gui.MenuReversibleProviderState,
    ): MenuReversibleStateRestoreContext =
        MenuReversibleStateRestoreContext(
            player,
            currentRoute,
            "run",
            interaction(currentRoute, normalizedReturnRoute),
            state,
        )

    private fun interaction(currentRoute: MenuRoute, normalizedReturnRoute: MenuRoute) = MenuReversibleInteractionContext(
        slot = 0,
        click = ClickType.RIGHT,
        actionId = "easy-create",
        capabilityId = "mwm-chanpon:player-world-creation",
        contract = EasyCreationReversibleProvider.contract(),
        revision = 1L,
        arguments = emptyMap(),
        attributes = EasyCreationReturnRouteContract.attributes(normalizedReturnRoute),
        routePayload = currentRoute.payload,
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
    ) : EasyCreationFlowStateStore {
        var current: EasyVoidWorldCreationService.FlowState = before

        override fun captureFlowState(playerId: UUID): EasyVoidWorldCreationService.FlowState = current

        override fun expectedFlowStateAfterStart(returnRoute: MenuRoute): EasyVoidWorldCreationService.FlowState =
            EasyVoidWorldCreationService.FlowState.of(null, returnRoute)

        fun start(returnRoute: MenuRoute) {
            current = expectedFlowStateAfterStart(returnRoute)
        }

        override fun restoreFlowState(playerId: UUID, state: EasyVoidWorldCreationService.FlowState) {
            current = state
        }
    }

    private class RecordingProviderRegistry : MenuReversibleStateProviderRegistry {
        private val definitions = linkedMapOf<String, MenuReversibleStateProviderDefinition>()

        override fun register(definition: MenuReversibleStateProviderDefinition) {
            definitions[definition.providerId] = definition
        }

        override fun unregister(owner: String, id: String) {
            definitions.remove("$owner:$id")
        }

        override fun unregisterOwner(owner: String) {
            definitions.keys.removeIf { it.startsWith("$owner:") }
        }

        override fun definition(providerId: String): MenuReversibleStateProviderDefinition? = definitions[providerId]

        override fun registration(providerId: String): MenuReversibleStateProviderRegistration =
            error("registration is not needed by this contract test: $providerId")

        override fun addInvalidationListener(listener: (MenuReversibleStateProviderRegistration) -> Unit): AutoCloseable =
            AutoCloseable { }

        override fun definitions(): List<MenuReversibleStateProviderDefinition> = definitions.values.toList()
    }
}
