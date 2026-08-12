package me.awabi2048.mwmchanpon.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RestorePreflightPolicyTest {
    private val validTarget =
        RestorePreflightTarget(
            includedInBackup = true,
            productionWorld = true,
            worldDataAvailable = true,
            archiveReadable = true,
            targetDirectoryWritable = true,
            worldMigrationCurrent = true,
            chanponDataCurrent = true,
        )

    @Test
    fun `実行前に判定できる復元不能条件を返す`() {
        assertEquals(
            "backup world archive is missing or unreadable",
            RestorePreflightPolicy.failureReason(
                backupLocked = false,
                coreAvailable = true,
                globalMigrationPending = false,
                targets = listOf(validTarget.copy(archiveReadable = false)),
                evacuationRequired = false,
                evacuationAvailable = false
            )
        )
        assertEquals(
            "spawn world is not configured or unavailable",
            RestorePreflightPolicy.failureReason(
                backupLocked = false,
                coreAvailable = true,
                globalMigrationPending = false,
                targets = listOf(validTarget),
                evacuationRequired = true,
                evacuationAvailable = false
            )
        )
    }

    @Test
    fun `すべての事前条件を満たせば実行可能`() {
        assertNull(
            RestorePreflightPolicy.failureReason(
                backupLocked = false,
                coreAvailable = true,
                globalMigrationPending = false,
                targets = listOf(validTarget),
                evacuationRequired = false,
                evacuationAvailable = false
            )
        )
    }

    @Test
    fun `移行保留中は復元を開始しない`() {
        assertEquals(
            "global migration is pending",
            RestorePreflightPolicy.failureReason(
                backupLocked = false,
                coreAvailable = true,
                globalMigrationPending = true,
                targets = listOf(validTarget),
                evacuationRequired = false,
                evacuationAvailable = false,
            )
        )
        assertEquals(
            "world migration is pending",
            RestorePreflightPolicy.failureReason(
                backupLocked = false,
                coreAvailable = true,
                globalMigrationPending = false,
                targets = listOf(validTarget.copy(worldMigrationCurrent = false)),
                evacuationRequired = false,
                evacuationAvailable = false,
            )
        )
    }
}
