package me.awabi2048.mwmchanpon.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveOperationPolicyTest {
    @Test
    fun `unlocked archive allows every operation`() {
        ChanponOperation.entries.forEach {
            assertTrue(ArchiveOperationPolicy.canPerform(false, it, false, false))
        }
    }

    @Test
    fun `locked archive keeps reads available and blocks mutations`() {
        assertTrue(ArchiveOperationPolicy.canPerform(true, ChanponOperation.READ, false, false))
        listOf(
            ChanponOperation.CREATE,
            ChanponOperation.EDIT,
            ChanponOperation.WARP,
            ChanponOperation.UNLOAD,
            ChanponOperation.BACKUP,
            ChanponOperation.DELETE
        ).forEach {
            assertFalse(ArchiveOperationPolicy.canPerform(true, it, true, true))
        }
    }

    @Test
    fun `locked archive limits recovery operations by permission`() {
        assertFalse(ArchiveOperationPolicy.canPerform(true, ChanponOperation.DIAGNOSE, false, false))
        assertTrue(ArchiveOperationPolicy.canPerform(true, ChanponOperation.DIAGNOSE, true, false))
        assertFalse(ArchiveOperationPolicy.canPerform(true, ChanponOperation.RESTORE, true, false))
        assertTrue(ArchiveOperationPolicy.canPerform(true, ChanponOperation.RESTORE, false, true))
    }
}
