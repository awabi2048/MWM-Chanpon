package me.awabi2048.mwmchanpon.service

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
}
