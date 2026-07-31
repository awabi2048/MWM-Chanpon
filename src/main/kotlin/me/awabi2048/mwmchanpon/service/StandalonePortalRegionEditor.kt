package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import net.querz.mca.MCAUtil
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.ListTag

internal data class StandalonePortalCommand(
    val x: Int,
    val y: Int,
    val z: Int,
    val command: String,
)

internal object StandalonePortalRegionEditor {
    fun write(worldRoot: Path, portal: StandalonePortalCommand) {
        val regionDirectory = worldRoot.resolve("region")
        val regionFile = regionDirectory.resolve(MCAUtil.createNameFromBlockLocation(portal.x, portal.z))
        require(Files.isRegularFile(regionFile)) {
            "portal chunk region is missing: $regionFile"
        }
        val mca = MCAUtil.read(regionFile.toFile())
        val chunk = mca.getChunk(MCAUtil.blockToChunk(portal.x), MCAUtil.blockToChunk(portal.z))
            ?: error("portal chunk is missing: ${portal.x}, ${portal.z}")

        mca.setBlockStateAt(portal.x, portal.y, portal.z, blockState("minecraft:command_block"), false)
        mca.setBlockStateAt(
            portal.x,
            portal.y + 1,
            portal.z,
            blockState("minecraft:stone_pressure_plate", mapOf("powered" to "false")),
            false,
        )

        val tileEntities = chunk.tileEntities ?: ListTag(CompoundTag::class.java)
        val retained = ListTag(CompoundTag::class.java)
        tileEntities.forEach { tag ->
            if (tag.getInt("x") != portal.x || tag.getInt("y") != portal.y || tag.getInt("z") != portal.z) {
                retained.add(tag)
            }
        }
        retained.add(
            CompoundTag().apply {
                putString("id", "minecraft:command_block")
                putInt("x", portal.x)
                putInt("y", portal.y)
                putInt("z", portal.z)
                putString("Command", portal.command)
                putByte("auto", 0)
                putByte("powered", 0)
                putByte("conditionMet", 0)
                putInt("SuccessCount", 0)
                putByte("TrackOutput", 0)
                putByte("UpdateLastExecution", 1)
                putLong("LastExecution", 0)
            },
        )
        chunk.tileEntities = retained
        MCAUtil.write(mca, regionFile.toFile(), true)
    }

    private fun blockState(name: String, properties: Map<String, String> = emptyMap()) =
        CompoundTag().apply {
            putString("Name", name)
            if (properties.isNotEmpty()) {
                put("Properties", CompoundTag().apply {
                    properties.forEach(::putString)
                })
            }
        }
}
