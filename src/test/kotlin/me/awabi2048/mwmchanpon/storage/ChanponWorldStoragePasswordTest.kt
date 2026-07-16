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

class ChanponWorldStoragePasswordTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `protection password is stored as plaintext and matched exactly`() {
        val storage = ChanponWorldStorage(tempDir.toFile())
        val worldUuid = UUID.randomUUID()

        storage.setProtectionPassword(worldUuid, "sample-password")

        assertEquals("sample-password", storage.getProtectionPassword(worldUuid))
        assertTrue(storage.hasProtectionPassword(worldUuid))
        assertTrue(storage.matchesProtectionPassword(worldUuid, "sample-password"))
        assertFalse(storage.matchesProtectionPassword(worldUuid, "different"))

        val config = YamlConfiguration.loadConfiguration(storage.worldFile(worldUuid))
        assertEquals("sample-password", config.getString("protection.password"))
        assertNull(config.get("protection.password_hash"))
    }
}
