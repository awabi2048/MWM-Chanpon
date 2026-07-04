package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.CCSystem
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import me.awabi2048.myworldmanager.model.PublishLevel
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.WorldNameValidation
import me.awabi2048.myworldmanager.util.WorldWarpId
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import org.bukkit.plugin.java.JavaPlugin

/**
 * MWM本体の作成種別へ戻さず、Chanpon専用の固定フローとしてかんたん作成を完結させる。
 */
class EasyVoidWorldCreationService(
    private val plugin: MWMChanponPlugin
) : Listener {
    private val pendingNames = ConcurrentHashMap<UUID, String>()
    private val creatingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun start(player: Player) {
        pendingNames.remove(player.uniqueId)
        player.closeInventory()
        player.sendMessage(i18n(player, "start"))
        showNameDialog(player)
    }

    @EventHandler
    fun onDialogClick(event: PlayerCustomClickEvent) {
        val identifier = event.identifier
        if (identifier !in DIALOG_KEYS) return

        val connection = event.commonConnection as? PlayerGameConnection ?: return
        val player = connection.player
        when (identifier) {
            NAME_SUBMIT -> handleNameSubmit(player, event)
            NAME_CANCEL -> pendingNames.remove(player.uniqueId)
            CONFIRM_CREATE -> {
                val name = pendingNames.remove(player.uniqueId) ?: return
                create(player, name)
            }
            CONFIRM_BACK -> showNameDialog(player, pendingNames[player.uniqueId].orEmpty())
        }
    }

    private fun handleNameSubmit(player: Player, event: PlayerCustomClickEvent) {
        val name = event.getDialogResponseView()?.getText(NAME_INPUT).orEmpty().trim()
        if (name.isBlank()) {
            showNameDialog(player, name, legacy("§c${i18n(player, "name_required")}"))
            return
        }

        val mwm = mwm()
        when (val validation = mwm.worldValidator.validateName(name)) {
            is WorldNameValidation.Failure -> {
                showNameDialog(player, name, mwm.languageManager.getComponent(player, validation.messageKey, validation.placeholders))
                return
            }
            WorldNameValidation.Ok -> Unit
        }

        if (mwm.worldConfigRepository.findByOwnerAndDisplayName(player.uniqueId, name) != null) {
            showNameDialog(player, name, mwm.languageManager.getComponent(player, "messages.world_name_duplicate"))
            return
        }

        pendingNames[player.uniqueId] = name
        showConfirmDialog(player, name)
    }

    private fun showNameDialog(player: Player, initial: String = "", error: Component? = null) {
        val body = buildList {
            add(DialogBody.plainMessage(component(player, "name_body")))
            if (error != null) add(DialogBody.plainMessage(error))
        }
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(component(player, "name_title"))
                        .body(body)
                        .inputs(
                            listOf(
                                DialogInput.text(NAME_INPUT, component(player, "name_label"))
                                    .initial(initial)
                                    .maxLength(32)
                                    .build()
                            )
                        )
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        ActionButton.create(component(player, "next"), null, 100, DialogAction.customClick(NAME_SUBMIT, null)),
                        ActionButton.create(component(player, "cancel"), null, 200, DialogAction.customClick(NAME_CANCEL, null))
                    )
                )
        }
        player.showDialog(dialog)
    }

    private fun showConfirmDialog(player: Player, name: String) {
        val body = listOf(
            DialogBody.plainMessage(component(player, "confirm_body", "world" to name)),
            DialogBody.plainMessage(component(player, "confirm_template"))
        )
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(DialogBase.builder(component(player, "confirm_title")).body(body).build())
                .type(
                    DialogType.confirmation(
                        ActionButton.create(component(player, "create"), null, 100, DialogAction.customClick(CONFIRM_CREATE, null)),
                        ActionButton.create(component(player, "back"), null, 200, DialogAction.customClick(CONFIRM_BACK, null))
                    )
                )
        }
        player.showDialog(dialog)
    }

    private fun create(player: Player, worldName: String) {
        if (!creatingPlayers.add(player.uniqueId)) {
            player.sendMessage(mwm().languageManager.getMessage(player, "error.world_creation_in_progress"))
            return
        }

        try {
            val mwm = mwm()
            if (mwm.worldConfigRepository.findByOwnerAndDisplayName(player.uniqueId, worldName) != null) {
                player.sendMessage(mwm.languageManager.getMessage(player, "messages.world_name_duplicate"))
                return
            }

            val uuid = generateUniqueWorldUuid(mwm)
            val folderName = "my_world.$uuid"
            if (Bukkit.getWorld(folderName) != null || mwm.worldService.resolveWorldDirectory(folderName).exists()) {
                player.sendMessage(mwm.languageManager.getMessage(player, "error.world_already_exists"))
                return
            }

            player.sendMessage(mwm.languageManager.getMessage(player, "messages.world_creation_started", mapOf("world" to worldName)))
            val creator = WorldCreator(folderName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generator(EASY_VOID_GENERATOR)
                .generateStructures(false)
            val world = Bukkit.createWorld(creator)
            if (world == null) {
                player.sendMessage(mwm.languageManager.getMessage(player, "error.world_creation_failed"))
                return
            }

            val spawn = Location(world, 0.0, SPAWN_Y.toDouble(), 0.0)
            world.setSpawnLocation(spawn.blockX, spawn.blockY, spawn.blockZ)
            placePlatform(world)
            applyInitialBorder(mwm, world)

            val createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val expireDate = LocalDate.now()
                .plusDays(mwm.config.getLong("default_expiration.initial_days", 30))
                .toString()
            val worldData = WorldData(
                uuid = uuid,
                name = worldName,
                description = "My World",
                icon = Material.GRASS_BLOCK,
                sourceWorld = SOURCE_WORLD,
                expireDate = expireDate,
                owner = player.uniqueId,
                publishLevel = PublishLevel.PRIVATE,
                spawnPosMember = world.spawnLocation,
                borderCenterPos = world.spawnLocation,
                cumulativePoints = 0,
                customWorldName = folderName,
                createdAt = createdAt
            )
            mwm.worldConfigRepository.save(worldData)
            Bukkit.getPluginManager().callEvent(
                MwmWorldCreatedEvent(
                    worldUuid = uuid,
                    worldName = worldName,
                    ownerUuid = player.uniqueId,
                    ownerName = player.name,
                    templateName = SOURCE_WORLD,
                    createdAt = createdAt
                )
            )
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.world_creation_success", mapOf("world" to worldName)))
            mwm.worldService.teleportToWorld(player, uuid)
            mwm.macroManager.execute(
                "on_world_create",
                mapOf("owner" to player.name, "world_uuid" to uuid.toString(), "world_name" to worldName, "template_name" to SOURCE_WORLD)
            )
        } catch (ex: Exception) {
            plugin.logger.severe("Failed to create easy void world '$worldName': ${ex.message}")
            player.sendMessage(mwm().languageManager.getMessage(player, "error.internal_error"))
        } finally {
            creatingPlayers.remove(player.uniqueId)
        }
    }

    private fun generateUniqueWorldUuid(mwm: MyWorldManager): UUID {
        val usedWarpIds = mwm.worldConfigRepository.findAll().mapTo(mutableSetOf()) { WorldWarpId.of(it.uuid) }
        repeat(100) {
            val uuid = UUID.randomUUID()
            val folderName = "my_world.$uuid"
            if (WorldWarpId.of(uuid) !in usedWarpIds &&
                Bukkit.getWorld(folderName) == null &&
                !mwm.worldService.resolveWorldDirectory(folderName).exists()
            ) {
                return uuid
            }
        }
        error("Unable to allocate unique MyWorld warp id after 100 attempts")
    }

    private fun applyInitialBorder(mwm: MyWorldManager, world: World) {
        val center = world.spawnLocation
        world.worldBorder.center = center
        world.worldBorder.size = mwm.config.getDouble("expansion.initial_size", 100.0)
    }

    private fun placePlatform(world: World) {
        for (x in -2..1) {
            for (z in -2..1) {
                world.getBlockAt(x, PLATFORM_Y, z).setType(Material.SCAFFOLDING, false)
            }
        }
    }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private fun i18n(player: Player?, key: String, vararg placeholders: Pair<String, Any>): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.player_world.easy_creation.$key", placeholders.toMap())

    private fun component(player: Player?, key: String, vararg placeholders: Pair<String, Any>): Component =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.player_world.easy_creation.$key", placeholders.toMap())

    private fun legacy(text: String): Component = LegacyComponentSerializer.legacySection().deserialize(text)

    @Suppress("OVERRIDE_DEPRECATION")
    private object EasyVoidGenerator : ChunkGenerator() {
        override fun shouldGenerateNoise(): Boolean = false
        override fun shouldGenerateSurface(): Boolean = false
        override fun shouldGenerateBedrock(): Boolean = false
        override fun shouldGenerateCaves(): Boolean = false
        override fun shouldGenerateDecorations(): Boolean = false
        override fun shouldGenerateMobs(): Boolean = false
        override fun shouldGenerateStructures(): Boolean = false
        override fun getBaseHeight(worldInfo: WorldInfo, random: java.util.Random, x: Int, z: Int, heightMap: HeightMap): Int =
            worldInfo.minHeight

        override fun getFixedSpawnLocation(world: World, random: java.util.Random): Location =
            Location(world, 0.5, SPAWN_Y.toDouble(), 0.5)
    }

    private companion object {
        private const val NAME_INPUT = "easy_world_name"
        private const val SOURCE_WORLD = "chanpon:easy_void"
        private const val PLATFORM_Y = 64
        private const val SPAWN_Y = 65
        private val NAME_SUBMIT = Key.key("mwmchanpon:easy_creation/name_submit")
        private val NAME_CANCEL = Key.key("mwmchanpon:easy_creation/name_cancel")
        private val CONFIRM_CREATE = Key.key("mwmchanpon:easy_creation/confirm_create")
        private val CONFIRM_BACK = Key.key("mwmchanpon:easy_creation/confirm_back")
        private val DIALOG_KEYS = setOf(NAME_SUBMIT, NAME_CANCEL, CONFIRM_CREATE, CONFIRM_BACK)
        private val EASY_VOID_GENERATOR = EasyVoidGenerator
    }
}
