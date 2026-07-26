package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldCreationOperation
import me.awabi2048.myworldmanager.api.extension.WorldCreationRequest
import me.awabi2048.myworldmanager.api.extension.WorldCreationType
import me.awabi2048.myworldmanager.api.service.ManagedWorldCreationRequest
import me.awabi2048.myworldmanager.api.service.ManagedWorldSpawn
import me.awabi2048.myworldmanager.api.service.WorldPointBillingMode
import me.awabi2048.myworldmanager.util.WorldNameValidation
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import org.bukkit.plugin.java.JavaPlugin

object EasyPlatformMaterialPolicy {
    val defaultMaterial: Material = Material.OBSIDIAN

    fun resolve(input: String): Material? =
        Material.matchMaterial(input.trim())?.takeIf { it.isBlock && it.isSolid && !it.isAir }
}

/**
 * Chanpon専用の入力とVoid仕様だけを所有し、作成確定はMWM共通パイプラインへ委譲する。
 */
class EasyVoidWorldCreationService(
    private val plugin: MWMChanponPlugin
) {
    private data class PendingCreation(val worldName: String, val platformMaterial: Material)

    private val pendingCreations = ConcurrentHashMap<UUID, PendingCreation>()

    fun start(player: Player) {
        if (!canCreate(player)) return
        pendingCreations.remove(player.uniqueId)
        CCSystem.getAPI().getMenuRuntimeService().close(player)
        player.sendMessage(i18n(player, "start"))
        showNameDialog(player)
    }

    private fun cancelFlow(player: Player): MenuActionResult {
        pendingCreations.remove(player.uniqueId)
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun handleNameSubmit(
        player: Player,
        nameInput: String,
        materialInputValue: String,
    ): MenuActionResult {
        val name = nameInput.trim()
        val materialInput = materialInputValue.trim()
        val platformMaterial = EasyPlatformMaterialPolicy.resolve(materialInput)
        if (name.isBlank()) {
            showNameDialog(
                player,
                name,
                platformMaterial ?: DEFAULT_PLATFORM_MATERIAL,
                legacy("§c${i18n(player, "name_required")}")
            )
            return MenuActionResult.Success(MenuUpdate.None)
        }
        if (platformMaterial == null) {
            showNameDialog(
                player,
                name,
                DEFAULT_PLATFORM_MATERIAL,
                legacy("§c${i18n(player, "platform_material_invalid")}")
            )
            return MenuActionResult.Success(MenuUpdate.None)
        }

        val mwm = mwm()
        when (val validation = mwm.worldValidator.validateName(name)) {
            is WorldNameValidation.Failure -> {
                showNameDialog(player, name, platformMaterial, mwm.languageManager.getComponent(player, validation.messageKey, validation.placeholders))
                return MenuActionResult.Success(MenuUpdate.None)
            }
            WorldNameValidation.Ok -> Unit
        }

        if (mwm.worldConfigRepository.findByOwnerAndDisplayName(player.uniqueId, name) != null) {
            showNameDialog(player, name, platformMaterial, mwm.languageManager.getComponent(player, "messages.world_name_duplicate"))
            return MenuActionResult.Success(MenuUpdate.None)
        }

        pendingCreations[player.uniqueId] = PendingCreation(name, platformMaterial)
        showConfirmDialog(player, name, platformMaterial)
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun showNameDialog(
        player: Player,
        initial: String = "",
        platformMaterial: Material = DEFAULT_PLATFORM_MATERIAL,
        error: Component? = null
    ) {
        CCSystem.getAPI().getMenuDialogService().show(
            player,
            MenuDialogRequest(
                owner = OWNER,
                id = NAME_DIALOG,
                title = component(player, "name_title"),
                body = buildList {
                    add(component(player, "name_body"))
                    if (error != null) add(error)
                },
                inputs = listOf(
                    MenuDialogInput.Text(NAME_INPUT, component(player, "name_label"), initial, maxLength = 32),
                    MenuDialogInput.Text(
                        PLATFORM_MATERIAL_INPUT,
                        component(player, "platform_material_label"),
                        platformMaterial.key.asString(),
                        maxLength = 64,
                    ),
                ),
                confirm = MenuDialogButton(
                    component(player, "next"),
                    MenuDialogHandler { actor, response ->
                        handleNameSubmit(
                            actor,
                            response.textValue(NAME_INPUT),
                            response.textValue(PLATFORM_MATERIAL_INPUT),
                        )
                    },
                ),
                cancel = MenuDialogButton(
                    component(player, "cancel"),
                    MenuDialogHandler { actor, _ -> cancelFlow(actor) },
                ),
            ),
        )
    }

    private fun showConfirmDialog(player: Player, name: String, platformMaterial: Material) {
        CCSystem.getAPI().getMenuDialogService().show(
            player,
            MenuDialogRequest(
                owner = OWNER,
                id = CONFIRM_DIALOG,
                title = component(player, "confirm_title"),
                body = listOf(
                    component(player, "confirm_body", "world" to name),
                    component(player, "confirm_template", "material" to platformMaterial.key.asString()),
                ),
                confirm = MenuDialogButton(
                    component(player, "create"),
                    MenuDialogHandler { actor, _ ->
                        pendingCreations.remove(actor.uniqueId)?.let { create(actor, it) }
                        MenuActionResult.Success(MenuUpdate.Close)
                    },
                ),
                cancel = MenuDialogButton(
                    component(player, "back"),
                    MenuDialogHandler { actor, _ ->
                        val pending = pendingCreations[actor.uniqueId]
                        showNameDialog(
                            actor,
                            pending?.worldName.orEmpty(),
                            pending?.platformMaterial ?: DEFAULT_PLATFORM_MATERIAL,
                        )
                        MenuActionResult.Success(MenuUpdate.None)
                    },
                ),
            ),
        )
    }

    private fun create(player: Player, pending: PendingCreation) {
        if (!canCreate(player)) return
        val worldService = MyWorldManagerApi.getWorldService()
        if (worldService == null) {
            player.sendMessage(mwm().languageManager.getMessage(player, "error.internal_error"))
            return
        }
        worldService.createManagedWorld(
            ManagedWorldCreationRequest(
                ownerUuid = player.uniqueId,
                worldName = pending.worldName,
                sourceId = SOURCE_WORLD,
                type = WorldCreationType.RANDOM,
                environment = World.Environment.NORMAL,
                worldType = WorldType.FLAT,
                generator = EASY_VOID_GENERATOR,
                generateStructures = false,
                initialSpawn = ManagedWorldSpawn(0, SPAWN_Y, 0),
                cost = 0,
                billingMode = WorldPointBillingMode.NONE,
                initializeWorld = { world -> placePlatform(world, pending.platformMaterial) }
            )
        ).thenAccept { success ->
            if (success) {
                CCSystem.getAPI().getMenuNavigationService().clear(player)
            }
        }
    }

    private fun canCreate(player: Player): Boolean {
        if (!player.hasPermission("myworldmanager.world.create") &&
            !player.hasPermission("myworldmanager.admin") &&
            !player.hasPermission("mwmchanpon.admin") &&
            !player.hasPermission("craftercrossing.test")
        ) {
            player.sendMessage(mwm().languageManager.getMessage(player, "no_permission"))
            return false
        }
        val decision = MyWorldManagerApi.checkWorldCreation(
            WorldCreationRequest(
                actor = player,
                player = player,
                operation = WorldCreationOperation.NORMAL,
                type = WorldCreationType.RANDOM
            )
        )
        if (!decision.allowed) decision.denialMessage?.let(player::sendMessage)
        return decision.allowed
    }

    private fun placePlatform(world: World, platformMaterial: Material) {
        for (x in -2..1) {
            for (z in -2..1) {
                world.getBlockAt(x, PLATFORM_Y, z).setType(platformMaterial, false)
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
        private const val PLATFORM_MATERIAL_INPUT = "easy_platform_material"
        private const val SOURCE_WORLD = "chanpon:easy_void"
        private const val PLATFORM_Y = 64
        private const val SPAWN_Y = 65
        private val DEFAULT_PLATFORM_MATERIAL = EasyPlatformMaterialPolicy.defaultMaterial
        private const val OWNER = "mwm-chanpon"
        private const val NAME_DIALOG = "easy-creation-name"
        private const val CONFIRM_DIALOG = "easy-creation-confirm"
        private val EASY_VOID_GENERATOR = EasyVoidGenerator
    }
}
