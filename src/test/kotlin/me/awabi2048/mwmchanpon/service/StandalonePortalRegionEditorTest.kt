package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import net.querz.mca.Chunk
import net.querz.mca.MCAFile
import net.querz.mca.MCAUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class StandalonePortalRegionEditorTest {
    @Test
    fun `ポータル位置をコマンドブロックと感圧板へ置換する`() {
        val world = Files.createTempDirectory("mwm-portal-region-test")
        try {
            val region = world.resolve("region")
            Files.createDirectories(region)
            val file = region.resolve("r.0.0.mca")
            val mca = MCAFile(0, 0)
            mca.setChunk(0, 0, Chunk.newChunk())
            MCAUtil.write(mca, file.toFile(), true)

            StandalonePortalRegionEditor.write(
                world,
                StandalonePortalCommand(1, 64, 2, "execute as @p run say test"),
            )

            val rewritten = MCAUtil.read(file.toFile())
            assertEquals("minecraft:command_block", rewritten.getBlockStateAt(1, 64, 2).getString("Name"))
            assertEquals("minecraft:stone_pressure_plate", rewritten.getBlockStateAt(1, 65, 2).getString("Name"))
            val commandBlock = rewritten.getChunk(0, 0).tileEntities
                .firstOrNull { it.getInt("x") == 1 && it.getInt("y") == 64 && it.getInt("z") == 2 }
            assertNotNull(commandBlock)
            assertEquals("execute as @p run say test", commandBlock!!.getString("Command"))
        } finally {
            world.toFile().deleteRecursively()
        }
    }
}
