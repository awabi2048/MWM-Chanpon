package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import me.awabi2048.myworldmanager.service.MwmReversibleContracts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponReversibleActionClassificationTest {
    @Test
    fun `reversible action classification has a provider for every state aggregate`() {
        assertEquals(emptyList<String>(), ChanponReversibleActionClassification.validate())
        assertEquals(23, ChanponReversibleActionClassification.entries.size)
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
    fun `all classified operations are used by production menu callsites`() {
        val expected = ChanponReversibleActionClassification.Operation.entries.toSet()
        val sources = Files.walk(Path.of("src", "main", "kotlin")).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .map { Files.readString(it) }
                .collect(Collectors.toList())
        }
        val actual = sources.flatMap { source ->
            Regex("ChanponReversibleActionClassification\\.Operation\\.([A-Z_]+)")
                .findAll(source)
                .map { match -> ChanponReversibleActionClassification.Operation.valueOf(match.groupValues[1]) }
        }.toSet()

        assertEquals(expected, actual, "分類台帳と実際の画面呼び出し箇所が一致しません")
        expected.forEach { operation ->
            when (operation.kind) {
                ChanponReversibleActionClassification.Kind.STATE_AGGREGATE ->
                    ChanponReversibleActionClassification.validateActionContract(
                        operation,
                        operation.providerId,
                        MenuActionSafety.REVERSIBLE,
                    )
                ChanponReversibleActionClassification.Kind.ROUTE_ONLY ->
                    ChanponReversibleActionClassification.validateActionContract(
                        operation,
                        null,
                        MenuActionSafety.NAVIGATION_ONLY,
                    )
            }
        }
    }

    @Test
    fun `MWM player world classifications retain provider and argument verification references`() {
        val references = listOf(
            MwmProviderReference(
                ChanponReversibleActionClassification.Operation.CURRENT_PLAYER_WORLD_DISPLAY_ORDER,
                MwmReversibleContracts.displayOrder(),
                "my_world_manager_plugin/MwmReversibleStateProviders.DisplayOrderProvider",
            ),
            MwmProviderReference(
                ChanponReversibleActionClassification.Operation.AWAY_PLAYER_WORLD_DISPLAY_ORDER,
                MwmReversibleContracts.displayOrder(),
                "my_world_manager_plugin/MwmReversibleStateProviders.DisplayOrderProvider",
            ),
            MwmProviderReference(
                ChanponReversibleActionClassification.Operation.STANDARD_CREATION_SESSION,
                MwmReversibleContracts.creationSession(),
                "my_world_manager_plugin/MwmReversibleStateProviders.CreationSessionProvider",
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

        val providerSource = Path.of("..", "..", "my_world_manager_plugin", "src", "main", "kotlin",
            "me", "awabi2048", "myworldmanager", "service", "MwmReversibleStateProviders.kt")
        val source = Files.readString(providerSource)
        references.map(MwmProviderReference::providerTestReference).toSet().forEach { reference ->
            assertTrue(source.contains(reference.substringAfterLast('.')))
        }
    }

    private data class MwmProviderReference(
        val operation: ChanponReversibleActionClassification.Operation,
        val contract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract,
        val providerTestReference: String,
    )
}
