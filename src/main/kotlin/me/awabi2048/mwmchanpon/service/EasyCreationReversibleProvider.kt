package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleContract
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderState
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProvider
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderDefinition
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderRegistry
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateRestoreContext

class EasyCreationReversibleProvider(
    private val service: EasyCreationFlowStateStore,
) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult =
        MenuReversibleProviderCaptureResult.Captured(
            State(
                before = service.captureFlowState(context.player.uniqueId),
                expectedAfter = service.expectedFlowStateAfterStart(context.route),
            ),
        )

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = context.state as? State
            ?: return MenuReversibleProviderRestoreResult.Rejected("invalid easy creation state type")
        return try {
            val current = service.captureFlowState(context.player.uniqueId)
            if (current != state.expectedAfter) {
                return MenuReversibleProviderRestoreResult.Rejected(
                    "easy creation flow changed concurrently: expected=${state.expectedAfter}, actual=$current",
                )
            }
            service.restoreFlowState(context.player.uniqueId, state.before)
            MenuReversibleProviderRestoreResult.Restored
        } catch (exception: RuntimeException) {
            MenuReversibleProviderRestoreResult.Rejected(
                "failed to restore easy creation flow: ${exception.message ?: exception.javaClass.simpleName}",
            )
        }
    }

    private data class State(
        val before: EasyVoidWorldCreationService.FlowState,
        val expectedAfter: EasyVoidWorldCreationService.FlowState,
    ) : MenuReversibleProviderState

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val ID = "easy-creation"

        fun contract(): MenuReversibleContract = ChanponReversibleContracts.easyCreation()

        fun register(registry: MenuReversibleStateProviderRegistry, service: EasyCreationFlowStateStore) {
            registry.register(MenuReversibleStateProviderDefinition(OWNER, ID, EasyCreationReversibleProvider(service)))
        }
    }
}
