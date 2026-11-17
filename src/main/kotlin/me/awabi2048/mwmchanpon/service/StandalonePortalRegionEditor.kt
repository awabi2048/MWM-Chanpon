package me.awabi2048.mwmchanpon.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import net.querz.nbt.io.NBTInputStream
import net.querz.nbt.io.NBTOutputStream
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.ListTag

internal data class StandalonePortalCommand(val x: Int, val y: Int, val z: Int, val command: String)

/**
 * 旧MCAモデルでは現行のsections/block_entitiesと負の高さを保持できません。
 * 現行NBTを保持したまま対象チャンクだけを追記し、他のチャンクは変更しません。
 * 必ず出力用コピーだけを渡し、稼働ワールドのリージョンは編集しません。
 */
internal object StandalonePortalRegionEditor {
    fun write(worldRoot: Path, portal: StandalonePortalCommand) {
        val chunkX = Math.floorDiv(portal.x, 16)
        val chunkZ = Math.floorDiv(portal.z, 16)
        val region = worldRoot.resolve("region/r.${Math.floorDiv(chunkX, 32)}.${Math.floorDiv(chunkZ, 32)}.mca")
        require(Files.isRegularFile(region)) { "ポータルのリージョンがありません: $region" }
        val slot = Math.floorMod(chunkX, 32) + Math.floorMod(chunkZ, 32) * 32
        RandomAccessFile(region.toFile(), "rw").use { file ->
            require(file.length() >= 8192) { "リージョンヘッダーが不正です: $region" }
            file.seek(slot * 4L)
            val location = file.readInt()
            val sector = location ushr 8
            val sectors = location and 255
            require(sector >= 2 && sectors > 0) { "ポータルのチャンクがありません: $chunkX, $chunkZ" }
            require(sector * 4096L + sectors * 4096L <= file.length()) { "チャンクの位置表が不正です。" }
            file.seek(sector * 4096L)
            val length = file.readInt()
            val compression = file.readUnsignedByte()
            require(length in 1..(sectors * 4096 - 4)) { "チャンク長が不正です。" }
            val external = region.parent.resolve("c.$chunkX.$chunkZ.mcc")
            val payload = if (compression and 128 != 0) Files.readAllBytes(external)
                else ByteArray(length - 1).also(file::readFully)
            val stream = ByteArrayInputStream(payload)
            val decoded = when (compression and 127) {
                1 -> GZIPInputStream(stream)
                2 -> InflaterInputStream(stream)
                3 -> stream
                else -> error("未対応のチャンク圧縮形式です: ${compression and 127}")
            }
            val named = NBTInputStream(decoded).use { it.readTag(512) }
            val root = named.tag as? CompoundTag ?: error("チャンクNBTがCompoundではありません。")
            editChunk(root, portal)
            val bytes = ByteArrayOutputStream()
            NBTOutputStream(DeflaterOutputStream(bytes)).use { it.writeTag(NamedTag(named.name, root), 512) }
            val compressed = bytes.toByteArray()
            val requiredSectors = (compressed.size + 5 + 4095) / 4096
            val useExternal = requiredSectors > 255
            val appendedSector = (file.length() + 4095) / 4096
            require(appendedSector <= 0xffffff) { "リージョンの最大サイズを超えています。" }
            val count = if (useExternal) 1 else requiredSectors
            file.setLength((appendedSector + count) * 4096)
            file.seek(appendedSector * 4096)
            file.writeInt(if (useExternal) 1 else compressed.size + 1)
            file.writeByte(if (useExternal) 130 else 2)
            if (useExternal) Files.write(external, compressed) else file.write(compressed)
            file.seek(slot * 4L)
            file.writeInt((appendedSector.toInt() shl 8) or count)
            file.seek(4096 + slot * 4L)
            file.writeInt((System.currentTimeMillis() / 1000).toInt())
        }
    }

