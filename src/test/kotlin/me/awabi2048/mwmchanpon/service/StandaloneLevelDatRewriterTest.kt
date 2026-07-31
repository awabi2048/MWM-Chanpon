package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import net.querz.nbt.io.NBTUtil
import net.querz.nbt.tag.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StandaloneLevelDatRewriterTest {
    @Test
    fun `出力用のゲームモードと日時とスポーンを上書きする`() {
        val directory = Files.createTempDirectory("mwm-level-dat-test")
        try {
            val levelDat = directory.resolve("level.dat")
            val original = CompoundTag().apply {
                put("Data", CompoundTag().apply {
                    putString("LevelName", "old")
                    putInt("GameType", 0)
                    putByte("allowCommands", 0)
                })
            }
            NBTUtil.write(original, levelDat.toFile(), true)

            StandaloneLevelDatRewriter.rewrite(
                levelDat,
                levelDat,
                StandaloneLevelMetadata("展示場", 1234L, 4444, "1.21.11", 10, 64, -20, 90f),
            )

            val data = (NBTUtil.read(levelDat.toFile(), true).tag as CompoundTag)
                .getCompoundTag("Data")
            assertEquals("展示場", data.getString("LevelName"))
            assertEquals(1234L, data.getLong("LastPlayed"))
            assertEquals(4444, data.getInt("DataVersion"))
            assertEquals(1, data.getInt("GameType"))
            assertEquals(1, data.getByte("allowCommands").toInt())
            assertEquals(10, data.getInt("SpawnX"))
            assertEquals(64, data.getInt("SpawnY"))
            assertEquals(-20, data.getInt("SpawnZ"))
            assertEquals(90f, data.getFloat("SpawnAngle"))
            assertEquals("1.21.11", data.getCompoundTag("Version").getString("Name"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
