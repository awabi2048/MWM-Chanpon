package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ZipArchiveExtractorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun extractsFilesInsideDestination() {
        val zip = createZip("world/data.txt" to "payload")
        val destination = tempDir.resolve("output")

        ZipArchiveExtractor.extract(zip.toFile(), destination)

        assertEquals("payload", Files.readString(destination.resolve("world/data.txt")))
    }

    @Test
    fun rejectsEntryOutsideDestination() {
        val zip = createZip("../escaped.txt" to "payload")
        val destination = tempDir.resolve("output")

        val error = assertThrows(IllegalStateException::class.java) {
            ZipArchiveExtractor.extract(zip.toFile(), destination)
        }

        assertEquals("archive_entry_outside_root:../escaped.txt", error.message)
        assertFalse(Files.exists(tempDir.resolve("escaped.txt")))
    }

    private fun createZip(vararg entries: Pair<String, String>): Path {
        val zip = tempDir.resolve("archive.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }
}
