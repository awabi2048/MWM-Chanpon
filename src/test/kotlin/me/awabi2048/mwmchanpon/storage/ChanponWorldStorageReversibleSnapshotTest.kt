package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponWorldStorageReversibleSnapshotTest {
    @Test
    fun `world file snapshot restores each immediate preimage across consecutive mutations`() {
        val folder = Files.createTempDirectory("chanpon-world-file-snapshot").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val worldUuid = UUID.randomUUID()
            val original = "visibility: public\nvisibility_set_at: 'original'\ntool_permissions:\n  player:\n    worldedit: false\n"
            storage.worldFile(worldUuid).apply { parentFile.mkdirs(); writeText(original) }
            val first = storage.captureWorldFileSnapshot(worldUuid)
            storage.setVisibility(worldUuid, ChanponWorldVisibility.PRIVATE)
            val second = storage.captureWorldFileSnapshot(worldUuid)
            storage.setToolEnabled(worldUuid, UUID.randomUUID(), "axiom", true)

            assertEquals(ChanponWorldStorage.WorldFileRestoreResult.RESTORED,
                storage.restoreWorldFileSnapshot(worldUuid, second) { true })
            assertTrue(storage.worldFile(worldUuid).readBytes().contentEquals(second.bytes))
            assertEquals(ChanponWorldStorage.WorldFileRestoreResult.RESTORED,
                storage.restoreWorldFileSnapshot(worldUuid, first) { true })
            assertEquals(original, storage.worldFile(worldUuid).readText())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `world file snapshot CAS failure leaves current bytes untouched`() {
        val folder = Files.createTempDirectory("chanpon-world-file-cas").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val worldUuid = UUID.randomUUID()
            storage.worldFile(worldUuid).apply { parentFile.mkdirs(); writeText("visibility: public\n") }
            val before = storage.captureWorldFileSnapshot(worldUuid)
            storage.worldFile(worldUuid).writeText("visibility: private\nmarker: concurrent\n")
            val concurrent = storage.worldFile(worldUuid).readBytes()

            assertEquals(ChanponWorldStorage.WorldFileRestoreResult.CONCURRENT_CHANGE,
                storage.restoreWorldFileSnapshot(worldUuid, before) { false })
            assertTrue(storage.worldFile(worldUuid).readBytes().contentEquals(concurrent))
            assertFalse(java.io.File(storage.worldFile(worldUuid).parentFile, "${worldUuid}.yml.restore.tmp").exists())
        } finally {
            folder.deleteRecursively()
        }
    }

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
