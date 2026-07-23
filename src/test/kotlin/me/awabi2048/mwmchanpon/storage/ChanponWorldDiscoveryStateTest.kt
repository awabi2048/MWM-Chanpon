package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponWorldDiscoveryStateTest {
    @Test
    fun `only completed submission state qualifies for discovery`() {
        val folder = Files.createTempDirectory("chanpon-world-discovery").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val worldUuid = UUID.randomUUID()
            val file = storage.worldFile(worldUuid)
            file.parentFile.mkdirs()

            YamlConfiguration().apply {
                set("submission.state", SubmissionState.SUBMITTED.storageValue)
                save(file)
            }
            assertFalse(storage.isSubmissionCompleted(worldUuid))

            YamlConfiguration().apply {
                set("submission.state", SubmissionState.COMPLETED.storageValue)
                save(file)
            }
            assertTrue(storage.isSubmissionCompleted(worldUuid))
        } finally {
            folder.deleteRecursively()
        }
    }
}
