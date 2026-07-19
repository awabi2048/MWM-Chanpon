package me.awabi2048.mwmchanpon

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimePolicySourceContractTest {
    @Test
    fun `daily update remains available while MWM expiration archive is disabled`() {
        val command = read("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponCommandExtension.kt")
        val runtime = read("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponWorldRuntimePolicy.kt")
        assertFalse(command.contains("\"update-day\" ->"))
        assertTrue(runtime.contains("isExpirationArchiveEnabled(): Boolean = false"))
    }

    @Test
    fun `MWM publish level is synchronized to private`() {
        val source = read("src/main/kotlin/me/awabi2048/mwmchanpon/service/PublishLevelSynchronizer.kt")
        assertTrue(source.contains("worldData.publishLevel = PublishLevel.PRIVATE"))
        assertTrue(source.contains("worldData.publicAt = null"))
        assertTrue(source.contains("repository.save(worldData)"))
    }

    @Test
    fun `password member addition uses external provider metadata`() {
        val source = read("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponWorldMenuAccessProvider.kt")
        assertTrue(source.contains("source = MwmMemberAddSource.EXTERNAL"))
        assertTrue(source.contains("providerId = \"mwm-chanpon\""))
        assertTrue(source.contains("detail = \"password\""))
    }

    private fun read(path: String): String = Files.readString(Path.of(path))
}
