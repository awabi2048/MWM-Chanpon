package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag

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
        val root = BinaryTagIO.reader().read(source, BinaryTagIO.Compression.GZIP)
        val originalData = root.getCompound("Data")
        val originalVersion = originalData.getCompound("Version")
        val version = CompoundBinaryTag.builder()
            .put(originalVersion)
            .putInt("Id", metadata.dataVersion)
            .putString("Name", metadata.versionName)
            .putString("Series", "main")
            .putByte("Snapshot", 0)
            .build()
        var dataBuilder = CompoundBinaryTag.builder()
            .put(originalData)
            .putLong("LastPlayed", metadata.lastPlayed)
            .putInt("DataVersion", metadata.dataVersion)
            .putString("LevelName", metadata.levelName)
            .putInt("GameType", 1)
            .putByte("allowCommands", 1)
            .putInt("SpawnX", metadata.spawnX)
            .putInt("SpawnY", metadata.spawnY)
            .putInt("SpawnZ", metadata.spawnZ)
            .putFloat("SpawnAngle", metadata.spawnAngle)
            .put("Version", version)
        val player = originalData.getCompound("Player")
        if (player.size() > 0) {
            dataBuilder = dataBuilder.put(
                "Player",
                CompoundBinaryTag.builder()
                    .put(player)
                    .putInt("playerGameType", 1)
                    .putInt("previousPlayerGameType", 1)
                    .build(),
            )
        }
        val rewritten = CompoundBinaryTag.builder()
            .put(root)
            .put("Data", dataBuilder.build())
            .build()
        Files.createDirectories(target.parent)
        BinaryTagIO.writer().write(rewritten, target, BinaryTagIO.Compression.GZIP)
    }
}
