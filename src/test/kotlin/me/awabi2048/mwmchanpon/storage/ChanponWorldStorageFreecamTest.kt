package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class ChanponWorldStorageFreecamTest {
    @Test
    fun `FreeCam is allowed by default and persists an explicit denial`() {
        val dataFolder = Files.createTempDirectory("mwm-chanpon-freecam-test").toFile()
        val worldUuid = UUID.randomUUID()
        val storage = ChanponWorldStorage(dataFolder)

        assertTrue(storage.isFreecamAllowed(worldUuid))
        storage.setFreecamAllowed(worldUuid, false)
        assertFalse(ChanponWorldStorage(dataFolder).isFreecamAllowed(worldUuid))
    }
}
