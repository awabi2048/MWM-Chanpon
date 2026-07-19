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

    @Test
    fun `backup and restore use the MWM shared operation lock`() {
        val source = read("src/main/kotlin/me/awabi2048/mwmchanpon/backup/BackupService.kt")
        assertTrue(source.contains("WorldOperation.BACKUP"))
        assertTrue(source.contains("WorldOperation.RESTORE"))
        assertTrue(source.contains("tryAcquireWorldOperation"))
        assertFalse(source.contains("operationLocks"))
    }

    @Test
    fun `Chanpon world data uses a synchronized atomic writer`() {
        val source = read("src/main/kotlin/me/awabi2048/mwmchanpon/storage/ChanponWorldStorage.kt")
        assertTrue(source.contains("@Synchronized"))
        assertTrue(source.contains("FileChannel.open"))
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(source.contains("Temporary Chanpon world data verification failed"))
        assertFalse(source.contains("config.save(worldFile(worldUuid))"))
    }

    private fun read(path: String): String = Files.readString(Path.of(path))
}
