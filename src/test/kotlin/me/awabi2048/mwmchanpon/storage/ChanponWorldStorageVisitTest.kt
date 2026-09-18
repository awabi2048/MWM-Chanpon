package me.awabi2048.mwmchanpon.storage

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponWorldStorageVisitTest {
    @Test
    fun `未バックアップのワールドは訪問なしでも定時対象になる`() {
        val dataFolder = Files.createTempDirectory("mwm-chanpon-visit-test").toFile()
        try {
            val storage = ChanponWorldStorage(dataFolder)
            val worldUuid = UUID.randomUUID()

            assertTrue(storage.isVisitedSinceLastBackup(worldUuid))
        } finally {
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `バックアップ後は再訪問があるまで定時対象外になる`() {
        val dataFolder = Files.createTempDirectory("mwm-chanpon-visit-cycle-test").toFile()
        try {
            val storage = ChanponWorldStorage(dataFolder)
            val worldUuid = UUID.randomUUID()

            storage.setLastBackedUpAt(worldUuid, 1000L)
            assertFalse(storage.isVisitedSinceLastBackup(worldUuid))

            storage.setLastVisitAt(worldUuid, 2000L)
            assertTrue(storage.isVisitedSinceLastBackup(worldUuid))

            // 同一周期内の再訪問記録は不要なため、呼び出し側で省略できることを確認する。
            assertTrue(storage.isVisitedSinceLastBackup(worldUuid))

            storage.setLastBackedUpAt(worldUuid, 3000L)
            assertFalse(storage.isVisitedSinceLastBackup(worldUuid))
        } finally {
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `訪問時刻と完了時刻は再読込後も保持される`() {
        val dataFolder = Files.createTempDirectory("mwm-chanpon-visit-persist-test").toFile()
        try {
            val storage = ChanponWorldStorage(dataFolder)
            val worldUuid = UUID.randomUUID()
            storage.setLastVisitAt(worldUuid, 2000L)
            storage.setLastBackedUpAt(worldUuid, 1000L)

            val reloaded = ChanponWorldStorage(dataFolder)
            assertEquals(2000L, reloaded.getLastVisitAt(worldUuid))
            assertEquals(1000L, reloaded.getLastBackedUpAt(worldUuid))
            assertTrue(reloaded.isVisitedSinceLastBackup(worldUuid))
        } finally {
            dataFolder.deleteRecursively()
        }
    }

    @Test
    fun `外部復元時は現行の訪問・完了時刻を温存し旧ハッシュを除去する`() {
        val dataFolder = Files.createTempDirectory("mwm-chanpon-visit-restore-test").toFile()
        try {
            val storage = ChanponWorldStorage(dataFolder)
            val worldUuid = UUID.randomUUID()
            storage.setLastVisitAt(worldUuid, 2000L)
            storage.setLastBackedUpAt(worldUuid, 1500L)

            val source = File.createTempFile("chanpon-restore-source", ".yml")
            try {
                YamlConfiguration().apply {
                    set("schema_version", 1)
                    set("backup.last_visit_at", 100L)
                    set("backup.last_backed_up_at", 100L)
                    set("backup.last_hash", "legacy")
                    save(source)
                }
                storage.restoreExternalWorldFile(source, worldUuid)
            } finally {
                source.delete()
            }

            assertEquals(2000L, storage.getLastVisitAt(worldUuid))
            assertEquals(1500L, storage.getLastBackedUpAt(worldUuid))
            val raw = YamlConfiguration.loadConfiguration(storage.worldFile(worldUuid))
            assertNull(raw.getString("backup.last_hash"))
        } finally {
            dataFolder.deleteRecursively()
        }
    }
}
