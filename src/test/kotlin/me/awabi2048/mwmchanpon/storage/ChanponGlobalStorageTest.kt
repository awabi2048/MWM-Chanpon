package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChanponGlobalStorageTest {
    @Test
    fun `phase defaults to production and persists as the single state`() {
        val folder = Files.createTempDirectory("chanpon-global-storage").toFile()
        try {
            val storage = ChanponGlobalStorage(folder)
            assertEquals(ChanponPhase.PRODUCTION_ALLOWED, storage.getPhase())

            storage.setPhase(ChanponPhase.BUILD_LOCKED)
            assertEquals(ChanponPhase.BUILD_LOCKED, ChanponGlobalStorage(folder).getPhase())
        } finally {
            folder.deleteRecursively()
        }
    }
}
