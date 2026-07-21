package me.awabi2048.mwmchanpon.service

import java.util.UUID
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ArchiveImportValidatorTest {
    @Test
    fun resolvesWorldDataWhenIdentityMatchesManifest() {
        val data = worldData("minecraft:my_world.alpha")
        val manifest = listOf(ArchiveManifestWorld(data.uuid, data.worldKey))

        assertEquals(listOf(ImportedWorld(data.uuid, data)), ArchiveImportValidator.resolve(manifest) { data })
    }

    @Test
    fun rejectsMissingOrMismatchedWorldData() {
        val uuid = UUID.randomUUID()
        val manifest = listOf(ArchiveManifestWorld(uuid, "minecraft:my_world.expected"))

        val error = assertThrows(IllegalStateException::class.java) {
            ArchiveImportValidator.resolve(manifest) { worldData("minecraft:my_world.other", uuid) }
        }
        assertEquals("archive_world_data_missing", error.message)
    }

    @Test
    fun rejectsExistingUuidBeforeFolderCheck() {
        val data = worldData("minecraft:my_world.alpha")
        val error = assertThrows(IllegalStateException::class.java) {
            ArchiveImportValidator.validateConflicts(
                listOf(ImportedWorld(data.uuid, data)),
                uuidExists = { true },
                folder = { error("folder must not be checked") }
            )
        }
        assertEquals("world_uuid_conflict:${data.uuid}", error.message)
    }

    @Test
    fun rejectsExistingWorldFolder() {
        val data = worldData("minecraft:my_world.alpha")
        val error = assertThrows(IllegalStateException::class.java) {
            ArchiveImportValidator.validateConflicts(
                listOf(ImportedWorld(data.uuid, data)),
                uuidExists = { false },
                folder = { ImportFolder("my_world_alpha", true) }
            )
        }
        assertEquals("world_folder_conflict:my_world_alpha", error.message)
    }

    private fun worldData(worldKey: String, uuid: UUID = UUID.randomUUID()) = WorldData(
        uuid = uuid,
        name = "Test",
        description = "",
        icon = Material.GRASS_BLOCK,
        sourceWorld = "world",
        expireDate = "",
        owner = UUID.randomUUID(),
        worldKey = worldKey
    )
}
