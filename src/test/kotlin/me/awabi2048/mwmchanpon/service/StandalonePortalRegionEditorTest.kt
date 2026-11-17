package me.awabi2048.mwmchanpon.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import net.querz.nbt.io.NBTInputStream
import net.querz.nbt.io.NBTOutputStream
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.ListTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StandalonePortalRegionEditorTest {
    @TempDir lateinit var directory: Path

    private fun chunk(paletteSize: Int = 1) = CompoundTag().apply {
        putInt("DataVersion", 5000)
        putString("Status", "minecraft:full")
        putString("保持対象", "元のNBT")
        put("sections", ListTag(CompoundTag::class.java).apply {
            for (y in -4..19) add(CompoundTag().apply {
                putByte("Y", y.toByte())
                put("block_states", CompoundTag().apply {
                    put("palette", ListTag(CompoundTag::class.java).apply {
                        repeat(paletteSize) { index -> add(CompoundTag().apply { putString("Name", if (index == 0) "minecraft:air" else "test:block_$index") }) }
                    })
                    if (paletteSize > 1) putLongArray("data", LongArray(256))
                })
                put("biomes", CompoundTag().apply { putString("保持対象", "バイオーム") })
            })
        })
        put("block_entities", ListTag(CompoundTag::class.java))
    }

    private fun block(root: CompoundTag, x: Int, y: Int, z: Int): String {
        val section = root.getListTag("sections").asCompoundTagList().first { it.getByte("Y").toInt() == Math.floorDiv(y, 16) }
        val states = section.getCompoundTag("block_states")
        val palette = states.getListTag("palette").asCompoundTagList()
        if (palette.size() == 1) return palette[0].getString("Name")
        val bits = maxOf(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1))
        val index = Math.floorMod(y, 16) * 256 + Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16)
        val value = ((states.getLongArray("data")[index / (64 / bits)] ushr (index % (64 / bits) * bits)) and ((1L shl bits) - 1)).toInt()
        return palette[value].getString("Name")
    }

    private fun entity(x: Int, y: Int, z: Int) = CompoundTag().apply {
        putString("id", "minecraft:chest"); putInt("x", x); putInt("y", y); putInt("z", z)
    }

    @Test
    fun `負座標とセクション境界を含め現行NBTの対象だけを置換する`() {
        val root = chunk()
        root.getListTag("block_entities").asCompoundTagList().apply {
            add(entity(-1, -1, -17)); add(entity(-1, 0, -17)); add(entity(-2, 0, -17))
        }
        StandalonePortalRegionEditor.editChunk(root, StandalonePortalCommand(-1, -1, -17, "execute as @p run say test"))
        assertEquals("minecraft:command_block", block(root, -1, -1, -17))
        assertEquals("minecraft:stone_pressure_plate", block(root, -1, 0, -17))
        assertEquals("minecraft:air", block(root, -2, -1, -17))
        assertEquals("元のNBT", root.getString("保持対象"))
        val entities = root.getListTag("block_entities").asCompoundTagList()
        assertEquals(2, entities.size())
        assertEquals("execute as @p run say test", entities.first { it.getString("id") == "minecraft:command_block" }.getString("Command"))
        assertEquals(1, entities.count { it.getString("id") == "minecraft:chest" })
    }

    @Test
    fun `パレット幅の拡張と同一チャンクの複数ポータルを保持する`() {
        val root = chunk(16)
        StandalonePortalRegionEditor.editChunk(root, StandalonePortalCommand(1, 64, 2, "say one"))
        StandalonePortalRegionEditor.editChunk(root, StandalonePortalCommand(3, 64, 4, "say two"))
        assertEquals("minecraft:command_block", block(root, 1, 64, 2))
        assertEquals("minecraft:stone_pressure_plate", block(root, 3, 65, 4))
        assertEquals("minecraft:air", block(root, 0, 64, 0))
        assertEquals(2, root.getListTag("block_entities").size())
    }

    @Test
    fun `旧形式と範囲外の高さは無言で破壊せず拒否する`() {
        assertThrows(IllegalArgumentException::class.java) {
            StandalonePortalRegionEditor.editChunk(CompoundTag().apply { put("Level", CompoundTag()) }, StandalonePortalCommand(0, 64, 0, "say test"))
        }
        assertThrows(IllegalStateException::class.java) {
            StandalonePortalRegionEditor.editChunk(chunk(), StandalonePortalCommand(0, 319, 0, "say test"))
        }
    }

    @Test
    fun `リージョン更新は他チャンクを変更せず繰り返し読める`() {
        val region = directory.resolve("region/r.-1.-1.mca")
        Files.createDirectories(region.parent)
        val bytes = ByteArrayOutputStream()
        NBTOutputStream(DeflaterOutputStream(bytes)).use { it.writeTag(NamedTag("", chunk()), 512) }
        val payload = bytes.toByteArray()
        val count = (payload.size + 5 + 4095) / 4096
        RandomAccessFile(region.toFile(), "rw").use { file ->
            file.setLength((2 + count) * 4096L)
            file.seek(1023 * 4L); file.writeInt((2 shl 8) or count)
            file.seek(8192); file.writeInt(payload.size + 1); file.writeByte(2); file.write(payload)
        }
        val original = Files.readAllBytes(region)
        StandalonePortalRegionEditor.write(directory, StandalonePortalCommand(-1, 64, -1, "say one"))
        StandalonePortalRegionEditor.write(directory, StandalonePortalCommand(-2, 64, -2, "say two"))
        val current = Files.readAllBytes(region)
        assertArrayEquals(original.copyOfRange(8192, original.size), current.copyOfRange(8192, original.size))
        RandomAccessFile(region.toFile(), "r").use { file ->
            file.seek(1023 * 4L); val location = file.readInt()
            file.seek((location ushr 8) * 4096L)
            val length = file.readInt(); assertEquals(2, file.readUnsignedByte())
            val compressed = ByteArray(length - 1); file.readFully(compressed)
            val root = NBTInputStream(InflaterInputStream(ByteArrayInputStream(compressed))).use { it.readTag(512).tag as CompoundTag }
            assertEquals("minecraft:command_block", block(root, -1, 64, -1))
            assertEquals("minecraft:command_block", block(root, -2, 64, -2))
        }
    }
}
