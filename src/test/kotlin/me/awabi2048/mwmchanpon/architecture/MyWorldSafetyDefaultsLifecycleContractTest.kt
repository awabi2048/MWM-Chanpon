package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyWorldSafetyDefaultsLifecycleContractTest {
    @Test
    fun `startup and restore paths invoke the safety defaults service`() {
        val source = source("MWMChanponPlugin.kt")

        assertTrue("worldSafetyDefaultsService = MyWorldSafetyDefaultsService(logger, chanponStorage::isRandomTickStopped)" in source)
        assertTrue("worldSafetyDefaultsService.syncAll(it)" in source)
        assertTrue("worldSafetyDefaultsService.sync(worldData)" in source)
        assertTrue("MyWorldSafetyDefaultsListener(this, worldSafetyDefaultsService)" in source)
    }

    @Test
    fun `lifecycle listener covers creation and world load without touching passthrough`() {
        val listener = source("listener/MyWorldSafetyDefaultsListener.kt")
        val service = source("protection/MyWorldSafetyDefaultsService.kt")

        assertTrue("MwmWorldCreatedEvent" in listener)
        assertTrue("WorldLoadEvent" in listener)
        assertTrue("findByUuid(event.worldUuid)" in listener)
        assertTrue("findByWorldName(event.world.name)" in listener)
        assertTrue("runTask(plugin, Runnable" in listener)
        assertFalse("Flags.PASSTHROUGH" in service)
    }

    private fun source(relativePath: String): String = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon/$relativePath",
    ).readText()
}
