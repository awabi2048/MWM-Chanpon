package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiStructuredMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.ui.chanponGestureAction
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldCreationOperation
import me.awabi2048.myworldmanager.api.extension.WorldCreationRequest
import me.awabi2048.myworldmanager.api.extension.WorldCreationType
import me.awabi2048.myworldmanager.api.service.ManagedWorldCreationRequest
import me.awabi2048.myworldmanager.api.service.ManagedWorldSpawn
import me.awabi2048.myworldmanager.api.service.WorldPointBillingMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo

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
    private data class PendingCreation(val worldName: String)

    private val pendingCreations = ConcurrentHashMap<UUID, PendingCreation>()
    private val returnRoutes = ConcurrentHashMap<UUID, MenuRoute>()
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = CONFIRM_ROUTE,
                renderer = { context -> renderConfirmation(context.player) },
                actions = mapOf(
                    ACTION_CREATE to MenuActionHandler { context -> confirmCreation(context.player) },
                    ACTION_BACK to MenuActionHandler { context -> returnToNameInput(context.player) },
                ),
                onClose = { context ->
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        val current = CCSystem.getAPI().getMenuNavigationService().currentRoute(context.player)
                        if (current?.owner != OWNER || current.id != CONFIRM_ROUTE) {
                            pendingCreations.remove(context.player.uniqueId)
                            returnRoutes.remove(context.player.uniqueId)
                        }
                    }, 2L)
                },
            ),
        )
    }

    fun start(player: Player, returnRoute: MenuRoute): MenuActionResult {
        if (!canCreate(player)) return MenuActionResult.Rejected()
        pendingCreations.remove(player.uniqueId)
        returnRoutes[player.uniqueId] = returnRoute
        player.sendMessage(i18n(player, "start"))
        showNameDialog(player)
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun cancelFlow(player: Player): MenuActionResult {
        pendingCreations.remove(player.uniqueId)
        val returnRoute = returnRoutes.remove(player.uniqueId)
        return MenuActionResult.Success(
            returnRoute?.let(MenuUpdate::Replace) ?: MenuUpdate.Close,
        )
    }

    private fun handleNameSubmit(
        player: Player,
        nameInput: String,
    ): MenuActionResult {
        val name = nameInput.trim()
        if (name.isBlank()) {
            showNameDialog(
                player,
                name,
                legacy("§c${i18n(player, "name_required")}")
            )
            return MenuActionResult.Success(MenuUpdate.None)
        }

        val worldService = MyWorldManagerApi.getWorldService()
            ?: return MenuActionResult.Rejected()
        worldService.validateWorldName(player, name)?.let { error ->
            showNameDialog(player, name, error)
            return MenuActionResult.Success(MenuUpdate.None)
        }

        if (MyWorldManagerApi.getWorldRepository()
                ?.findByOwnerAndDisplayName(player.uniqueId, name) != null
        ) {
            showNameDialog(
                player,
                name,
                CCSystem.getAPI().getI18nComponent(player, "messages.world_name_duplicate"),
            )
            return MenuActionResult.Success(MenuUpdate.None)
        }

        pendingCreations[player.uniqueId] = PendingCreation(name)
        return MenuActionResult.Success(
            MenuUpdate.Navigate(MenuRoute(OWNER, CONFIRM_ROUTE)),
        )
    }

    private fun showNameDialog(
        player: Player,
        initial: String = "",
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
                ),
                confirm = MenuDialogButton(
                    component(player, "next"),
                    MenuDialogHandler { actor, response ->
                        handleNameSubmit(
                            actor,
                            response.textValue(NAME_INPUT),
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

    private fun renderConfirmation(player: Player): InventoryMenuView {
        val pending = pendingCreations[player.uniqueId]
        val layout = CCSystem.getAPI().getGuiLayoutService().confirmation45()
        if (pending == null) {
            return InventoryMenuView(layout.size, component(player, "confirm_title"), emptyList())
        }
        val preview = guiItem(
            Material.PAPER,
            i18n(player, "confirm_title"),
            GuiLoreSpec.Rich(
                listOf(
                    GuiLoreLine.Text(i18n(player, "confirm_body", "world" to pending.worldName)),
                    GuiLoreLine.Text(
                        i18n(
                            player,
                            "confirm_template",
                            "material" to configuredPlatformMaterial().key.asString(),
                        ),
                    ),
                ),
                GuiLoreFrame.BOTH,
            ),
            GuiElementRole.CONTENT,
        )
        return InventoryMenuView(
            layout.size,
            component(player, "confirm_title"),
            listOf(
                CCSystem.getAPI().getGuiElementService().menuDisplay(
                    GuiMenuDisplaySpec(layout.previewSlot, preview),
                ),
                CCSystem.getAPI().getGuiElementService().menuStructuredEntry(
                    player,
                    GuiStructuredMenuEntrySpec(
                        slot = layout.confirmSlot,
                        item = guiItem(
                        Material.LIME_CONCRETE,
                        i18n(player, "create"),
                        GuiLoreSpec.None,
                        GuiElementRole.CONFIRM,
                        ),
                        actions = listOf(
                            chanponGestureAction(
                                ACTION_CREATE,
                                MenuGesture.ANY,
                                i18n(player, "create"),
                                safety = MenuActionSafety.IRREVERSIBLE,
                            ),
                        ),
                    ),
                ),
                CCSystem.getAPI().getGuiElementService().menuStructuredEntry(
                    player,
                    GuiStructuredMenuEntrySpec(
                        slot = layout.cancelSlot,
                        item = guiItem(
                        Material.RED_CONCRETE,
                        i18n(player, "back"),
                        GuiLoreSpec.None,
                        GuiElementRole.CANCEL,
                        ),
                        actions = listOf(
                            chanponGestureAction(
                                ACTION_BACK,
                                MenuGesture.ANY,
                                i18n(player, "back"),
                                safety = MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun confirmCreation(player: Player): MenuActionResult {
        val pending = pendingCreations.remove(player.uniqueId)
            ?: return MenuActionResult.Rejected()
        returnRoutes.remove(player.uniqueId)
        create(player, pending)
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun returnToNameInput(player: Player): MenuActionResult {
        val pending = pendingCreations[player.uniqueId]
        showNameDialog(
            player,
            pending?.worldName.orEmpty(),
        )
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun create(player: Player, pending: PendingCreation) {
        if (!canCreate(player)) return
        val platformMaterial = configuredPlatformMaterial()
        val worldService = MyWorldManagerApi.getWorldService()
        if (worldService == null) {
            player.sendMessage(CCSystem.getAPI().getI18nString(player, "error.internal_error"))
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
                initializeWorld = { world -> placePlatform(world, platformMaterial) }
            )
        )
    }

    private fun canCreate(player: Player): Boolean {
        if (!player.hasPermission("myworldmanager.world.create") &&
            !player.hasPermission("myworldmanager.admin") &&
            !player.hasPermission("mwmchanpon.admin") &&
            !player.hasPermission("craftercrossing.test")
        ) {
            player.sendMessage(CCSystem.getAPI().getI18nString(player, "no_permission"))
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

    private fun configuredPlatformMaterial(): Material =
        EasyPlatformMaterialPolicy.resolve(
            plugin.config.getString(PLATFORM_MATERIAL_CONFIG_PATH, DEFAULT_PLATFORM_MATERIAL.key.asString()).orEmpty()
        ) ?: error("$PLATFORM_MATERIAL_CONFIG_PATH contains an invalid block material")

    private fun guiItem(
        material: Material,
        name: String,
        lore: GuiLoreSpec,
        role: GuiElementRole,
    ) = GuiItemSpec(
            material = material,
            name = GuiNameSpec.Text(name, GuiNameStyle.DEFAULT),
            lore = lore,
            role = role,
            amount = 1,
        )

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
        const val PLATFORM_MATERIAL_CONFIG_PATH = "creation.easy.platform_material"
        private const val SOURCE_WORLD = "chanpon:easy_void"
        private const val PLATFORM_Y = 64
        private const val SPAWN_Y = 65
        private val DEFAULT_PLATFORM_MATERIAL = EasyPlatformMaterialPolicy.defaultMaterial
        private const val OWNER = "mwm-chanpon"
        private const val NAME_DIALOG = "easy-creation-name"
        private const val CONFIRM_ROUTE = "easy-creation-confirm"
        private const val ACTION_CREATE = "create"
        private const val ACTION_BACK = "back"
        private val EASY_VOID_GENERATOR = EasyVoidGenerator
    }
}
