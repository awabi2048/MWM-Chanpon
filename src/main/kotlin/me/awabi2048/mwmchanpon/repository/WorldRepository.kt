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
        return loadAll().count { it.owner == owner }
    }

    fun findAccessibleByPlayer(playerUuid: UUID): List<MyWorldData> {
        return loadAll()
            .filter { it.owner == playerUuid || it.members.contains(playerUuid) }
            .sortedBy { it.createdAt }
    }

    fun findByCustomWorldName(worldName: String): MyWorldData? {
        return loadAll().firstOrNull { it.customWorldName == worldName }
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
        worldData.spawnPosGuest?.let { section.createSection("spawn_pos_guest", it.toMap()) }
        section.createSection("spawn_pos_member", worldData.spawnPosMember.toMap())
        section.createSection("border_center_pos", worldData.borderCenterPos.toMap())
        section.set("border_size", worldData.borderSize)
        section.set("notification_enabled", worldData.notificationEnabled)
        section.set("announcement_message", worldData.announcementMessage)
        section.set("tags", worldData.tags)
        section.set("created_at", worldData.createdAt.toString())
        section.set("custom_world_name", worldData.customWorldName)
        config.save(File(rootDirectory, "${worldData.uuid}.yml"))
    }

    fun load(worldUuid: UUID): MyWorldData? {
        val file = File(rootDirectory, "$worldUuid.yml")
        return loadFromFile(file)
    }

    fun loadAll(): List<MyWorldData> {
        val files = rootDirectory.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) } ?: return emptyList()
        return files.mapNotNull { file -> loadFromFile(file) }
    }

    private fun loadFromFile(file: File): MyWorldData? {
        if (!file.exists()) {
            return null
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val section = config.getConfigurationSection("world_data") ?: return null
        val spawnGuestSection = section.getConfigurationSection("spawn_pos_guest")
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
            spawnPosGuest = spawnGuestSection?.let(::fromSection),
            spawnPosMember = fromSection(spawnSection),
            borderCenterPos = fromSection(borderSection),
            borderSize = section.getDouble("border_size"),
            notificationEnabled = section.getBoolean("notification_enabled", true),
            announcementMessage = section.getString("announcement_message").orEmpty(),
            tags = section.getStringList("tags"),
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
