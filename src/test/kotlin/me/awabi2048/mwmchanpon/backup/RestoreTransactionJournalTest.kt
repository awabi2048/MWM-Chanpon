package me.awabi2048.mwmchanpon.backup

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RestoreTransactionJournalTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun persistsPhaseAndOperationMarkersAtomically() {
        val uuid = UUID.randomUUID()
        val directory = RestoreTransactionJournal.allocateDirectory(tempDir.toFile())
        val journal = RestoreTransactionJournal.create(
            directory,
            "backup-1",
            listOf(
                RestoreJournalWorld(
                    worldUuid = uuid,
                    targetWorldPath = tempDir.resolve("target").toString(),
                    temporaryWorldPath = tempDir.resolve("temporary").toString(),
                    previousWorldPath = tempDir.resolve("previous").toString(),
                    previousWorldExisted = true,
                    targetMetadataPath = tempDir.resolve("mwm.yml").toString(),
                    previousMetadataPath = tempDir.resolve("mwm.previous").toString(),
                    previousMetadataExisted = true,
                    targetChanponPath = tempDir.resolve("chanpon.yml").toString(),
                    previousChanponPath = tempDir.resolve("chanpon.previous").toString(),
                    previousChanponExisted = false,
                    previousPortalsPath = tempDir.resolve("portals.previous.yml").toString(),
                    previousWorldMoveStarted = false,
                    previousWorldMoved = false,
                    targetWorldReplaceStarted = false,
                    targetWorldReplaced = false,
                    phase = RestoreJournalPhase.PREPARED,
                ),
            ),
        )

        journal.markPreviousWorldMoveStarted(uuid)
        journal.markTargetWorldReplaceStarted(uuid)
        journal.markDataCommitted(uuid)

        val restored = RestoreTransactionJournal.load(directory)!!
        val record = restored.record(uuid)!!
        assertTrue(record.previousWorldMoveStarted)
        assertTrue(record.targetWorldReplaceStarted)
        assertEquals(RestoreJournalPhase.DATA_COMMITTED, record.phase)
        assertFalse(restored.areSnapshotsReady())
        journal.markSnapshotsReady()
        assertTrue(RestoreTransactionJournal.load(directory)!!.areSnapshotsReady())
        assertFalse(Files.exists(directory.toPath().resolve("journal.yml.tmp")))
    }

    @Test
    fun completedJournalCanBeRemovedAsOneRecoveryUnit() {
        val directory = RestoreTransactionJournal.allocateDirectory(tempDir.toFile())
        val journal = RestoreTransactionJournal.create(
            directory,
            "backup-2",
            listOf(
                RestoreJournalWorld(
                    worldUuid = UUID.randomUUID(),
                    targetWorldPath = tempDir.resolve("target").toString(),
                    temporaryWorldPath = tempDir.resolve("temporary").toString(),
                    previousWorldPath = tempDir.resolve("previous").toString(),
                    previousWorldExisted = false,
                    targetMetadataPath = tempDir.resolve("mwm.yml").toString(),
                    previousMetadataPath = tempDir.resolve("mwm.previous").toString(),
                    previousMetadataExisted = false,
                    targetChanponPath = tempDir.resolve("chanpon.yml").toString(),
                    previousChanponPath = tempDir.resolve("chanpon.previous").toString(),
                    previousChanponExisted = false,
                    previousPortalsPath = tempDir.resolve("portals.previous.yml").toString(),
                    previousWorldMoveStarted = false,
                    previousWorldMoved = false,
                    targetWorldReplaceStarted = false,
                    targetWorldReplaced = false,
                    phase = RestoreJournalPhase.PREPARED,
                ),
            ),
        )

        journal.delete()

        assertFalse(Files.exists(directory.toPath()))
    }
}
