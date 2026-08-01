package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.MenuReversibleContract
import java.util.UUID
import me.awabi2048.mwmchanpon.ui.chanponGestureAction
import me.awabi2048.myworldmanager.service.MwmReversibleContracts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponReversibleActionClassificationTest {
    @Test
    fun `reversible action classification has a provider for every state aggregate`() {
        assertEquals(emptyList<String>(), ChanponReversibleActionClassification.validate())
        assertEquals(22, ChanponReversibleActionClassification.entries.size)
        val providerIds = ChanponReversibleActionClassification.entries
            .filter { it.kind == ChanponReversibleActionClassification.Kind.STATE_AGGREGATE }
            .mapNotNull { it.providerId }
            .toSet()
        assertTrue(
            providerIds.filter { it.startsWith("mwm-chanpon:") }.all { it in ChanponReversibleContracts.providerIds },
            "Chanpon state aggregateに未登録providerがあります: $providerIds",
        )
    }

    @Test
    fun `list filters and export selection remain route-only`() {
        val routeOnly = ChanponReversibleActionClassification.entries
            .filter { it.kind == ChanponReversibleActionClassification.Kind.ROUTE_ONLY }
            .map { it.operation }
            .toSet()
        assertEquals(
            setOf(
                "admin-world-list-filter",
                "admin-world-list-sort",
                "submission-list-filter",
                "submission-list-sort",
                "export-selection-toggle",
                "export-selection-controls",
            ),
            routeOnly,
        )
    }

    @Test
    fun `every classified operation produces its declared safety and restore contract through the production factory`() {
        val generated = ChanponReversibleActionClassification.Operation.entries.associateWith { operation ->
            val contract = contractFor(operation)
            val safety = when (operation.kind) {
                ChanponReversibleActionClassification.Kind.STATE_AGGREGATE -> MenuActionSafety.REVERSIBLE
                ChanponReversibleActionClassification.Kind.ROUTE_ONLY -> MenuActionSafety.NAVIGATION_ONLY
            }
            chanponGestureAction(
                actionId = operation.key,
                gesture = MenuGesture.ANY,
                label = operation.key,
                safety = safety,
                reversibleContract = contract,
                classification = operation,
            )
        }

        assertEquals(ChanponReversibleActionClassification.Operation.entries.toSet(), generated.keys)
        generated.forEach { (operation, action) ->
            assertEquals(
                if (operation.kind == ChanponReversibleActionClassification.Kind.STATE_AGGREGATE) {
                    MenuActionSafety.REVERSIBLE
                } else {
                    MenuActionSafety.NAVIGATION_ONLY
                },
                action.safety,
            )
            assertEquals(operation.providerId, action.reversibleContract?.providerId)
            if (operation.kind == ChanponReversibleActionClassification.Kind.STATE_AGGREGATE) {
                assertTrue(action.reversibleContract!!.arguments.isNotEmpty() || operation.providerId!!.startsWith("myworldmanager:"))
            }
        }
    }

    @Test
    fun `MWM player world classifications retain provider and argument verification`() {
        val references = listOf(
            MwmProviderReference(
                ChanponReversibleActionClassification.Operation.CURRENT_PLAYER_WORLD_DISPLAY_ORDER,
                MwmReversibleContracts.displayOrder(),
            ),
            MwmProviderReference(
                ChanponReversibleActionClassification.Operation.AWAY_PLAYER_WORLD_DISPLAY_ORDER,
                MwmReversibleContracts.displayOrder(),
            ),
            MwmProviderReference(
                ChanponReversibleActionClassification.Operation.STANDARD_CREATION_SESSION,
                MwmReversibleContracts.creationSession(),
            ),
        )
        assertEquals(
            setOf(
                ChanponReversibleActionClassification.Operation.CURRENT_PLAYER_WORLD_DISPLAY_ORDER,
                ChanponReversibleActionClassification.Operation.AWAY_PLAYER_WORLD_DISPLAY_ORDER,
                ChanponReversibleActionClassification.Operation.STANDARD_CREATION_SESSION,
            ),
            references.map(MwmProviderReference::operation).toSet(),
        )
        references.forEach { reference ->
            assertEquals(reference.operation.providerId, reference.contract.providerId)
            ChanponReversibleActionClassification.validateActionContract(
                reference.operation,
                reference.contract.providerId,
                MenuActionSafety.REVERSIBLE,
            )
        }
        assertEquals(emptyMap<String, String>(), references[0].contract.arguments)
        assertEquals(emptyMap<String, String>(), references[1].contract.arguments)
        assertEquals(mapOf("operation" to "start"), references[2].contract.arguments)

    }

    private fun contractFor(operation: ChanponReversibleActionClassification.Operation): MenuReversibleContract? {
        val world = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val player = UUID.fromString("00000000-0000-0000-0000-000000000002")
        return when (operation) {
            ChanponReversibleActionClassification.Operation.AUTOMATION_TOGGLE ->
                ChanponReversibleContracts.automation("backup.auto_backup.enabled")
            ChanponReversibleActionClassification.Operation.BUILDING_TOGGLE ->
                ChanponReversibleContracts.adminWorldControls("building")
            ChanponReversibleActionClassification.Operation.CREATION_AVAILABILITY_CYCLE ->
                ChanponReversibleContracts.adminWorldControls("creation")
            ChanponReversibleActionClassification.Operation.SPAWN_WORLD_SET ->
                ChanponReversibleContracts.adminWorldControls("spawn", world.toString())
            ChanponReversibleActionClassification.Operation.ENVIRONMENT_DIRECT_SETTING_CYCLE,
            ChanponReversibleActionClassification.Operation.ENVIRONMENT_PROTECTION_CYCLE ->
                ChanponReversibleContracts.environment(world)
            ChanponReversibleActionClassification.Operation.PUBLICATION_CYCLE -> ChanponReversibleContracts.publication()
            ChanponReversibleActionClassification.Operation.WORLD_NOTIFICATION_TOGGLE -> ChanponReversibleContracts.notification()
            ChanponReversibleActionClassification.Operation.SUBMISSION_CLOSURE ->
                ChanponReversibleContracts.submission("global", "close")
            ChanponReversibleActionClassification.Operation.SUBMISSION_COMPLETION ->
                ChanponReversibleContracts.submission(world.toString(), "complete")
            ChanponReversibleActionClassification.Operation.TOOL_ENABLE_TOGGLE ->
                ChanponReversibleContracts.toolPermission(world, player, "toggle")
            ChanponReversibleActionClassification.Operation.MEMBER_ROLE_TOGGLE ->
                ChanponReversibleContracts.toolPermission(world, player, "role")
            ChanponReversibleActionClassification.Operation.CREATION_PRODUCTION_SELECTION ->
                ChanponReversibleContracts.creationProductionSelection()
            ChanponReversibleActionClassification.Operation.CURRENT_PLAYER_WORLD_DISPLAY_ORDER,
            ChanponReversibleActionClassification.Operation.AWAY_PLAYER_WORLD_DISPLAY_ORDER -> MwmReversibleContracts.displayOrder()
            ChanponReversibleActionClassification.Operation.STANDARD_CREATION_SESSION -> MwmReversibleContracts.creationSession()
            ChanponReversibleActionClassification.Operation.ADMIN_WORLD_LIST_FILTER,
            ChanponReversibleActionClassification.Operation.ADMIN_WORLD_LIST_SORT,
            ChanponReversibleActionClassification.Operation.SUBMISSION_LIST_FILTER,
            ChanponReversibleActionClassification.Operation.SUBMISSION_LIST_SORT,
            ChanponReversibleActionClassification.Operation.EXPORT_SELECTION_TOGGLE,
            ChanponReversibleActionClassification.Operation.EXPORT_SELECTION_CONTROLS -> null
        }
    }

    private data class MwmProviderReference(
        val operation: ChanponReversibleActionClassification.Operation,
        val contract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract,
    )
}
