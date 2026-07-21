package me.awabi2048.mwmchanpon.service

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

internal object ZipArchiveExtractor {
    fun extract(zipFile: File, destination: Path) {
        val root = destination.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(zipFile.toPath())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = root.resolve(entry.name).normalize()
                if (!target.startsWith(root)) {
                    throw IllegalStateException("archive_entry_outside_root:${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    FileOutputStream(target.toFile()).use(zip::copyTo)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
