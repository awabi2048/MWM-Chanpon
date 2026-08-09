package me.awabi2048.mwmchanpon.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupListMenuLayoutTest {
    @Test
    fun `WORLD mode moves info to header center and back to footer center`() {
        assertEquals(
            BackupListSlots(backSlot = 40, infoSlot = 4),
            resolveBackupListSlots(BackupListMenu.BackupListMode.WORLD, 36, 40, centeredBackSlot = 40),
        )
        assertEquals(
            BackupListSlots(backSlot = 49, infoSlot = 4),
            resolveBackupListSlots(BackupListMenu.BackupListMode.WORLD, 45, 49, centeredBackSlot = 49),
        )
    }

    @Test
    fun `ALL mode keeps the existing layout slots`() {
        val existing = BackupListSlots(backSlot = 45, infoSlot = 49)

        assertEquals(
            existing,
            resolveBackupListSlots(
                BackupListMenu.BackupListMode.ALL,
                existing.backSlot,
                existing.infoSlot,
                centeredBackSlot = existing.backSlot,
            ),
        )
    }
}
