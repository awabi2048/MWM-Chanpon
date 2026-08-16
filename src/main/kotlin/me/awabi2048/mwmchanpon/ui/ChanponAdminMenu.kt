package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.LocalizationKey

import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys
import com.awabi2048.ccsystem.api.localization.generated.ChanponPhaseKeys

import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiAdminKeys
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiInteractionGuidance
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryOption
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiValueTone
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
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
import me.awabi2048.mwmchanpon.service.ChanponReversibleContracts
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.mwmchanpon.service.ChanponReversibleStateProviders
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.AdminMenuCapabilityPlacements
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

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
        ChanponReversibleStateProviders.registerAdminWorldControls(
            CCSystem.getAPI().getMenuReversibleStateProviderRegistry(),
            globalStorage,
            spawnProtection,
        )
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
                availability = reasonedAvailability(
                    reason = { i18nComponent(it.player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_NO_PERMISSION) },
                ) {
                    it.player.hasPermission("mwmchanpon.admin")
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    switchToChanponPresentation(it.player)
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "open",
                        trigger = MenuCapabilityTrigger.ANY,
                        textProvider = MenuCapabilityActionTextProvider {
                            CCSystem.getAPI().getLocalized(
                                it.player,
                                MyworldGuiAdminKeys.GUI_ADMIN_MENU_MENU_SWITCH_ACTION,
                            )
                        },
                        handler = MenuCapabilityActionHandler {
                            if (!it.player.hasPermission("mwmchanpon.admin")) {
                                MenuActionResult.Rejected(i18nComponent(it.player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_NO_PERMISSION))
                            } else {
                                MenuActionResult.Success(MenuUpdate.Navigate(MenuRoute(OWNER, ROUTE_MAIN)))
                            }
                        },
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                ),
            ),
        )
    }

    fun open(player: Player) {
        if (!player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_NO_PERMISSION))
            return
        }
        runtime.navigate(player, MenuRoute(OWNER, ROUTE_MAIN))
    }

    private fun renderMain(player: Player): InventoryMenuView {
        val elements = mutableListOf(
            actionEntry(player, 19, Material.LIME_BANNER, adminPresentation(ACTION_SUBMISSION), ACTION_SUBMISSION),
            actionEntry(player, 21, Material.CHEST, adminPresentation(ACTION_WORLD_DATA), ACTION_WORLD_DATA),
            actionEntry(player, 23, Material.CLOCK, adminPresentation(ACTION_AUTOMATION), ACTION_AUTOMATION),
            actionEntry(player, 25, Material.DISPENSER, adminPresentation(ACTION_EXPORT), ACTION_EXPORT),
            buildingPermissionItem(player),
            worldCreationAvailabilityItem(player),
            switchToMwmItem(player),
        )
        currentWorldUuid(player)?.let { worldUuid ->
            val configured = globalStorage.getSpawnWorldUuid()
            val key = if (configured == null) {
                AdminPresentation(ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_SET_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_SET_LORE, ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_SET_ACTION)
            } else {
                AdminPresentation(ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_CHANGE_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_CHANGE_LORE, ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_CHANGE_ACTION)
            }
            elements += actionEntry(
                player,
                ChanponAdminMenuLayout.FOOTER_SPAWN_WORLD,
                Material.COMPASS,
                key,
                ACTION_SET_SPAWN_WORLD,
                mapOf(WORLD_UUID to worldUuid.toString()),
            )
        }
        return InventoryMenuView(45, i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_TITLE), elements)
    }

    private fun toggleBuilding(context: MenuActionContext): MenuActionResult {
        if (!plugin.isConfigurationMigrationCurrent()) return MenuActionResult.Rejected()
        plugin.setBuildingAllowed(!globalStorage.isBuildingAllowed())
        return MenuActionResult.Success(MenuUpdate.Refresh)
    }

    private fun cycleWorldCreation(context: MenuActionContext): MenuActionResult {
        val direction = GuiCycle.direction(context.click) ?: return MenuActionResult.Ignored
        if (!plugin.isConfigurationMigrationCurrent()) return MenuActionResult.Rejected()
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
            i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_CONFIRM_TITLE),
            listOf(
                confirmationEntry(
                    player,
                    layout.confirmSlot,
                    true,
                    ACTION_CONFIRM_SPAWN,
                    ChanponReversibleContracts.adminWorldControls("spawn", worldUuid.toString()),
                    ChanponReversibleActionClassification.Operation.SPAWN_WORLD_SET,
                ),
                CCSystem.getAPI().getGuiElementService().menuDisplay(
                    com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec(
                        layout.previewSlot,
                        GuiItemSpec(
                            Material.COMPASS,
                            GuiNameSpec.FixedLabel(
                                i18nComponent(
                                    player,
                                    ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_CONFIRM_CENTER_DISPLAY,
                                    mapOf("world" to worldData.name),
                                ),
                            ),
                            me.awabi2048.mwmchanpon.ui.framedLore(
                                i18nList(
                                    player,
                                    ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_CONFIRM_CENTER_LORE,
                                    mapOf("world" to worldData.name),
                                ).map(GuiLoreLine::Text),
                                com.awabi2048.ccsystem.api.gui.GuiLoreFrame.BOTH,
                            ),
                            GuiElementRole.CONTENT,
                            1,
                        ),
                    ),
                ),
                confirmationEntry(player, layout.cancelSlot, false, ACTION_CANCEL_SPAWN),
            ),
        ).asConfirmation()
    }

    private fun confirmSpawn(context: MenuActionContext): MenuActionResult {
        if (!plugin.isConfigurationMigrationCurrent()) return MenuActionResult.Rejected()
        val worldUuid = context.route.payload[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        globalStorage.setSpawnWorldUuid(worldUuid)
        MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let { worldData ->
            if (!globalStorage.isSpawnPolicyInitialized(worldUuid) && spawnProtection.applyDefaults(worldData)) {
                globalStorage.setSpawnPolicyInitialized(worldUuid)
            }
        }
        context.player.sendMessage(i18nComponent(context.player, ChanponBackupKeys.CHANPON_BACKUP_SPAWN_WORLD_SET_SUCCESS))
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun actionEntry(
        player: Player,
        slot: Int,
        material: Material,
        presentation: AdminPresentation,
        actionId: String,
        payload: Map<String, String> = emptyMap(),
    ): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = material,
                name = GuiNameSpec.FixedLabel(i18nComponent(player, presentation.display)),
                role = GuiElementRole.ACTION,
                description = i18nList(player, presentation.lore),
                actions = listOf(
                    chanponGestureAction(
                        actionId,
                        MenuGesture.ANY,
                        i18n(player, presentation.action),
                        payload,
                        safety = adminActionSafety(actionId),
                        reversibleContract = adminReversibleContract(actionId),
                        classification = adminClassification(actionId),
                    ),
                ),
            ),
        )

    private fun buildingPermissionItem(player: Player): MenuElement {
        val allowed = globalStorage.isBuildingAllowed()
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = ChanponAdminMenuLayout.FOOTER_BUILDING_PERMISSION,
                material = if (allowed) Material.BRICKS else Material.BARRIER,
                name = me.awabi2048.mwmchanpon.ui.fixedName(i18n(player, ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_DISPLAY), GuiNameStyle.PRIMARY),
                role = GuiElementRole.ACTION,
                amount = 1,
                description = listOf(i18n(player, if (allowed) ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_ALLOWED_DESCRIPTION else ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_DENIED_DESCRIPTION)),
                data = listOf(GuiMenuEntryData(
                    i18n(player, ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_CURRENT),
                    i18n(player, if (allowed) ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_ALLOWED else ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_DENIED),
                    GuiValueTone.PRIMARY,
                )),
                actions = listOf(chanponGestureAction(
                    ACTION_BUILDING_ALLOWED,
                    MenuGesture.ANY,
                    i18n(player, if (allowed) ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_DENY_ACTION else ChanponPhaseKeys.CHANPON_CONTROLS_BUILD_ALLOW_ACTION),
                    safety = MenuActionSafety.REVERSIBLE,
                    reversibleContract = ChanponReversibleContracts.adminWorldControls("building"),
                    classification = ChanponReversibleActionClassification.Operation.BUILDING_TOGGLE,
                )),
                glint = null
            ),
        )
    }

    private fun worldCreationAvailabilityItem(player: Player): MenuElement {
        val availability = globalStorage.getWorldCreationAvailability()
        val presentation = creationPresentation(availability)
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
                name = me.awabi2048.mwmchanpon.ui.fixedName(i18n(player, ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_DISPLAY), GuiNameStyle.PRIMARY),
                role = GuiElementRole.ACTION,
                amount = 1,
                description = listOf(i18n(player, presentation.description)),
                data = listOf(GuiMenuEntryData(
                    i18n(player, ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_CURRENT),
                    i18n(player, presentation.display),
                    GuiValueTone.PRIMARY,
                )),
                options = WorldCreationAvailability.entries.map { option ->
                    GuiMenuEntryOption(
                        label = i18n(player, creationPresentation(option).display),
                        selected = option == availability,
                    )
                },
                warnings = emptyList(),
                dangers = emptyList(),
                actions = listOf(chanponCycleAction(
                    ACTION_WORLD_CREATION,
                    i18n(player, ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_CYCLE_ACTION),
                    safety = MenuActionSafety.REVERSIBLE,
                    reversibleContract = ChanponReversibleContracts.adminWorldControls("creation"),
                    classification = ChanponReversibleActionClassification.Operation.CREATION_AVAILABILITY_CYCLE,
                )),
                interactionGuidance = GuiInteractionGuidance.LIST_SETTING,
                glint = null
            ),
        )
    }

    private fun confirmationEntry(
        player: Player,
        slot: Int,
        confirm: Boolean,
        actionId: String,
        reversibleContract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract? = null,
        classification: ChanponReversibleActionClassification.Operation? = null,
    ): MenuElement {
        val key = if (confirm) ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CONFIRM else ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CANCEL
        val actionKey = if (confirm) ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CONFIRM_ACTION else ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CANCEL_ACTION
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE,
                name = GuiNameSpec.FixedLabel(i18nComponent(player, key)),
                role = if (confirm) GuiElementRole.CONFIRM else GuiElementRole.CANCEL,
                actions = listOf(
                    chanponGestureAction(
                        actionId,
                        MenuGesture.ANY,
                        i18n(player, actionKey),
                        safety = adminActionSafety(actionId),
                        reversibleContract = reversibleContract,
                        classification = classification,
                    ),
                ),
            ),
        )
    }

    private fun currentWorldUuid(player: Player): java.util.UUID? {
        return MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid
    }

    private fun adminActionSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_SUBMISSION,
        ACTION_WORLD_DATA,
        ACTION_AUTOMATION,
        ACTION_EXPORT,
        ACTION_SWITCH_MWM,
        ACTION_CANCEL_SPAWN -> MenuActionSafety.NAVIGATION_ONLY
        ACTION_SET_SPAWN_WORLD -> MenuActionSafety.CONFIRM_ENTRY
        ACTION_BUILDING_ALLOWED,
        ACTION_WORLD_CREATION,
        ACTION_CONFIRM_SPAWN -> MenuActionSafety.REVERSIBLE
        else -> error("Chanpon admin action must declare a dedicated safety mapping: $actionId")
    }

    private fun adminReversibleContract(actionId: String) = when (actionId) {
        ACTION_BUILDING_ALLOWED -> ChanponReversibleContracts.adminWorldControls("building")
        ACTION_WORLD_CREATION -> ChanponReversibleContracts.adminWorldControls("creation")
        else -> null
    }

    private fun adminClassification(actionId: String) = when (actionId) {
        ACTION_BUILDING_ALLOWED -> ChanponReversibleActionClassification.Operation.BUILDING_TOGGLE
        ACTION_WORLD_CREATION -> ChanponReversibleActionClassification.Operation.CREATION_AVAILABILITY_CYCLE
        else -> null
    }

    private fun switchToMwmItem(player: Player): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = ChanponAdminMenuLayout.FOOTER_MAIN_SWITCH,
                material = Material.NETHER_STAR,
                name = GuiNameSpec.FixedLabel(i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_SWITCH_MWM_DISPLAY)),
                role = GuiElementRole.NAVIGATION,
                description = listOf(i18n(player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_SWITCH_MWM_DESCRIPTION)),
                actions = listOf(chanponGestureAction(
                    ACTION_SWITCH_MWM,
                    MenuGesture.ANY,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_SWITCH_MWM_ACTION),
                    safety = MenuActionSafety.NAVIGATION_ONLY,
                )),
            ),
        )

    private fun switchToChanponPresentation(player: Player): MenuCapabilityPresentation {
        return MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.NETHER_STAR,
                name = me.awabi2048.mwmchanpon.ui.fixedName(
                    CCSystem.getAPI().getLocalized(
                        player,
                        MyworldGuiAdminKeys.GUI_ADMIN_MENU_MENU_SWITCH_DISPLAY,
                    ),
                    GuiNameStyle.DEFAULT,
                ),
                lore = GuiLoreSpec.Blocks(
                    listOf(
                        GuiLoreBlock(
                            CCSystem.getAPI().getLocalized(
                                player,
                                MyworldGuiAdminKeys.GUI_ADMIN_MENU_MENU_SWITCH_LORE,
                                mapOf("next" to i18n(player, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_PROVIDER_DISPLAY)),
                            ).map(GuiLoreLine::Text),
                        ),
                    ),
                ),
                role = GuiElementRole.NAVIGATION,
                amount = 1,
            ),
        )
    }

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, key, placeholders)

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun i18nList(player: Player?, key: LocalizationKey<List<String>>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun adminPresentation(actionId: String): AdminPresentation = when (actionId) {
        ACTION_SUBMISSION -> AdminPresentation(ChanponBackupKeys.CHANPON_BACKUP_ADMIN_SUBMISSION_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_SUBMISSION_LORE, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_SUBMISSION_ACTION)
        ACTION_WORLD_DATA -> AdminPresentation(ChanponBackupKeys.CHANPON_BACKUP_ADMIN_WORLD_DATA_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_WORLD_DATA_LORE, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_WORLD_DATA_ACTION)
        ACTION_AUTOMATION -> AdminPresentation(ChanponBackupKeys.CHANPON_BACKUP_ADMIN_AUTOMATION_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_AUTOMATION_LORE, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_AUTOMATION_ACTION)
        ACTION_EXPORT -> AdminPresentation(ChanponBackupKeys.CHANPON_BACKUP_ADMIN_EXPORT_DISPLAY, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_EXPORT_LORE, ChanponBackupKeys.CHANPON_BACKUP_ADMIN_EXPORT_ACTION)
        else -> error("未定義の管理操作IDです: $actionId")
    }

    private fun creationPresentation(availability: WorldCreationAvailability): CreationPresentation = when (availability) {
        WorldCreationAvailability.DENIED -> CreationPresentation(ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_DENIED_DISPLAY, ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_DENIED_DESCRIPTION)
        WorldCreationAvailability.NON_PRODUCTION -> CreationPresentation(ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_NON_PRODUCTION_DISPLAY, ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_NON_PRODUCTION_DESCRIPTION)
        WorldCreationAvailability.PRODUCTION -> CreationPresentation(ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_PRODUCTION_DISPLAY, ChanponPhaseKeys.CHANPON_CONTROLS_CREATION_PRODUCTION_DESCRIPTION)
    }

    private data class AdminPresentation(
        val display: LocalizationKey<String>,
        val lore: LocalizationKey<List<String>>,
        val action: LocalizationKey<String>,
    )

    private data class CreationPresentation(
        val display: LocalizationKey<String>,
        val description: LocalizationKey<String>,
    )

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
        private const val ACTION_CONFIRM_SPAWN = "confirm_spawn"
        private const val ACTION_CANCEL_SPAWN = "cancel_spawn"
        private const val MENU_SWITCH_CAPABILITY = "admin-menu-switch"
    }
}
