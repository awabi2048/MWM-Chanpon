package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ArchiveManifestTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun readsUuidAndWorldKeyFromSupportedManifest() {
        val uuid = UUID.randomUUID()
        val file = writeManifest("""
            schema_version: 1
            world_count: 1
            worlds:
              - uuid: $uuid
                world_key: world_alpha
        """)

        assertEquals(listOf(ArchiveManifestWorld(uuid, "world_alpha")), ArchiveManifest.read(file, 1))
    }

    @Test
    fun rejectsUnsupportedSchema() {
        val error = assertThrows(IllegalStateException::class.java) {
            ArchiveManifest.read(writeManifest("schema_version: 2\nworlds: [{}]"), 1)
        }
        assertEquals("archive_schema_unsupported", error.message)
    }

    @Test
    fun rejectsMissingWorldIdentity() {
        val error = assertThrows(IllegalStateException::class.java) {
            ArchiveManifest.read(writeManifest("schema_version: 1\nworld_count: 1\nworlds:\n  - uuid: invalid"), 1)
        }
        assertEquals("archive_world_data_missing", error.message)
    }

    private fun writeManifest(content: String) = tempDir.resolve("manifest.yml").also {
        Files.writeString(it, content.trimIndent())
    }.toFile()
}
