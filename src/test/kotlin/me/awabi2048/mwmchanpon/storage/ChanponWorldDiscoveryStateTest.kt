package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `latest valid completion timestamp survives malformed history entries`() {
        val folder = Files.createTempDirectory("chanpon-world-completion-time").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val worldUuid = UUID.randomUUID()
            val file = storage.worldFile(worldUuid)
            file.parentFile.mkdirs()
            YamlConfiguration().apply {
                set("submission.state", SubmissionState.COMPLETED.storageValue)
                set(
                    "submission.history",
                    listOf(
                        mapOf("action" to "mark_completed", "at" to "not-a-date"),
                        mapOf("action" to "mark_completed", "at" to "2026-08-10T12:00:00"),
                        mapOf("action" to "unmark_completed", "at" to "2026-08-10T13:00:00"),
                        mapOf("action" to "mark_completed", "at" to "2026-08-11T09:00:00"),
                    ),
                )
                save(file)
            }

            assertEquals(
                java.time.LocalDateTime.parse("2026-08-11T09:00:00"),
                storage.getCompletedAt(worldUuid),
            )
        } finally {
            folder.deleteRecursively()
        }
    }
}
