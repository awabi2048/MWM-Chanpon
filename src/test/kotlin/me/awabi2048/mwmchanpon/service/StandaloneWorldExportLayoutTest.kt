package me.awabi2048.mwmchanpon.service

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandaloneWorldExportLayoutTest {
    @Test
    fun `dimension ids are stable and namespace safe`() {
        val uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        assertEquals(
            "world_12345678123456789abcdef012345678",
            StandaloneWorldExportLayout.dimensionId(uuid)
        )
        assertEquals(
            "mwm_export:world_12345678123456789abcdef012345678",
            StandaloneWorldExportLayout.dimensionKey(uuid)
        )
    }

    @Test
    fun `data pack metadata targets current server format`() {
        assertTrue(StandaloneWorldExportLayout.packMetadata().contains("\"pack_format\": 101"))
        assertTrue(StandaloneWorldExportLayout.dimensionDefinition().contains("\"type\": \"minecraft:overworld\""))
    }
}
