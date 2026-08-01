package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChanponWorldStorageReversibleSnapshotTest {
    @Test
    fun `submission snapshot restores state and revision after a mutation`() {
        val folder = Files.createTempDirectory("chanpon-submission-snapshot").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val worldUuid = UUID.randomUUID()
            val actor = UUID.randomUUID()
            storage.initializeSubmissionState(worldUuid)
            val before = storage.captureSubmissionSnapshot(worldUuid)

            storage.setSubmissionState(worldUuid, SubmissionState.COMPLETED, actor, "tester", "mark_completed")
            val changed = storage.captureSubmissionSnapshot(worldUuid)
            assertEquals(before.revision + 1, changed.revision)
            storage.restoreSubmissionSnapshot(worldUuid, before)

            assertEquals(before, storage.captureSubmissionSnapshot(worldUuid))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `tool state restoration retains an absent override`() {
        val folder = Files.createTempDirectory("chanpon-tool-snapshot").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val worldUuid = UUID.randomUUID()
            val playerUuid = UUID.randomUUID()
            assertEquals(null, storage.getToolEnabledState(worldUuid, playerUuid, "worldedit"))

            storage.setToolEnabled(worldUuid, playerUuid, "worldedit", false)
            storage.restoreToolEnabledState(worldUuid, playerUuid, "worldedit", null)

            assertEquals(null, storage.getToolEnabledState(worldUuid, playerUuid, "worldedit"))
        } finally {
            folder.deleteRecursively()
        }
    }
}
