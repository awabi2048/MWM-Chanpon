package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponGlobalStorageTest {
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
    fun `legacy phase is migrated to independent controls`() {
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
            val migrated = YamlConfiguration.loadConfiguration(settings)
            assertFalse(migrated.contains("phase"))
        } finally {
            folder.deleteRecursively()
        }
    }
}
