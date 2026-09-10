package me.awabi2048.mwmchanpon.storage

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
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

    @Test
    fun `外部配置のchanpon_ymlはファイル名がUUIDでなくても現行形式なら通過する`() {
        // バックアップ内は worlds/<uuid>/chanpon.yml、アーカイブ内は worlds/<uuid>/chanpon_data.yml であり、
        // ファイル名でのUUID検証を要求すると正規の復元元がすべて拒否される。
        val worldUuid = UUID.randomUUID()
        val file = writeExternalWorldFile("chanpon.yml", currentExternalContent())

        assertDoesNotThrow {
            ChanponWorldDataMigrationParticipant.validateCurrentExternalFile(file, worldUuid)
        }
    }

    @Test
    fun `外部配置の旧形式は拒否される`() {
        val worldUuid = UUID.randomUUID()
        val legacy = writeExternalWorldFile(
            "chanpon.yml",
            """
            schema_version: 0
            production: true
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ChanponWorldDataMigrationParticipant.validateCurrentExternalFile(legacy, worldUuid)
        }
    }

    @Test
    fun `存在しない外部復元元は拒否される`() {
        val missing = temporaryDirectory.resolve("chanpon.yml").toFile()

        assertThrows(IllegalArgumentException::class.java) {
            ChanponWorldDataMigrationParticipant.validateCurrentExternalFile(missing, UUID.randomUUID())
        }
    }

    private fun writeExternalWorldFile(name: String, content: String): File {
        val file = temporaryDirectory.resolve(name).toFile()
        file.writeText(content)
        return file
    }

    private fun currentExternalContent(): String =
        """
        schema_version: 1
        production: true
        protection:
          mode: members
        submission:
          state: unsubmitted
        """.trimIndent()

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
