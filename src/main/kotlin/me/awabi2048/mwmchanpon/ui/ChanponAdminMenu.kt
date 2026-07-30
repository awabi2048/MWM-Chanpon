package me.awabi2048.mwmchanpon.ui
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryAction
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryOption
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiValueTone
import com.awabi2048.ccsystem.api.gui.MenuAcceptedClicks
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.WorldCreationAvailability
import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.AdminMenuCapabilityPlacements
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class ChanponAdminMenu(
    private val plugin: MWMChanponPlugin,
    private val globalStorage: ChanponGlobalStorage,
    private val spawnProtection: SpawnWorldProtectionService,
    private val submissionAdminMenu: SubmissionAdminMenu,
    private val worldDataManagementMenu: WorldDataManagementMenu,
    private val automationSettingsMenu: AutomationSettingsMenu,
    private val worldDataExportMenu: WorldDataExportMenu
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                OWNER,
                ROUTE_MAIN,
                renderer = { context -> renderMain(context.player) },
                actions = mapOf(
                    ACTION_SUBMISSION to MenuActionHandler { context ->
                        MenuActionResult.Success(MenuUpdate.Navigate(submissionAdminMenu.route()))
                    },
                    ACTION_WORLD_DATA to MenuActionHandler { context ->
                        MenuActionResult.Success(MenuUpdate.Navigate(worldDataManagementMenu.route()))
                    },
                    ACTION_AUTOMATION to MenuActionHandler { context ->
                        MenuActionResult.Success(MenuUpdate.Navigate(automationSettingsMenu.route()))
                    },
                    ACTION_EXPORT to MenuActionHandler { context ->
                        MenuActionResult.Success(MenuUpdate.Navigate(worldDataExportMenu.route()))
                    },
                    ACTION_SWITCH_MWM to MenuActionHandler { context ->
                        MyWorldManagerApi.prepareAdminMenuRoute(context.player)
                            ?.let { MenuActionResult.Success(MenuUpdate.Navigate(it)) }
                            ?: MenuActionResult.Rejected()
                    },
                    ACTION_BUILDING_ALLOWED to MenuActionHandler(::toggleBuilding),
                    ACTION_WORLD_CREATION to MenuActionHandler(::cycleWorldCreation),
                    ACTION_SET_SPAWN_WORLD to MenuActionHandler(::showSpawnConfirmation),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                OWNER,
                ROUTE_SPAWN_CONFIRM,
                renderer = { context -> renderSpawnConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_CONFIRM_SPAWN to MenuActionHandler(::confirmSpawn),
                    ACTION_CANCEL_SPAWN to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                ),
            ),
        )
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = OWNER,
                id = MENU_SWITCH_CAPABILITY,
                placement = AdminMenuCapabilityPlacements.MENU_SWITCH,
                availability = MenuCapabilityAvailability {
                    it.player.hasPermission("mwmchanpon.admin")
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    switchToChanponPresentation(it.player)
                },
                actions = listOf(
                    MenuCapabilityAction(
                        id = "open",
                        trigger = MenuCapabilityTrigger.LEFT,
                        textProvider = MenuCapabilityActionTextProvider {
                            CCSystem.getAPI().getI18nString(
                                it.player,
                                "gui.admin_menu.menu_switch.action",
                            )
                        },
                        handler = MenuCapabilityActionHandler {
                            if (!it.player.hasPermission("mwmchanpon.admin")) {
                                MenuActionResult.Rejected(i18nComponent(it.player, "admin.no_permission"))
                            } else {
                                MenuActionResult.Success(MenuUpdate.Navigate(MenuRoute(OWNER, ROUTE_MAIN)))
                            }
                        },
                    ),
                ),
            ),
        )
    }

    fun open(player: Player) {
        if (!player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(i18nComponent(player, "admin.no_permission"))
            return
        }
        runtime.navigate(player, MenuRoute(OWNER, ROUTE_MAIN))
    }

    private fun renderMain(player: Player): InventoryMenuView {
        val elements = mutableListOf(
            MenuElement(19, menuItem(player, Material.LIME_BANNER, "admin.submission", ACTION_SUBMISSION), GuiElementRole.ACTION, ACTION_SUBMISSION),
            MenuElement(21, menuItem(player, Material.CHEST, "admin.world_data", ACTION_WORLD_DATA), GuiElementRole.ACTION, ACTION_WORLD_DATA),
            MenuElement(23, menuItem(player, Material.CLOCK, "admin.automation", ACTION_AUTOMATION), GuiElementRole.ACTION, ACTION_AUTOMATION),
            MenuElement(25, menuItem(player, Material.DISPENSER, "admin.export", ACTION_EXPORT), GuiElementRole.ACTION, ACTION_EXPORT),
            buildingPermissionItem(player),
            worldCreationAvailabilityItem(player),
            switchToMwmItem(player),
        )
        currentWorldUuid(player)?.let { worldUuid ->
            elements += MenuElement(
                ChanponAdminMenuLayout.FOOTER_SPAWN_WORLD,
                spawnWorldItem(player, worldUuid),
                GuiElementRole.ACTION,
                ACTION_SET_SPAWN_WORLD,
                mapOf(WORLD_UUID to worldUuid.toString()),
            )
        }
        return InventoryMenuView(45, i18nComponent(player, "admin.title"), elements)
    }

    private fun toggleBuilding(context: MenuActionContext): MenuActionResult {
        if (!context.click.isLeftClick && !context.click.isRightClick) return MenuActionResult.Ignored
        plugin.setBuildingAllowed(!globalStorage.isBuildingAllowed())
        return MenuActionResult.Success(MenuUpdate.Refresh)
    }

    private fun cycleWorldCreation(context: MenuActionContext): MenuActionResult {
        val direction = GuiCycle.direction(context.click) ?: return MenuActionResult.Ignored
        globalStorage.setWorldCreationAvailability(
            GuiCycle.select(
                globalStorage.getWorldCreationAvailability(),
                WorldCreationAvailability.entries.toList(),
                direction,
            ),
        )
        return MenuActionResult.Success(MenuUpdate.Refresh)
    }

    private fun showSpawnConfirmation(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.payload[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(
            MenuUpdate.Navigate(MenuRoute(OWNER, ROUTE_SPAWN_CONFIRM, mapOf(WORLD_UUID to worldUuid.toString()))),
        )
    }

    private fun renderSpawnConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val worldUuid = route.payload[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: error("スポーンワールドUUIDがありません")
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: error("スポーンワールドが見つかりません: $worldUuid")
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "spawn_world.confirm.title"),
            listOf(
                MenuElement(layout.confirmSlot, confirmButton(player, true), GuiElementRole.CONFIRM, ACTION_CONFIRM_SPAWN),
                MenuElement(
                    layout.previewSlot,
                    menuItem(player, Material.COMPASS, "spawn_world.confirm.center", ACTION_NONE).also {
                it.editMeta { meta ->
                    meta.displayName(i18nComponent(player, "spawn_world.confirm.center.display", mapOf("world" to worldData.name)))
                    meta.lore(ChanponLore.build(i18nList(player, "spawn_world.confirm.center.lore", mapOf("world" to worldData.name)).map(GuiLoreLine::Text)))
                }
            },
                    GuiElementRole.CONTENT,
                ),
                MenuElement(layout.cancelSlot, confirmButton(player, false), GuiElementRole.CANCEL, ACTION_CANCEL_SPAWN),
            ),
        )
    }

    private fun confirmSpawn(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.route.payload[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        globalStorage.setSpawnWorldUuid(worldUuid)
        MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let { worldData ->
            if (!globalStorage.isSpawnPolicyInitialized(worldUuid) && spawnProtection.applyDefaults(worldData)) {
                globalStorage.setSpawnPolicyInitialized(worldUuid)
            }
        }
        context.player.sendMessage(i18nComponent(context.player, "spawn_world.set_success"))
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun menuItem(player: Player, material: Material, key: String, action: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun spawnWorldItem(player: Player, worldUuid: java.util.UUID): ItemStack {
        val configured = globalStorage.getSpawnWorldUuid()
        val key = if (configured == null) "spawn_world.set" else "spawn_world.change"
        return menuItem(player, Material.COMPASS, key, ACTION_SET_SPAWN_WORLD)
    }

    private fun buildingPermissionItem(player: Player): MenuElement {
        val allowed = globalStorage.isBuildingAllowed()
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = ChanponAdminMenuLayout.FOOTER_BUILDING_PERMISSION,
                material = if (allowed) Material.BRICKS else Material.BARRIER,
                name = GuiNameSpec.Text(controlsI18n(player, "build.display"), GuiNameStyle.PRIMARY),
                role = GuiElementRole.ACTION,
                amount = 1,
                description = listOf(controlsI18n(player, if (allowed) "build.allowed_description" else "build.denied_description")),
                data = listOf(GuiMenuEntryData(
                    controlsI18n(player, "build.current"),
                    controlsI18n(player, if (allowed) "build.allowed" else "build.denied"),
                    GuiValueTone.PRIMARY,
                )),
                actions = listOf(GuiMenuEntryAction(
                    ACTION_BUILDING_ALLOWED,
                    MenuAcceptedClicks.LEFT_RIGHT,
                    controlsI18n(player, if (allowed) "build.deny_action" else "build.allow_action"),
                )),
                glint = null
            ),
        )
    }

    private fun worldCreationAvailabilityItem(player: Player): MenuElement {
        val availability = globalStorage.getWorldCreationAvailability()
        val key = availability.name.lowercase()
        val material = when (availability) {
            WorldCreationAvailability.DENIED -> Material.BARRIER
            WorldCreationAvailability.NON_PRODUCTION -> Material.CRAFTING_TABLE
            WorldCreationAvailability.PRODUCTION -> Material.NETHER_STAR
        }
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = ChanponAdminMenuLayout.FOOTER_WORLD_CREATION,
                material = material,
                name = GuiNameSpec.Text(controlsI18n(player, "creation.display"), GuiNameStyle.PRIMARY),
                role = GuiElementRole.ACTION,
                amount = 1,
                description = listOf(controlsI18n(player, "creation.$key.description")),
                data = listOf(GuiMenuEntryData(
                    controlsI18n(player, "creation.current"),
                    controlsI18n(player, "creation.$key.display"),
                    GuiValueTone.PRIMARY,
                )),
                options = WorldCreationAvailability.entries.map { option ->
                    GuiMenuEntryOption(
                        label = controlsI18n(player, "creation.${option.name.lowercase()}.display"),
                        selected = option == availability,
                    )
                },
                warnings = emptyList(),
                dangers = emptyList(),
                actions = listOf(GuiMenuEntryAction(
                    ACTION_WORLD_CREATION,
                    MenuAcceptedClicks.LEFT_RIGHT,
                    controlsI18n(player, "creation.cycle_action"),
                )),
                glint = null
            ),
        )
    }

    private fun confirmButton(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun currentWorldUuid(player: Player): java.util.UUID? {
        return MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid
    }

    private fun infoItem(player: Player): ItemStack {
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin.info.display"))
            meta.lore(ChanponLore.build(i18nList(player, "admin.info.lore", mapOf("version" to plugin.pluginMeta.version)).map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun switchToMwmItem(player: Player): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = ChanponAdminMenuLayout.FOOTER_MAIN_SWITCH,
                material = Material.NETHER_STAR,
                name = GuiNameSpec.Component(i18nComponent(player, "admin.switch_mwm.display")),
                role = GuiElementRole.NAVIGATION,
                description = listOf(i18n(player, "admin.switch_mwm.description")),
                actions = listOf(GuiMenuEntryAction(
                    ACTION_SWITCH_MWM,
                    MenuAcceptedClicks.LEFT_RIGHT,
                    i18n(player, "admin.switch_mwm.action"),
                )),
            ),
        )

    private fun switchToChanponPresentation(player: Player): MenuCapabilityPresentation {
        return MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.NETHER_STAR,
                name = GuiNameSpec.Text(
                    CCSystem.getAPI().getI18nString(
                        player,
                        "gui.admin_menu.menu_switch.display",
                    ),
                    GuiNameStyle.DEFAULT,
                ),
                lore = GuiLoreSpec.Blocks(
                    listOf(
                        GuiLoreBlock(
                            CCSystem.getAPI().getI18nStringList(
                                player,
                                "gui.admin_menu.menu_switch.lore",
                                mapOf("next" to i18n(player, "admin.provider_display")),
                            ).map(GuiLoreLine::Text),
                        ),
                    ),
                ),
                role = GuiElementRole.NAVIGATION,
                amount = 1,
            ),
        )
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)

    private fun controlsI18n(player: Player?, key: String) =
        CCSystem.getAPI().getI18nString(player, "chanpon.controls.$key")

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    private fun structuredBlocks(blocks: List<GuiLoreBlock>): List<Component> =
        CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE_MAIN = "admin"
        private const val ROUTE_SPAWN_CONFIRM = "admin_spawn_confirm"
        private const val WORLD_UUID = "world_uuid"
        private const val TYPE_CONTROL = "chanpon_admin_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_SUBMISSION = "submission"
        private const val ACTION_WORLD_DATA = "world_data"
        private const val ACTION_AUTOMATION = "automation"
        private const val ACTION_EXPORT = "export"
        private const val ACTION_SWITCH_MWM = "switch_mwm"
        private const val ACTION_BUILDING_ALLOWED = "building_allowed"
        private const val ACTION_WORLD_CREATION = "world_creation"
        private const val ACTION_SET_SPAWN_WORLD = "set_spawn_world"
        private const val ACTION_NONE = "none"
        private const val ACTION_CONFIRM_SPAWN = "confirm_spawn"
        private const val ACTION_CANCEL_SPAWN = "cancel_spawn"
        private const val MENU_SWITCH_CAPABILITY = "admin-menu-switch"
    }
}
