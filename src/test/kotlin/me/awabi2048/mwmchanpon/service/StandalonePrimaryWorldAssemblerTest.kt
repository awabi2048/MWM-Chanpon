package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class StandalonePrimaryWorldAssemblerTest {
    @Test
    fun `地形は代表ワールドから共有データはサーバーメインワールドからコピーする`() {
        val root = Files.createTempDirectory("mwm-primary-world-assembler")
        val representative = root.resolve("representative").createDirectories()
        val serverMain = root.resolve("server-main").createDirectories()
        val output = root.resolve("output")

        representative.resolve("region").createDirectories()
        representative.resolve("region/r.0.0.mca").writeText("representative-terrain")
        representative.resolve("level.dat").writeText("representative-level")
        representative.resolve("data").createDirectories()
        representative.resolve("data/map_0.dat").writeText("representative-map")
        representative.resolve("session.lock").writeText("lock")

        serverMain.resolve("level.dat").writeText("server-main-level")
        serverMain.resolve("level.dat_old").writeText("server-main-old-level")
        serverMain.resolve("data").createDirectories()
        serverMain.resolve("data/map_0.dat").writeText("server-main-map")

        StandalonePrimaryWorldAssembler.copy(representative, serverMain, output)

        assertEquals("representative-terrain", output.resolve("region/r.0.0.mca").readText())
        assertEquals("server-main-level", output.resolve("level.dat").readText())
        assertEquals("server-main-old-level", output.resolve("level.dat_old").readText())
        assertEquals("server-main-map", output.resolve("data/map_0.dat").readText())
        assertFalse(Files.exists(output.resolve("session.lock")))
    }
}
