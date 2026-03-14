package me.awabi2048.mwmchanpon.repository

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.MyWorldData
import me.awabi2048.mwmchanpon.model.SerializableLocation
import me.awabi2048.mwmchanpon.model.WorldPublishLevel
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

class WorldRepository(private val plugin: MWMChanponPlugin) {
    private val rootDirectory: File
        get() = File(plugin.dataFolder.parentFile, "my_worlds")

    fun countByOwner(owner: UUID): Int {
        val files = rootDirectory.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) } ?: return 0
        return files.count { file ->
            if (!file.exists()) {
                false
            } else {
                val config = YamlConfiguration.loadConfiguration(file)
                config.getString("world_data.owner") == owner.toString()
            }
        }
    }

    fun save(worldData: MyWorldData) {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }

        val config = YamlConfiguration()
        val section = config.createSection("world_data")
        section.set("uuid", worldData.uuid.toString())
        section.set("name", worldData.name)
        section.set("description", worldData.description)
        section.set("icon", worldData.icon.name)
        section.set("source_world", worldData.sourceWorld)
        section.set("owner", worldData.owner.toString())
        section.set("members", worldData.members.map(UUID::toString))
        section.set("publish_level", worldData.publishLevel.name)
        section.createSection("spawn_pos_member", worldData.spawnPosMember.toMap())
        section.createSection("border_center_pos", worldData.borderCenterPos.toMap())
        section.set("border_size", worldData.borderSize)
        section.set("created_at", worldData.createdAt.toString())
        section.set("custom_world_name", worldData.customWorldName)
        config.save(File(rootDirectory, "${worldData.uuid}.yml"))
    }

    fun load(worldUuid: UUID): MyWorldData? {
        val file = File(rootDirectory, "$worldUuid.yml")
        if (!file.exists()) {
            return null
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val section = config.getConfigurationSection("world_data") ?: return null
        val spawnSection = section.getConfigurationSection("spawn_pos_member") ?: return null
        val borderSection = section.getConfigurationSection("border_center_pos") ?: return null

        return MyWorldData(
            uuid = UUID.fromString(section.getString("uuid") ?: return null),
            name = section.getString("name") ?: return null,
            description = section.getString("description").orEmpty(),
            icon = Material.matchMaterial(section.getString("icon").orEmpty()) ?: Material.GRASS_BLOCK,
            sourceWorld = section.getString("source_world").orEmpty(),
            owner = UUID.fromString(section.getString("owner") ?: return null),
            members = section.getStringList("members").map(UUID::fromString),
            publishLevel = runCatching {
                WorldPublishLevel.valueOf(section.getString("publish_level") ?: WorldPublishLevel.WIP.name)
            }.getOrDefault(WorldPublishLevel.WIP),
            spawnPosMember = fromSection(spawnSection),
            borderCenterPos = fromSection(borderSection),
            borderSize = section.getDouble("border_size"),
            customWorldName = section.getString("custom_world_name") ?: return null,
            createdAt = LocalDateTime.parse(section.getString("created_at") ?: return null),
        )
    }

    private fun SerializableLocation.toMap(): Map<String, Any> = mapOf(
        "world" to world,
        "x" to x,
        "y" to y,
        "z" to z,
        "yaw" to yaw,
        "pitch" to pitch,
    )

    private fun fromSection(section: ConfigurationSection): SerializableLocation {
        return SerializableLocation(
            world = section.getString("world").orEmpty(),
            x = section.getDouble("x"),
            y = section.getDouble("y"),
            z = section.getDouble("z"),
            yaw = section.getDouble("yaw").toFloat(),
            pitch = section.getDouble("pitch").toFloat(),
        )
    }
}
