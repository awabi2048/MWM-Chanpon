package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import net.querz.nbt.io.NBTUtil
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag

internal data class StandaloneLevelMetadata(
    val levelName: String,
    val lastPlayed: Long,
    val dataVersion: Int,
    val versionName: String,
    val spawnX: Int,
    val spawnY: Int,
    val spawnZ: Int,
    val spawnAngle: Float,
)

internal object StandaloneLevelDatRewriter {
    fun rewrite(source: Path, target: Path, metadata: StandaloneLevelMetadata) {
        require(Files.isRegularFile(source)) { "level.dat is missing: $source" }
        val namedRoot = NBTUtil.read(source.toFile(), true)
        val root = (namedRoot.tag as? CompoundTag)?.clone()
            ?: error("level.dat root is not a compound tag")
        val data = root.getCompoundTag("Data")?.clone() ?: CompoundTag()
        val version = data.getCompoundTag("Version")?.clone() ?: CompoundTag()
        version.putInt("Id", metadata.dataVersion)
        version.putString("Name", metadata.versionName)
        version.putString("Series", "main")
        version.putByte("Snapshot", 0)

        data.putLong("LastPlayed", metadata.lastPlayed)
        data.putInt("DataVersion", metadata.dataVersion)
        data.putString("LevelName", metadata.levelName)
        data.putInt("GameType", 1)
        data.putByte("allowCommands", 1)
        data.putInt("SpawnX", metadata.spawnX)
        data.putInt("SpawnY", metadata.spawnY)
        data.putInt("SpawnZ", metadata.spawnZ)
        data.putFloat("SpawnAngle", metadata.spawnAngle)
        data.put("Version", version)

        data.getCompoundTag("Player")?.clone()?.let { player ->
            player.putInt("playerGameType", 1)
            player.putInt("previousPlayerGameType", 1)
            data.put("Player", player)
        }
        root.put("Data", data)
        Files.createDirectories(target.parent)
        NBTUtil.write(NamedTag(namedRoot.name, root), target.toFile(), true)
    }
}
