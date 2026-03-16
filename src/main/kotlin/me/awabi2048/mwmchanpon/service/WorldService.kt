package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.CreationMethod
import me.awabi2048.mwmchanpon.model.MyWorldData
import me.awabi2048.mwmchanpon.model.SerializableLocation
import me.awabi2048.mwmchanpon.model.WizardSession
import me.awabi2048.mwmchanpon.model.WorldPublishLevel
import me.awabi2048.mwmchanpon.repository.WorldRepository
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.Comparator
import java.util.UUID

class WorldService(
    private val plugin: MWMChanponPlugin,
    private val worldRepository: WorldRepository,
    private val templateService: TemplateService,
) {
    fun validateWorldName(name: String): String? {
        val minLength = plugin.config.getInt("world_name.min_length", 3)
        val maxLength = plugin.config.getInt("world_name.max_length", 16)
        if (name.length !in minLength..maxLength) {
            return plugin.localizationService.getString(null, "messages.world_name_invalid_length", mapOf("min" to minLength.toString(), "max" to maxLength.toString()))
        }

        val blocked = plugin.config.getStringList("validation.blocked_strings")
        if (blocked.any { it.isNotBlank() && name.contains(it, ignoreCase = true) }) {
            return plugin.localizationService.getString(null, "messages.world_name_blocked")
        }

        return null
    }

    fun validateWorldDescription(description: String): String? {
        val maxLength = plugin.config.getInt("world_description.max_length", 100)
        if (description.length > maxLength) {
            return plugin.localizationService.getString(null, "messages.world_description_invalid_length", mapOf("max" to maxLength.toString()))
        }
        if (description.contains('\n') || description.contains('\r')) {
            return plugin.localizationService.getString(null, "messages.world_description_single_line")
        }
        return null
    }

    fun canCreateMore(targetPlayer: Player): Boolean {
        return getOwnedWorldCount(targetPlayer.uniqueId) < getMaxCreateCount()
    }

    fun getMaxCreateCount(): Int {
        return plugin.config.getInt("creation.max_create_count_default", 3)
    }

    fun getOwnedWorldCount(playerUuid: UUID): Int {
        return worldRepository.countByOwner(playerUuid)
    }

    fun getRemainingCreateCount(player: Player): Int {
        return (getMaxCreateCount() - getOwnedWorldCount(player.uniqueId)).coerceAtLeast(0)
    }

    fun getAccessibleWorlds(player: Player): List<MyWorldData> {
        return worldRepository.findAccessibleByPlayer(player.uniqueId)
    }

    fun getWorldByUuid(worldUuid: UUID): MyWorldData? {
        return worldRepository.load(worldUuid)
    }

    fun getWorldByCustomWorldName(worldName: String): MyWorldData? {
        return worldRepository.findByCustomWorldName(worldName)
    }

    fun canOpenSettings(player: Player, worldData: MyWorldData): Boolean {
        return worldData.owner == player.uniqueId || worldData.members.contains(player.uniqueId)
    }

    fun isPlayerInWorld(player: Player, worldData: MyWorldData): Boolean {
        return player.world.name == worldData.customWorldName
    }

    fun getNotificationTargets(worldData: MyWorldData): List<UUID> {
        return buildList {
            add(worldData.owner)
            addAll(worldData.members)
        }.distinct()
    }

    fun rotatePublishLevel(worldData: MyWorldData, forward: Boolean = true): MyWorldData {
        val levels = WorldPublishLevel.entries
        val currentIndex = levels.indexOf(worldData.publishLevel).coerceAtLeast(0)
        val nextIndex = if (forward) {
            (currentIndex + 1) % levels.size
        } else {
            (currentIndex - 1 + levels.size) % levels.size
        }
        return updateWorld(worldData.copy(publishLevel = levels[nextIndex]))
    }

    fun updateWorldInfo(worldData: MyWorldData, name: String, description: String): Result<MyWorldData> {
        val nameError = validateWorldName(name)
        if (nameError != null) {
            return Result.failure(IllegalArgumentException(nameError))
        }
        val descriptionError = validateWorldDescription(description)
        if (descriptionError != null) {
            return Result.failure(IllegalArgumentException(descriptionError))
        }
        return Result.success(updateWorld(worldData.copy(name = name, description = description)))
    }

    fun updateWorldIcon(worldData: MyWorldData, icon: Material): MyWorldData {
        return updateWorld(worldData.copy(icon = icon))
    }

    fun toggleVisitNotification(worldData: MyWorldData): MyWorldData {
        return updateWorld(worldData.copy(notificationEnabled = !worldData.notificationEnabled))
    }

    private fun updateWorld(worldData: MyWorldData): MyWorldData {
        worldRepository.save(worldData)
        return worldData
    }

    fun teleportToWorld(player: Player, worldData: MyWorldData): Result<Unit> {
        return runCatching {
            val world = Bukkit.getWorld(worldData.customWorldName)
                ?: Bukkit.createWorld(WorldCreator(worldData.customWorldName))
                ?: throw IllegalStateException("ワールドの読み込みに失敗しました。")
            val location = Location(
                world,
                worldData.spawnPosMember.x,
                worldData.spawnPosMember.y,
                worldData.spawnPosMember.z,
                worldData.spawnPosMember.yaw,
                worldData.spawnPosMember.pitch,
            )
            world.getChunkAt(location).load(true)
            player.teleportAsync(location)
        }
    }

    fun createWorld(session: WizardSession, callback: (Result<MyWorldData>) -> Unit) {
        val targetPlayer = Bukkit.getPlayer(session.targetPlayerUuid)
        if (targetPlayer == null || !targetPlayer.isOnline) {
            callback(Result.failure(IllegalStateException("対象プレイヤーがオフラインです。")))
            return
        }

        if (!canCreateMore(targetPlayer)) {
            callback(Result.failure(IllegalStateException("対象プレイヤーはこれ以上マイワールドを所有できません。")))
            return
        }

        val method = session.creationMethod ?: run {
            callback(Result.failure(IllegalStateException("作成方式が選択されていません。")))
            return
        }
        session.worldName ?: run {
            callback(Result.failure(IllegalStateException("ワールド名が未設定です。")))
            return
        }
        val worldUuid = UUID.randomUUID()
        val customWorldName = "my_world.$worldUuid"

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            runCatching {
                prepareWorldFolder(customWorldName, session)
            }.onFailure {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    callback(Result.failure(it))
                })
                return@Runnable
            }

            plugin.server.scheduler.runTask(plugin, Runnable {
                runCatching {
                    val world = createOrLoadWorld(customWorldName, method, session.seedText)
                    val spawn = resolveSpawn(world, session)
                    applyWorldBorder(world, spawn)
                    val data = buildWorldData(worldUuid, customWorldName, world, targetPlayer, spawn, session)
                    worldRepository.save(data)
                    teleportAfterDelay(targetPlayer, spawn)
                    data
                }.onSuccess {
                    callback(Result.success(it))
                }.onFailure {
                    deleteWorldDirectoryIfExists(customWorldName)
                    callback(Result.failure(it))
                }
            })
        })
    }

    private fun prepareWorldFolder(customWorldName: String, session: WizardSession) {
        val target = plugin.server.worldContainer.toPath().resolve(customWorldName)
        if (Files.exists(target)) {
            throw IllegalStateException("同名ワールドディレクトリが既に存在します。")
        }

        if (session.creationMethod == CreationMethod.TEMPLATE) {
            val template = templateService.get(session.templateKey ?: throw IllegalStateException("テンプレートが未選択です。"))
                ?: throw IllegalStateException("テンプレートが見つかりません。")
            copyTemplateDirectory(plugin.server.worldContainer.toPath().resolve(template.path), target)
        }
    }

    private fun createOrLoadWorld(name: String, method: CreationMethod, seedText: String?): World {
        val creator = WorldCreator(name)
        if (method == CreationMethod.SEED && !seedText.isNullOrBlank()) {
            creator.seed(seedText.toLongOrNull() ?: seedText.hashCode().toLong())
        }

        return Bukkit.createWorld(creator) ?: throw IllegalStateException("ワールドの読み込みに失敗しました。")
    }

    private fun resolveSpawn(world: World, session: WizardSession): Location {
        val template = session.templateKey?.let(templateService::get)
        val templateOrigin = template?.originLocation
        val spawn = if (templateOrigin != null) {
            Location(world, templateOrigin.x + 0.5, templateOrigin.y, templateOrigin.z + 0.5)
        } else {
            world.spawnLocation.clone().add(0.5, 0.0, 0.5)
        }

        world.setSpawnLocation(spawn)
        world.getChunkAt(spawn).load(true)
        return spawn
    }

    private fun applyWorldBorder(world: World, spawn: Location) {
        val border = world.worldBorder
        val initialSize = plugin.config.getDouble("expansion.initial_size", 100.0)
        border.center = spawn
        border.size = initialSize
    }

    private fun buildWorldData(
        worldUuid: UUID,
        customWorldName: String,
        world: World,
        targetPlayer: Player,
        spawn: Location,
        session: WizardSession,
    ): MyWorldData {
        val icon = resolveIcon(session)
        val borderCenter = SerializableLocation(
            world = world.name,
            x = spawn.x,
            y = spawn.y,
            z = spawn.z,
            yaw = spawn.yaw,
            pitch = spawn.pitch,
        )
        val spawnLocation = borderCenter.copy()

        return MyWorldData(
            uuid = worldUuid,
            name = session.worldName.orEmpty(),
            description = "",
            icon = icon,
            sourceWorld = resolveSourceWorld(session),
            owner = targetPlayer.uniqueId,
            members = emptyList(),
            publishLevel = WorldPublishLevel.WIP,
            spawnPosGuest = spawnLocation,
            spawnPosMember = spawnLocation,
            borderCenterPos = borderCenter,
            borderSize = world.worldBorder.size,
            notificationEnabled = true,
            announcementMessage = "",
            tags = emptyList(),
            customWorldName = customWorldName,
            createdAt = LocalDateTime.now(),
        )
    }

    private fun resolveIcon(session: WizardSession): Material {
        val template = session.templateKey?.let(templateService::get)
        return template?.icon ?: Material.GRASS_BLOCK
    }

    private fun resolveSourceWorld(session: WizardSession): String {
        return when (session.creationMethod) {
            CreationMethod.TEMPLATE -> "template:${session.templateKey.orEmpty()}"
            CreationMethod.RANDOM -> "random"
            CreationMethod.SEED -> "seed:${session.seedText.orEmpty()}"
            null -> "unknown"
        }
    }

    private fun teleportAfterDelay(player: Player, location: Location) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) {
                return@Runnable
            }
            location.world?.getChunkAt(location)?.load(true)
            player.teleportAsync(location)
        }, 20L)
    }

    private fun copyTemplateDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination)
                }
            }
        }
        Files.deleteIfExists(target.resolve("uid.dat"))
        Files.deleteIfExists(target.resolve("session.lock"))
    }

    fun deleteWorldDirectoryIfExists(worldName: String) {
        val root = plugin.server.worldContainer.toPath().resolve(worldName)
        if (!Files.exists(root)) {
            return
        }

        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
