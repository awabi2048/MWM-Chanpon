package me.awabi2048.mwmchanpon.service

import java.util.UUID
import me.awabi2048.myworldmanager.model.WorldData

internal data class ImportedWorld(val uuid: UUID, val worldData: WorldData)
internal data class ImportFolder(val name: String, val exists: Boolean)

internal object ArchiveImportValidator {
    fun resolve(
        manifestWorlds: List<ArchiveManifestWorld>,
        readWorldData: (UUID) -> WorldData?
    ): List<ImportedWorld> = manifestWorlds.map { entry ->
        val worldData = readWorldData(entry.uuid)
        if (worldData == null || worldData.worldKey != entry.worldKey) {
            throw IllegalStateException("archive_world_data_missing")
        }
        ImportedWorld(entry.uuid, worldData)
    }

    fun validateConflicts(
        imports: List<ImportedWorld>,
        uuidExists: (UUID) -> Boolean,
        folder: (WorldData) -> ImportFolder
    ) {
        imports.forEach { imported ->
            if (uuidExists(imported.uuid)) {
                throw IllegalStateException("world_uuid_conflict:${imported.uuid}")
            }
            val target = folder(imported.worldData)
            if (target.exists) throw IllegalStateException("world_folder_conflict:${target.name}")
        }
    }
}
