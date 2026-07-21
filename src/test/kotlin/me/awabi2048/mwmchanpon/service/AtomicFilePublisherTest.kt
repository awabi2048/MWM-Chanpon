package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AtomicFilePublisherTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun publishesCompletedFileAndRemovesTemporarySource() {
        val source = tempDir.resolve("archive.zip.tmp")
        val target = tempDir.resolve("archive.zip")
        Files.writeString(source, "completed")

        AtomicFilePublisher.publish(source, target)

        assertEquals("completed", Files.readString(target))
        assertFalse(Files.exists(source))
    }

    @Test
    fun replacesExistingTargetWithCompletedFile() {
        val source = tempDir.resolve("archive.zip.tmp")
        val target = tempDir.resolve("archive.zip")
        Files.writeString(source, "new")
        Files.writeString(target, "old")

        AtomicFilePublisher.publish(source, target)

        assertEquals("new", Files.readString(target))
        assertFalse(Files.exists(source))
    }
}
