package me.awabi2048.mwmchanpon.api

import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StandaloneExportContributorsTest {
    private val registered = mutableListOf<StandaloneExportContributor>()

    @AfterEach
    fun cleanup() {
        registered.forEach(StandaloneExportContributors::unregister)
    }

    @Test
    fun `prepare captures every registered contributor in registration order`() {
        val calls = mutableListOf<String>()
        register { calls += "first"; PreparedStandaloneExport { } }
        register { calls += "second"; PreparedStandaloneExport { } }

        val prepared = StandaloneExportContributors.prepare(context())

        assertEquals(listOf("first", "second"), calls)
        assertEquals(2, prepared.size)
    }

    @Test
    fun `duplicate contributor registration is rejected`() {
        val contributor = StandaloneExportContributor { PreparedStandaloneExport { } }
        register(contributor)

        assertThrows(IllegalArgumentException::class.java) {
            StandaloneExportContributors.register(contributor)
        }
    }

    private fun register(contributor: StandaloneExportContributor) {
        StandaloneExportContributors.register(contributor)
        registered += contributor
    }

    private fun context() = StandaloneExportContext(
        listOf(StandaloneExportWorld(UUID.randomUUID(), "world", "minecraft:overworld", true))
    )
}
