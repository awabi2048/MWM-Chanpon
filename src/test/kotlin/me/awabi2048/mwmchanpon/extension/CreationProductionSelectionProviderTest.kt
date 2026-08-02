package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.api.gui.MenuReversibleInteractionContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateRestoreContext
import com.awabi2048.ccsystem.api.gui.MenuRoute
import java.lang.reflect.Proxy
import java.util.UUID
import me.awabi2048.mwmchanpon.restoreContextForTest
import me.awabi2048.mwmchanpon.service.ChanponReversibleContracts
import me.awabi2048.myworldmanager.api.extension.CreationConfirmationCapabilityContract
import me.awabi2048.myworldmanager.api.extension.WorldCreationDraft
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreationProductionSelectionProviderTest {
    @Test
    fun `creation production provider restores the exact draft state and rejects stale pending state`() {
        val playerId = UUID.randomUUID()
        val player = player(playerId)
        val draft = MutableDraft("test-world")
        val stateStore = CreationProductionSelectionState()
        val provider = CreationProductionSelectionProvider(stateStore)
        val capture = captureContext(player, draft)
        val captured = assertInstanceOf(MenuReversibleProviderCaptureResult.Captured::class.java, provider.capture(capture))
        val providerState = assertInstanceOf(CreationProductionSelectionProviderState::class.java, captured.state)

        assertFalse(
            providerState.snapshot.javaClass.declaredFields.any { field ->
                WorldCreationDraft::class.java.isAssignableFrom(field.type)
            },
            "snapshotに可変WorldCreationDraftを保持してはいけません",
        )

        draft.setBoolean(ProductionToggleExtension.EXTRA_KEY, true)
        stateStore.setPending(playerId, "test-world", true)
        assertEquals(
            MenuReversibleProviderRestoreResult.Restored,
            provider.restore(restoreContext(player, capture, providerState)),
        )
        assertFalse(draft.getBoolean(ProductionToggleExtension.EXTRA_KEY) ?: false)

        val stale = assertInstanceOf(MenuReversibleProviderCaptureResult.Captured::class.java, provider.capture(capture))
        val staleState = assertInstanceOf(CreationProductionSelectionProviderState::class.java, stale.state)
        draft.setBoolean(ProductionToggleExtension.EXTRA_KEY, true)
        stateStore.setPending(playerId, "test-world", false)
        assertInstanceOf(
            MenuReversibleProviderRestoreResult.Rejected::class.java,
            provider.restore(restoreContext(player, capture, staleState)),
        )
        assertTrue(draft.getBoolean(ProductionToggleExtension.EXTRA_KEY) ?: false)
    }

    private fun captureContext(player: Player, draft: WorldCreationDraft): MenuReversibleStateCaptureContext {
        val route = MenuRoute("mwm-chanpon", "creation")
        return MenuReversibleStateCaptureContext(
            player,
            route,
            "run",
            MenuReversibleInteractionContext(
                0,
                ClickType.LEFT,
                "toggle",
                ProductionToggleExtension.CREATION_CAPABILITY_ID,
                ChanponReversibleContracts.creationProductionSelection(),
                1L,
                emptyMap(),
                mapOf(CreationConfirmationCapabilityContract.DRAFT_ATTRIBUTE to draft),
                route.payload,
            ),
        )
    }

    private fun restoreContext(
        player: Player,
        capture: MenuReversibleStateCaptureContext,
        state: CreationProductionSelectionProviderState,
    ) = restoreContextForTest(player, capture, state)

    private fun player(playerId: UUID): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getUniqueId" -> playerId
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

    private class MutableDraft(override val worldName: String) : WorldCreationDraft {
        private val values = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String): Boolean? = values[key]
        override fun setBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }
}
