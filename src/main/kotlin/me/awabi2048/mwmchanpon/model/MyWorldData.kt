package me.awabi2048.mwmchanpon.model

import org.bukkit.Material
import java.time.LocalDateTime
import java.util.UUID

data class MyWorldData(
    val uuid: UUID,
    val name: String,
    val description: String,
    val icon: Material,
    val sourceWorld: String,
    val owner: UUID,
    val members: List<UUID>,
    val publishLevel: WorldPublishLevel,
    val spawnPosMember: SerializableLocation,
    val borderCenterPos: SerializableLocation,
    val borderSize: Double,
    val customWorldName: String,
    val createdAt: LocalDateTime,
)

data class SerializableLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)
