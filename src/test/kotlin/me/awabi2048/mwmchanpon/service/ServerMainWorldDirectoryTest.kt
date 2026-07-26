package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerMainWorldDirectoryTest {
    @Test
    fun `server propertiesのlevel-nameからメインワールドを解決する`() {
        val root = Files.createTempDirectory("mwm-main-world")
        root.resolve("server.properties").writeText("level-name=main_level\n")
        root.resolve("main_level").createDirectories()
        root.resolve("main_level/level.dat").createFile()

        val resolved = ServerMainWorldDirectory.resolve(root)

        assertEquals("main_level", resolved.levelName)
        assertEquals(root.resolve("main_level").toAbsolutePath().normalize(), resolved.path)
    }

    @Test
    fun `サーバーディレクトリ外を指すlevel-nameを拒否する`() {
        val root = Files.createTempDirectory("mwm-main-world")
        root.resolve("server.properties").writeText("level-name=../outside\n")

        assertThrows<IllegalArgumentException> {
            ServerMainWorldDirectory.resolve(root)
        }
    }
}
