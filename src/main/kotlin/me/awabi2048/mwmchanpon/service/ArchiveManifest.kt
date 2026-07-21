package me.awabi2048.mwmchanpon.service

import java.io.File
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration

internal data class ArchiveManifestWorld(val uuid: UUID, val worldKey: String)

internal object ArchiveManifest {
    fun read(file: File, supportedSchemaVersion: Int): List<ArchiveManifestWorld> {
        if (!file.exists()) throw IllegalStateException("manifest_missing")
        val yaml = YamlConfiguration.loadConfiguration(file)
        if (yaml.getInt("schema_version", 0) != supportedSchemaVersion) {
            throw IllegalStateException("archive_schema_unsupported")
        }
        val entries = yaml.getMapList("worlds")
        if (entries.isEmpty()) throw IllegalStateException("archive_empty")
        return entries.map { entry ->
            val uuid = runCatching { UUID.fromString(entry["uuid"]?.toString()) }.getOrNull()
            val worldKey = entry["world_key"]?.toString()
            if (uuid == null || worldKey.isNullOrBlank()) {
                throw IllegalStateException("archive_world_data_missing")
            }
            ArchiveManifestWorld(uuid, worldKey)
        }
    }
}
