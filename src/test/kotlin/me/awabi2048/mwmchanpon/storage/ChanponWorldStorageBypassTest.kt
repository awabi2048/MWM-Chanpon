package me.awabi2048.mwmchanpon.storage

import java.nio.file.Path
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 全体停止の個人バイパスの保存契約です。
 * 迂回するのはグローバル設定だけであり、既定は無効です。
 */
class ChanponWorldStorageBypassTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `bypass defaults to disabled and persists per world member`() {
        val storage = ChanponWorldStorage(tempDir.toFile())
        val worldUuid = UUID.randomUUID()
        val playerUuid = UUID.randomUUID()

        assertFalse(storage.isGlobalBypassAllowed(worldUuid, playerUuid))
        assertNull(storage.getGlobalBypassState(worldUuid, playerUuid))

        storage.setGlobalBypassAllowed(worldUuid, playerUuid, true)

        assertTrue(storage.isGlobalBypassAllowed(worldUuid, playerUuid))
        assertEquals(true, storage.getGlobalBypassState(worldUuid, playerUuid))
        assertTrue(ChanponWorldStorage(tempDir.toFile()).isGlobalBypassAllowed(worldUuid, playerUuid))

        val config = YamlConfiguration.loadConfiguration(storage.worldFile(worldUuid))
        assertEquals(true, config.getBoolean("global_bypass.${playerUuid}", false))
    }

    @Test
    fun `restore with null removes the bypass key`() {
        val storage = ChanponWorldStorage(tempDir.toFile())
        val worldUuid = UUID.randomUUID()
        val playerUuid = UUID.randomUUID()

        storage.setGlobalBypassAllowed(worldUuid, playerUuid, true)
        storage.restoreGlobalBypassState(worldUuid, playerUuid, null)

        assertFalse(storage.isGlobalBypassAllowed(worldUuid, playerUuid))
        assertNull(storage.getGlobalBypassState(worldUuid, playerUuid))
    }

    @Test
    fun `removeMember clears bypass alongside tool permissions`() {
        val storage = ChanponWorldStorage(tempDir.toFile())
        val worldUuid = UUID.randomUUID()
        val playerUuid = UUID.randomUUID()

        storage.setToolEnabled(worldUuid, playerUuid, "worldedit", true)
        storage.setGlobalBypassAllowed(worldUuid, playerUuid, true)
        storage.removeMember(worldUuid, playerUuid)

        assertFalse(storage.isGlobalBypassAllowed(worldUuid, playerUuid))
        assertNull(storage.getGlobalBypassState(worldUuid, playerUuid))
        assertTrue(storage.getMemberToolStates(worldUuid, playerUuid).isEmpty())
    }
}