    internal fun editChunk(root: CompoundTag, portal: StandalonePortalCommand) {
        require(!root.containsKey("Level") && root.containsKey("sections")) { "現行形式のチャンクではありません。Minecraft側で先に移行してください。" }
        setBlock(root, portal.x, portal.y, portal.z, blockState("minecraft:command_block", mapOf("facing" to "north", "conditional" to "false")))
        setBlock(root, portal.x, portal.y + 1, portal.z, blockState("minecraft:stone_pressure_plate", mapOf("powered" to "false")))
        val entities = root.getListTag("block_entities")?.asCompoundTagList() ?: ListTag(CompoundTag::class.java)
        val retained = ListTag(CompoundTag::class.java)
        entities.forEach { tag ->
            // コマンドブロックと感圧板の両方で、以前のチェスト等のNBTを除去します。
            if (!isReplaced(tag, portal)) retained.add(tag)
        }
        retained.add(CompoundTag().apply {
            putString("id", "minecraft:command_block")
            putInt("x", portal.x); putInt("y", portal.y); putInt("z", portal.z)
            putString("Command", portal.command)
            putByte("auto", 0); putByte("powered", 0); putByte("conditionMet", 0)
            putInt("SuccessCount", 0); putByte("TrackOutput", 0)
            putByte("UpdateLastExecution", 1); putLong("LastExecution", 0)
        })
        root.put("block_entities", retained)
        // 旧ブロックの予約tickも同じ座標から除去します。
        listOf("block_ticks", "fluid_ticks").forEach { key ->
            root.getListTag(key)?.asCompoundTagList()?.let { ticks ->
                val kept = ListTag(CompoundTag::class.java)
                ticks.forEach { if (!isReplaced(it, portal)) kept.add(it) }
                root.put(key, kept)
            }
        }
        // 変更後の高さと明るさはMinecraftに再計算させます。
        root.remove("Heightmaps")
        root.putByte("isLightOn", 0)
    }

    private fun isReplaced(tag: CompoundTag, portal: StandalonePortalCommand) =
        tag.getInt("x") == portal.x && tag.getInt("z") == portal.z && tag.getInt("y") in portal.y..(portal.y + 1)

    private fun setBlock(root: CompoundTag, x: Int, y: Int, z: Int, state: CompoundTag) {
        val sections = root.getListTag("sections").asCompoundTagList()
        val sectionY = Math.floorDiv(y, 16)
        val section = sections.firstOrNull { it.getByte("Y").toInt() == sectionY }
            ?: error("ポータル位置のセクションがありません: $sectionY")
        val states = section.getCompoundTag("block_states") ?: CompoundTag().apply {
            put("palette", ListTag(CompoundTag::class.java).apply { add(blockState("minecraft:air")) })
        }.also { section.put("block_states", it) }
        val palette = states.getListTag("palette").asCompoundTagList()
        require(palette.size() > 0) { "ブロックパレットが空です。" }
        val oldBits = bits(palette.size())
        val oldData = states.getLongArray("data")
        val values = IntArray(4096) { index ->
            if (palette.size() == 1) 0 else {
                require(oldData != null && oldData.size >= (4096 + 64 / oldBits - 1) / (64 / oldBits)) { "ブロック配列が不正です。" }
                ((oldData[index / (64 / oldBits)] ushr (index % (64 / oldBits) * oldBits)) and ((1L shl oldBits) - 1)).toInt()
            }
        }
        var stateIndex = (0 until palette.size()).firstOrNull { palette[it] == state }
        if (stateIndex == null) { stateIndex = palette.size(); palette.add(state) }
        values[Math.floorMod(y, 16) * 256 + Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16)] = stateIndex
        val newBits = bits(palette.size())
        val perLong = 64 / newBits
        val packed = LongArray((4096 + perLong - 1) / perLong)
        values.forEachIndexed { index, value ->
            require(value in 0 until palette.size()) { "ブロックパレット参照が不正です。" }
            packed[index / perLong] = packed[index / perLong] or (value.toLong() shl (index % perLong * newBits))
        }
        states.putLongArray("data", packed)
        section.remove("BlockLight"); section.remove("SkyLight")
    }

    private fun bits(size: Int) = maxOf(4, 32 - Integer.numberOfLeadingZeros(size - 1))

    private fun blockState(name: String, properties: Map<String, String> = emptyMap()) = CompoundTag().apply {
        putString("Name", name)
        if (properties.isNotEmpty()) put("Properties", CompoundTag().apply { properties.forEach(::putString) })
    }
}
