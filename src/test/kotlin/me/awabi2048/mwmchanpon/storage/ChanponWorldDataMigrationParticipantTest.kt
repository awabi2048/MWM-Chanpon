package me.awabi2048.mwmchanpon.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration

class ChanponWorldDataMigrationParticipantTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `legacy password protection mode is pending instead of invalid`() {
        val file = writeLegacyPasswordWorldFile()

        val inspection = ChanponWorldDataMigrationParticipant.inspectFile(file)

        assertEquals(ChanponWorldFileState.PENDING, inspection.state)
    }

    @Test
    fun `legacy password protection mode is converted to members`() {
        val file = writeLegacyPasswordWorldFile()

        ChanponWorldDataMigrationParticipant.migrateFile(file, Logger.getLogger("test"))

        val migrated = YamlConfiguration.loadConfiguration(file)
        assertEquals("members", migrated.getString("protection.mode"))
        assertEquals(ChanponWorldDataMigrationParticipant.CURRENT_SCHEMA_VERSION, migrated.getInt("schema_version"))
        // 参加パスワードは建築許可モードとは独立しているため、移行後も保持します。
        assertEquals("legacy-secret", migrated.getString("protection.password"))
    }

    private fun writeLegacyPasswordWorldFile(): File {
        val file = temporaryDirectory.resolve("44eaa7a4-b53c-439c-9ae4-cc8350e3138e.yml").toFile()
        file.writeText(
            """
            schema_version: 1
            protection:
              mode: password
              password: legacy-secret
            """.trimIndent(),
        )
        return file
    }
}
