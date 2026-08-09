package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChanponWorldStorageCorruptFileTest {
    @Test
    fun `corrupt world yaml falls back to safe defaults instead of propagating parser failure`() {
        val folder = Files.createTempDirectory("chanpon-world-corrupt").toFile()
        try {
            val worldUuid = UUID.randomUUID()
            val storage = ChanponWorldStorage(folder, Logger.getAnonymousLogger())
            storage.worldFile(worldUuid).apply {
                parentFile.mkdirs()
                writeText("submission: [unterminated")
            }

            assertDoesNotThrow { storage.isImportedArchive(worldUuid) }
            assertEquals(false, storage.isImportedArchive(worldUuid))
        } finally {
            folder.deleteRecursively()
        }
    }
}
