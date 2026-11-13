package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponGlobalStorageTest {
    @Test
    fun `修正期間とシミュレーションは保存され終了時に解除される`() {
        val folder = Files.createTempDirectory("chanpon-repair-period").toFile()
        try {
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val storage = ChanponGlobalStorage(folder)
            storage.setBuildingAllowed(false)
            storage.setRepairBypassEnabled(true)
            storage.setTourSimulationEnabled(first, true)
            storage.setTourSimulationEnabled(second, true)
            val reloaded = ChanponGlobalStorage(folder)
            assertTrue(reloaded.isRepairBypassEnabled())
            assertTrue(reloaded.isBuildingAllowed())
            assertTrue(reloaded.isTourSimulationEnabled(first))
            reloaded.setRepairBypassEnabled(false)
            val ended = ChanponGlobalStorage(folder)
            assertFalse(ended.isRepairBypassEnabled())
            assertFalse(ended.isBuildingAllowed())
            assertFalse(ended.isTourSimulationEnabled(first))
            ended.setRepairBypassEnabled(true)
            assertFalse(ended.isTourSimulationEnabled(second))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `independent controls default to building and production creation allowed`() {
        val folder = Files.createTempDirectory("chanpon-global-storage").toFile()
        try {
            val storage = ChanponGlobalStorage(folder)
            assertTrue(storage.isBuildingAllowed())
            assertEquals(WorldCreationAvailability.PRODUCTION, storage.getWorldCreationAvailability())

            storage.setBuildingAllowed(false)
            storage.setWorldCreationAvailability(WorldCreationAvailability.NON_PRODUCTION)
            val reloaded = ChanponGlobalStorage(folder)
            assertFalse(reloaded.isBuildingAllowed())
            assertEquals(WorldCreationAvailability.NON_PRODUCTION, reloaded.getWorldCreationAvailability())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `legacy phase is read without being migrated implicitly`() {
        val folder = Files.createTempDirectory("chanpon-global-storage-legacy").toFile()
        try {
            val settings = folder.resolve("settings.yml")
            folder.mkdirs()
            YamlConfiguration().apply {
                set("phase", "BUILD_LOCKED")
                save(settings)
            }

            val storage = ChanponGlobalStorage(folder)
            assertFalse(storage.isBuildingAllowed())
            assertEquals(WorldCreationAvailability.DENIED, storage.getWorldCreationAvailability())
            val unchanged = YamlConfiguration.loadConfiguration(settings)
            assertTrue(unchanged.contains("phase"))
            assertFalse(unchanged.contains("building_allowed"))
            assertFalse(unchanged.contains("world_creation_availability"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `admin world controls snapshot restores every aggregate member`() {
        val folder = Files.createTempDirectory("chanpon-global-storage-snapshot").toFile()
        try {
            val storage = ChanponGlobalStorage(folder)
            val firstSpawn = UUID.randomUUID()
            val secondSpawn = UUID.randomUUID()
            storage.setBuildingAllowed(false)
            storage.setWorldCreationAvailability(WorldCreationAvailability.NON_PRODUCTION)
            storage.setSpawnWorldUuid(firstSpawn)
            storage.setSpawnPolicyInitialized(firstSpawn)
            val before = storage.captureAdminWorldControls()

            storage.setBuildingAllowed(true)
            storage.setWorldCreationAvailability(WorldCreationAvailability.DENIED)
            storage.setSpawnWorldUuid(secondSpawn)
            storage.setSpawnPolicyInitialized(secondSpawn)
            storage.restoreAdminWorldControls(before)

            assertEquals(before, storage.captureAdminWorldControls())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `offline invite notification ids are retained only while invites remain pending`() {
        val folder = Files.createTempDirectory("chanpon-global-storage-offline-invite").toFile()
        try {
            val storage = ChanponGlobalStorage(folder)
            val playerUuid = UUID.randomUUID()
            val retainedId = UUID.randomUUID()
            val removedId = UUID.randomUUID()

            assertTrue(storage.markOfflineMemberInviteNotified(playerUuid, retainedId))
            assertTrue(storage.markOfflineMemberInviteNotified(playerUuid, removedId))
            assertEquals(
                setOf(retainedId),
                storage.retainOfflineMemberInviteIds(playerUuid, setOf(retainedId)),
            )
            assertEquals(setOf(retainedId), storage.getSentOfflineMemberInviteIds(playerUuid))
            assertEquals(
                setOf(retainedId),
                ChanponGlobalStorage(folder).getSentOfflineMemberInviteIds(playerUuid),
            )
        } finally {
            folder.deleteRecursively()
        }
    }
}
