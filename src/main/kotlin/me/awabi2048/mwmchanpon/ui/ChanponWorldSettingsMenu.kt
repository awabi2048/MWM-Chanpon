package me.awabi2048.mwmchanpon.ui
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiCycleDirection
import com.awabi2048.ccsystem.api.gui.GuiConfirmationLayout
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuIconAction
import com.awabi2048.ccsystem.api.gui.GuiMenuIconData
import com.awabi2048.ccsystem.api.gui.GuiMenuIconSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryAction
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuAcceptedClicks
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuInteraction
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.extension.ChanponWorldSettingsPlacements
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsAction
import me.awabi2048.myworldmanager.api.extension.WorldSettingsActionRequest
import me.awabi2048.myworldmanager.api.extension.WorldSettingsNavigationRequest
import me.awabi2048.myworldmanager.api.extension.WorldSettingsRouteProvider
import me.awabi2048.myworldmanager.api.service.ExpansionExecutionMode
import me.awabi2048.myworldmanager.api.service.ExpansionSequenceOptions
import me.awabi2048.myworldmanager.api.service.ExpansionSequencePhase
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class ChanponWorldSettingsMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val environmentGui: ChanponEnvironmentGui
) : WorldSettingsRouteProvider {
    private val accessGuard = WorldSettingsAccessGuard(storage, protectionService)
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_SETTINGS,
                renderer = { context -> render(context.player, source(context.route)) },
                actions = mapOf(
                    ACTION_SELECT to MenuActionHandler(::handleAction),
                ),
            )
        )
    }

    override fun prepare(
        player: Player,
        worldData: WorldData,
        request: WorldSettingsNavigationRequest,
    ): MenuRoute =
        route(Source(Screen.MAIN, worldData.uuid, request))

    private fun render(player: Player, source: Source): InventoryMenuView {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return InventoryMenuView(9, Component.text("World settings"), emptyList())
        return when (source.screen) {
            Screen.MAIN -> renderMain(player, worldData, source.request)
            Screen.EXPANSION -> renderExpansion(player, worldData)
            Screen.MODIFIED_WARNING -> renderModifiedWarning(player)
            Screen.CONFIRM -> renderConfirmation(player, worldData, source)
            Screen.CRITICAL -> renderCritical(player, worldData)
            Screen.DELETE_CONFIRM -> renderDeleteConfirmation(player, worldData, source.finalStep)
        }
    }


    private fun handleAction(context: MenuActionContext): MenuActionResult {
        val source = source(context.route)
        val player = context.player
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return MenuActionResult.Rejected()
        return when (context.payload[OPERATION]) {
            OPERATION_EDIT_INFO ->
                executeMwmAction(player, worldData, WorldSettingsAction.EDIT_INFO, context.click)
            OPERATION_SELECT_ICON ->
                executeMwmAction(player, worldData, WorldSettingsAction.SELECT_ICON, context.click)
            OPERATION_SET_SPAWN ->
                executeMwmAction(player, worldData, WorldSettingsAction.SET_SPAWN, context.click)
            OPERATION_MEMBERS ->
                executeMwmAction(player, worldData, WorldSettingsAction.MANAGE_MEMBERS, context.click)
            OPERATION_ANNOUNCEMENT ->
                executeMwmAction(player, worldData, WorldSettingsAction.EDIT_ANNOUNCEMENT, context.click)
            OPERATION_TOUR ->
                executeMwmAction(player, worldData, WorldSettingsAction.MANAGE_TOUR, context.click)
            OPERATION_PORTALS ->
                executeMwmAction(player, worldData, WorldSettingsAction.MANAGE_PORTALS, context.click)
            OPERATION_WARP ->
                executeMwmAction(player, worldData, WorldSettingsAction.WARP, context.click)
            OPERATION_BACK -> MenuActionResult.Success(MenuUpdate.Back)
            OPERATION_EXPAND_CENTER -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                confirmResultByPlatform(player, worldData, ConfirmAction.EXPAND, null)
            }
            OPERATION_EXPAND_DIRECTION -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                val started = MyWorldManagerApi.getWorldService()?.startWorldBorderExpansionSequence(
                    player,
                    source.worldUuid,
                    ExpansionSequenceOptions(
                        ExpansionSequencePhase.DIRECTION_SELECT,
                        null,
                        ExpansionExecutionMode.IMMEDIATE_NO_COST,
                        setOf(ExpansionSequencePhase.METHOD_SELECT),
                    ),
                ) == true
                if (!started) {
                    player.sendMessage(i18nComponent(player, "message.expand_failed"))
                    MenuActionResult.Rejected()
                } else {
                    MenuActionResult.Success(MenuUpdate.None)
                }
            }
            OPERATION_STEP_BACK -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                if (worldData.latestBorderExpansionRecord()?.modified == true) {
                    MenuActionResult.Success(
                        MenuUpdate.Navigate(
                            route(
                                Source(
                                    Screen.MODIFIED_WARNING,
                                    worldData.uuid,
                                    confirmAction = ConfirmAction.STEP_BACK,
                                )
                            )
                        )
                    )
                } else {
                    confirmResultByPlatform(player, worldData, ConfirmAction.STEP_BACK, null)
                }
            }
            OPERATION_CONFIRM -> when (source.screen) {
                Screen.MODIFIED_WARNING -> {
                    if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                        return MenuActionResult.Rejected()
                    }
                    val action = source.confirmAction ?: return MenuActionResult.Rejected()
                    confirmResultByPlatform(player, worldData, action, null)
                }
                Screen.CONFIRM -> {
                    if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                        return MenuActionResult.Rejected()
                    }
                    executeConfirmAction(
                        player,
                        worldData,
                        source.confirmAction ?: return MenuActionResult.Rejected(),
                        source.direction,
                        external = false,
                    )
                }
                Screen.DELETE_CONFIRM -> confirmDelete(player, source, worldData)
                else -> MenuActionResult.Ignored
            }
            OPERATION_DELETE -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                if (storage.isProductionWorld(source.worldUuid)) return MenuActionResult.Rejected()
                MenuActionResult.Success(
                    MenuUpdate.Navigate(
                        route(Source(Screen.DELETE_CONFIRM, worldData.uuid, finalStep = false)),
                    ),
                )
            }
            else -> MenuActionResult.Ignored
        }
    }

    private fun executeMwmAction(
        player: Player,
        worldData: WorldData,
        action: WorldSettingsAction,
        click: ClickType,
    ): MenuActionResult =
        MyWorldManagerApi.executeWorldSettingsAction(
            WorldSettingsActionRequest(player, worldData.uuid, action, click),
        )

    private fun addFeature(
        elements: MutableList<MenuElement>,
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
        slot: Int,
    ) {
        val placement = when (feature) {
            WorldSettingsFeature.EXPANSION ->
                ChanponWorldSettingsPlacements.EXPANSION
            WorldSettingsFeature.PUBLISH ->
                ChanponWorldSettingsPlacements.PUBLISH
            WorldSettingsFeature.NOTIFICATION ->
                ChanponWorldSettingsPlacements.NOTIFICATION
            WorldSettingsFeature.ENVIRONMENT ->
                ChanponWorldSettingsPlacements.ENVIRONMENT
            WorldSettingsFeature.CRITICAL ->
                ChanponWorldSettingsPlacements.CRITICAL
        }
        val service = CCSystem.getAPI().getMenuCapabilityService()
        val arguments = mapOf(WORLD_UUID to worldData.uuid.toString())
        val capability = service.definitions(placement)
            .firstNotNullOfOrNull {
                service.resolve(it.capabilityId, player, arguments)
            } ?: return
        val accepted = capability.acceptedClicks - ClickType.MIDDLE
        elements += MenuElement(
            slot,
            CCSystem.getAPI().getGuiElementService().menuCapability(capability.presentation),
            if (capability.actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT,
            interaction = if (capability.actionable) {
                MenuInteraction.Capability(
                    capabilityId = capability.capabilityId,
                    arguments = arguments,
                    acceptedClicks = accepted,
                )
            } else {
                MenuInteraction.DisplayOnly
            },
        )
    }

    private fun addFooterCapabilities(
        elements: MutableList<MenuElement>,
        player: Player,
        worldData: WorldData,
        size: Int,
        owner: Boolean,
        protected: Boolean,
    ) {
        val arguments = mapOf(WORLD_UUID to worldData.uuid.toString())
        addCapabilitiesAt(
            elements,
            player,
            ChanponWorldSettingsPlacements.FOOTER,
            listOf(51),
            arguments,
        )
        addCapabilitiesAt(
            elements,
            player,
            ChanponWorldSettingsPlacements.FOOTER_LEFT,
            listOf(ChanponWorldSettingsLayout.linkedPortalSlot(size, owner, protected)),
            arguments,
        )
        addCapabilitiesAt(
            elements,
            player,
            ChanponWorldSettingsPlacements.FOOTER_RIGHT,
            listOf(size - 4),
            arguments,
        )
    }

    private fun addCapabilitiesAt(
        elements: MutableList<MenuElement>,
        player: Player,
        placement: String,
        slots: List<Int>,
        arguments: Map<String, String>,
    ) {
        val service = CCSystem.getAPI().getMenuCapabilityService()
        service.definitions(placement)
            .asSequence()
            .mapNotNull { service.resolve(it.capabilityId, player, arguments) }
            .zip(slots.asSequence())
            .forEach { (capability, slot) ->
                elements += MenuElement(
                    slot,
                    CCSystem.getAPI().getGuiElementService().menuCapability(capability.presentation),
                    if (capability.actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT,
                    interaction = if (capability.actionable) {
                        MenuInteraction.Capability(
                            capabilityId = capability.capabilityId,
                            arguments = arguments,
                            acceptedClicks = capability.acceptedClicks,
                        )
                    } else {
                        MenuInteraction.DisplayOnly
                    },
                )
            }
    }

    private fun settingsAction(
        slot: Int,
        item: ItemStack,
        operation: String,
        worldAction: WorldSettingsAction,
        player: Player,
        worldData: WorldData,
    ): MenuElement {
        val contract = MyWorldManagerApi.getWorldSettingsActionContract(player, worldData.uuid, worldAction)
        val actionable = contract?.actionable == true
        val acceptedClicks = contract?.acceptedClicks.orEmpty()
        return MenuElement(
            slot,
            item,
            if (actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT,
            interaction = if (actionable) {
                MenuInteraction.Action(
                    ACTION_SELECT,
                    acceptedClicks,
                    mapOf(OPERATION to operation),
                    contract.sounds,
                )
            } else {
                MenuInteraction.DisplayOnly
            },
        )
    }

    private fun renderMain(
        player: Player,
        worldData: WorldData,
        request: WorldSettingsNavigationRequest,
    ): InventoryMenuView {
        val admin = MyWorldManagerApi.hasWorldSettingsAdminAccess(player)
        val actualOwner = worldData.owner == player.uniqueId || admin
        val moderator = player.uniqueId in worldData.moderators
        val member = player.uniqueId in worldData.members
        val imported = storage.isImportedArchive(worldData.uuid)
        val owner = actualOwner && !imported
        val manager = (owner || moderator) && !imported
        val protected = protectionService.isProtected(worldData)
        val memberLayout = member && !manager
        val size = if (protected || memberLayout) PROTECTED_SETTINGS_SIZE else 54
        val elements = mutableListOf<MenuElement>()

        if (protected && owner) {
            addFeature(elements, player, worldData, WorldSettingsFeature.PUBLISH, 21)
            addFeature(elements, player, worldData, WorldSettingsFeature.NOTIFICATION, 22)
        } else if (manager) {
            elements += mwmActionEntry(player, 19, worldData, WorldSettingsAction.EDIT_INFO, "info", Material.NAME_TAG, OPERATION_EDIT_INFO, listOf(i18n(player, "mwm_action.action")))
            elements += mwmActionEntry(player, 20, worldData, WorldSettingsAction.SELECT_ICON, "icon", Material.ANVIL, OPERATION_SELECT_ICON, listOf(i18n(player, "mwm_action.action")))
            elements += mwmActionEntry(
                player,
                21,
                worldData,
                WorldSettingsAction.SET_SPAWN,
                "spawn",
                Material.COMPASS,
                OPERATION_SET_SPAWN,
                if (MyWorldManagerApi.getBedrockFormService()?.isBedrock(player) == true) {
                    listOf(text(player, "gui.settings.spawn.action.select_type"))
                } else {
                    listOf(
                        text(player, "gui.settings.spawn.action.set_guest"),
                        text(player, "gui.settings.spawn.action.set_member"),
                    )
                },
                requireTargetWorld = true,
            )
            elements += mwmActionEntry(
                player,
                28,
                worldData,
                WorldSettingsAction.EDIT_ANNOUNCEMENT,
                "announcement",
                Material.OAK_SIGN,
                OPERATION_ANNOUNCEMENT,
                listOf(
                    text(player, "gui.settings.announcement.action.set_message"),
                    text(player, "gui.settings.announcement.action.reset_message"),
                ),
            )
            addFeature(elements, player, worldData, WorldSettingsFeature.NOTIFICATION, 29)
            elements += mwmActionEntry(
                player,
                47,
                worldData,
                WorldSettingsAction.MANAGE_TOUR,
                "tour",
                Material.PALE_OAK_BOAT,
                OPERATION_TOUR,
                listOf(i18n(player, "mwm_action.action")),
                "gui.tour.worldmenu",
            )
        }
        if (owner && !protected) {
            addFeature(elements, player, worldData, WorldSettingsFeature.EXPANSION, 23)
            addFeature(elements, player, worldData, WorldSettingsFeature.PUBLISH, 24)
            elements += mwmActionEntry(player, 25, worldData, WorldSettingsAction.MANAGE_MEMBERS, "member", Material.PLAYER_HEAD, OPERATION_MEMBERS, listOf(i18n(player, "mwm_action.action")))
            addFeature(elements, player, worldData, WorldSettingsFeature.ENVIRONMENT, 32)
            addFeature(elements, player, worldData, WorldSettingsFeature.CRITICAL, 33)
            val hasPortals = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) != null &&
                MyWorldManagerApi.getWorldService() != null &&
                hasWorldPortals(worldData)
            if (hasPortals) {
                elements += mwmActionEntry(
                    player,
                    ChanponWorldSettingsLayout.PORTAL_MANAGEMENT_SLOT,
                    worldData,
                    WorldSettingsAction.MANAGE_PORTALS,
                    "portals",
                    Material.END_PORTAL_FRAME,
                    OPERATION_PORTALS,
                    listOf(i18n(player, "mwm_action.action")),
                )
            }
        }

        elements += worldInfoElement(player, worldData)
        if (CCSystem.getAPI().getMenuNavigationService().canGoBack(player)) {
            elements += MenuElement(
                ChanponGuiElements.backSlot(size),
                returnItem(player),
                GuiElementRole.BACK,
                interaction = MenuInteraction.Back(),
            )
        }
        addFooterCapabilities(elements, player, worldData, size, owner, protected)

        return InventoryMenuView(
            size,
            CCSystem.getAPI().getI18nComponent(
                player,
                "gui.settings.title",
                mapOf("world" to worldData.name),
            ),
            elements,
            playerInventoryInteraction =
                com.awabi2048.ccsystem.api.gui.PlayerInventoryInteraction.INTERACTIVE,
        )
    }

    private fun confirmDelete(
        player: Player,
        source: Source,
        worldData: WorldData,
    ): MenuActionResult {
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
            return MenuActionResult.Rejected()
        }
        if (!source.finalStep) {
            return MenuActionResult.Success(
                MenuUpdate.Navigate(
                    route(Source(Screen.DELETE_CONFIRM, worldData.uuid, finalStep = true)),
                ),
            )
        }
        val worldService = MyWorldManagerApi.getWorldService()
            ?: return MenuActionResult.Rejected()
        worldService.deleteWorld(source.worldUuid).thenAccept { success ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.sendMessage(
                    i18nComponent(
                        player,
                        if (success) "critical.deleted" else "critical.delete_failed",
                    ),
                )
                if (success) MyWorldManagerApi.closeWorldSettingsContext(player)
            })
        }
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun openExpansionMenu(player: Player, worldData: WorldData): MenuActionResult =
        MenuActionResult.Success(
            MenuUpdate.Navigate(route(Source(Screen.EXPANSION, worldData.uuid)))
        )

    private fun openCriticalMenu(player: Player, worldData: WorldData): MenuActionResult {
        if (criticalUnavailableLoreKey(player, worldData) != null) {
            player.sendMessage(i18nComponent(player, "critical.unavailable_message"))
            return MenuActionResult.Rejected()
        }
        return MenuActionResult.Success(
            MenuUpdate.Navigate(route(Source(Screen.CRITICAL, worldData.uuid)))
        )
    }

    private fun confirmResultByPlatform(
        player: Player,
        worldData: WorldData,
        action: ConfirmAction,
        direction: BlockFace?,
    ): MenuActionResult {
        if (MyWorldManagerApi.getBedrockFormService()?.isBedrock(player) == true) {
            return if (openBedrockConfirmForm(player, worldData, action, direction)) {
                MenuActionResult.Success(MenuUpdate.None)
            } else {
                MenuActionResult.Rejected()
            }
        }
        return MenuActionResult.Success(
            MenuUpdate.Navigate(
                route(
                    Source(
                        Screen.CONFIRM,
                        worldData.uuid,
                        confirmAction = action,
                        direction = direction,
                    ),
                )
            )
        )
    }

    private fun openBedrockConfirmForm(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?): Boolean {
        val directionName = direction?.let { directionName(player, it) } ?: i18n(player, "direction.center")
        val modified = action == ConfirmAction.STEP_BACK && worldData.latestBorderExpansionRecord()?.modified == true
        val forms = MyWorldManagerApi.getBedrockFormService() ?: return false
        val opened = forms.sendSimpleForm(
            player = player,
            title = i18n(player, "confirm.${action.key}.title"),
            content = confirmContentLines(player, action, directionName, modified).joinToString("\n"),
            buttons = listOf(
                i18n(player, "confirm.button.confirm"),
                i18n(player, "confirm.button.cancel")
            ),
            onSelect = { index ->
                val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid)
                    ?: return@sendSimpleForm MenuActionResult.Success(MenuUpdate.Close)
                if (index == 0) {
                    executeConfirmAction(player, latest, action, direction, external = true)
                } else {
                    MenuActionResult.Success(MenuUpdate.Resume)
                }
            },
            onClosed = { MenuActionResult.Success(MenuUpdate.Resume) },
        )
        return opened
    }

    private fun executeConfirmAction(
        player: Player,
        worldData: WorldData,
        action: ConfirmAction,
        direction: BlockFace?,
        external: Boolean,
    ): MenuActionResult {
        val service = MyWorldManagerApi.getWorldService()
        val success = when (action) {
            ConfirmAction.EXPAND -> service?.expandWorldBorder(worldData.uuid, direction) == true
            ConfirmAction.STEP_BACK -> service?.stepBackWorldBorder(worldData.uuid) == true
        }
        player.sendMessage(i18nComponent(player, if (success) action.successKey else action.failedKey))
        when {
            success && action == ConfirmAction.EXPAND -> {
                MyWorldManagerApi.closeWorldSettingsContext(player)
                return MenuActionResult.Success(MenuUpdate.Close)
            }
            external -> return MenuActionResult.Success(MenuUpdate.Resume)
            else -> return MenuActionResult.Success(MenuUpdate.Back)
        }
    }

    private fun renderExpansion(player: Player, worldData: WorldData): InventoryMenuView {
        val elements = mutableListOf(
            actionElement(
                20,
                expansionOptionItem(player, "center", Material.MAP),
                OPERATION_EXPAND_CENTER,
            ),
            actionElement(
                24,
                expansionOptionItem(player, "direction_select", Material.COMPASS),
                OPERATION_EXPAND_DIRECTION,
            ),
            actionElement(
                ChanponGuiElements.backSlot(45),
                returnItem(player),
                OPERATION_BACK,
                GuiElementRole.BACK,
            ),
        )
        if (worldData.latestBorderExpansionRecord() != null) {
            elements += actionElement(42, stepBackItem(player, worldData), OPERATION_STEP_BACK)
        }
        return InventoryMenuView(45, i18nComponent(player, "expand_menu.title"), elements)
    }

    private fun renderModifiedWarning(player: Player): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "modified_warning.title"),
            confirmationElements(
                layout,
                modifiedWarningItem(player),
                warningButton(player, true),
                warningButton(player, false),
            ),
        )
    }

    private fun renderConfirmation(player: Player, worldData: WorldData, source: Source): InventoryMenuView {
        val action = source.confirmAction ?: ConfirmAction.EXPAND
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "confirm.${action.key}.title"),
            confirmationElements(
                layout,
                confirmCenterItem(player, worldData, action, source.direction),
                confirmButton(player, true),
                confirmButton(player, false),
            ),
        )
    }

    private fun renderCritical(player: Player, worldData: WorldData): InventoryMenuView =
        InventoryMenuView(
            45,
            i18nComponent(player, "critical.menu_title"),
            listOf(
                actionElement(
                    ChanponGuiElements.backSlot(45),
                    returnItem(player),
                    OPERATION_BACK,
                    GuiElementRole.BACK,
                ),
                actionElement(22, deleteWorldItem(player), OPERATION_DELETE),
            ),
        )

    private fun renderDeleteConfirmation(
        player: Player,
        worldData: WorldData,
        finalStep: Boolean,
    ): InventoryMenuView {
        val key = if (finalStep) "critical.final" else "critical.confirm"
        val layout = ChanponGuiElements.confirmationLayout()
        val lore = if (finalStep) {
            loreBlocks(listOf(GuiLoreBlock(listOf(
                GuiLoreLine.Text(i18n(player, "critical.final_description")),
                GuiLoreLine.Danger(i18n(player, "critical.final_danger"))
            ))))
        } else {
            loreBlocks(listOf(GuiLoreBlock(listOf(
                GuiLoreLine.Data(i18n(player, "critical.confirm_target_label"), worldData.name, "§e"),
                GuiLoreLine.Danger(i18n(player, "critical.confirm_danger"))
            ))))
        }
        val centerItem = componentItem(
            Material.TNT,
            i18n(player, "${key}_display"),
            lore,
        )
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "${key}_title"),
            confirmationElements(
                layout,
                centerItem,
                confirmButton(player, true),
                confirmButton(player, false),
            ),
        )
    }

    private fun publishPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        val visibility = storage.getVisibility(worldData.uuid)
        val display = visibilityName(player, visibility)
        val options = listOf(
            Triple(ChanponWorldVisibility.PUBLIC, visibilityName(player, ChanponWorldVisibility.PUBLIC), "\u00A7a"),
            Triple(ChanponWorldVisibility.DEVELOPMENT, visibilityName(player, ChanponWorldVisibility.DEVELOPMENT), "\u00A7e"),
            Triple(ChanponWorldVisibility.PRIVATE, visibilityName(player, ChanponWorldVisibility.PRIVATE), "\u00A77")
        )
        val description = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> i18n(player, "publish.public_description")
            ChanponWorldVisibility.DEVELOPMENT -> i18n(player, "publish.development_description")
            ChanponWorldVisibility.PRIVATE -> i18n(player, "publish.private_description")
        }
        val lore = CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(buildList {
                add(GuiLoreLine.Data(
                    i18n(player, "publish.current_label"),
                    display,
                    options.first { it.first == visibility }.third
                ))
                add(GuiLoreLine.Text(description))
                add(GuiLoreLine.Spacer)
                options.forEach { (option, displayName, selectedColor) ->
                    val selected = option == visibility
                    add(GuiLoreLine.Option(displayName, selected, selectedColor, "\u00A77"))
                }
            }, GuiLoreFrame.BOTH)
        )
        return capabilityPresentation(Material.OAK_SIGN, i18n(player, "publish.title"), lore)
    }

    private fun expansionPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        val level = worldData.borderExpansionLevel
        val max = maxExpansionLevel()
        val lines = if (level >= max) {
            i18nList(player, "expand.status_max").map(GuiLoreLine::Text)
        } else {
            emptyList()
        }
        return capabilityPresentation(
            Material.FILLED_MAP,
            i18n(player, "expand.display"),
            describedStructuredLore(
                player,
                textList(player, "gui.settings.expand.blocks.description").map(GuiLoreLine::Text),
                lines,
                requireTargetWorld = true,
                worldData = worldData
            ),
        )
    }

    private fun notificationPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        val status = i18n(player, if (worldData.notificationEnabled) "common.on" else "common.off")
        return MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.BELL,
                name = GuiNameSpec.Text(
                    text(player, "gui.settings.notification.display"),
                    GuiNameStyle.DEFAULT,
                ),
                lore = GuiLoreSpec.Blocks(
                    listOf(
                        GuiLoreBlock(
                            textList(player, "gui.settings.notification.blocks.description")
                                .map(GuiLoreLine::Text),
                        ),
                        GuiLoreBlock(
                            listOf(
                                GuiLoreLine.Data(
                                    i18n(player, "notification.current_label"),
                                    status,
                                    "§e",
                                ),
                            ),
                        ),
                    ),
                ),
                role = GuiElementRole.ACTION,
                amount = 1,
            ),
            glint = worldData.notificationEnabled,
        )
    }

    private fun criticalPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        criticalUnavailableLoreKey(player, worldData)?.let { loreKey ->
            return criticalUnavailablePresentation(player, loreKey)
        }
        return capabilityPresentation(
            Material.TNT,
            i18n(player, "critical.display"),
            describedStructuredLore(
                player,
                textList(player, "gui.settings.critical.blocks.summary").map(GuiLoreLine::Text),
                emptyList(),
                requireTargetWorld = true,
                worldData = worldData,
            ),
        )
    }

    private fun criticalUnavailableLoreKey(player: Player, worldData: WorldData): String? {
        // 重大な設定に入れない理由を一覧表示と遷移防御で共有し、専用メニューへの侵入を防ぐ。
        if (storage.isProductionWorld(worldData.uuid)) return "critical.production_warning"
        if (!MyWorldManagerApi.isCriticalSettingsEnabled(player.uniqueId)) {
            return "critical.disabled_warning"
        }
        return null
    }

    private fun deleteWorldItem(player: Player): ItemStack {
        val lore = CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(listOf(
            GuiLoreBlock(listOf(
                GuiLoreLine.Text(i18n(player, "critical.delete_description")),
                GuiLoreLine.Danger(i18n(player, "critical.delete_danger"))
            )),
            GuiLoreBlock(listOf(singleClick(player, i18n(player, "critical.delete_action"))))
        )))
        return componentItem(Material.TNT, i18n(player, "critical.delete_display"), lore)
    }

    private fun criticalUnavailablePresentation(
        player: Player,
        loreKey: String,
    ): MenuCapabilityPresentation {
        return capabilityPresentation(
            Material.BARRIER,
            i18n(player, "critical.unavailable_display"),
            describedStructuredLore(
                player,
                textList(player, "gui.settings.critical.blocks.summary").map(GuiLoreLine::Text),
                listOf(GuiLoreLine.Warning(i18n(player, loreKey))),
            ),
            role = GuiElementRole.CONTENT,
        )
    }

    private fun mwmPresentation(
        player: Player,
        worldData: WorldData,
        iconKey: String,
        material: Material,
        langPrefix: String = "gui.settings.$iconKey",
        requireTargetWorld: Boolean = false,
    ): MenuCapabilityPresentation {
        val display = text(player, "$langPrefix.display")
        val lore = describedStructuredLore(
            player,
            mwmDescription(player, iconKey),
            emptyList(),
            requireTargetWorld = requireTargetWorld,
            worldData = worldData,
        )
        val icon = MyWorldManagerApi.getConfiguredMenuIcon("world_settings", iconKey, material)
        return capabilityPresentation(icon, display, lore)
    }

    private fun mwmActionEntry(
        player: Player,
        slot: Int,
        worldData: WorldData,
        action: WorldSettingsAction,
        iconKey: String,
        material: Material,
        operation: String,
        actionTexts: List<String>,
        langPrefix: String = "gui.settings.$iconKey",
        requireTargetWorld: Boolean = false,
    ): MenuElement {
        val contract = requireNotNull(
            MyWorldManagerApi.getWorldSettingsActionContract(player, worldData.uuid, action),
        ) { "Missing world settings action contract: $action/${worldData.uuid}" }
        require(contract.options.size == actionTexts.size) {
            "World settings action presentation mismatch: $action options=${contract.options.size} texts=${actionTexts.size}"
        }
        val actionable = contract.actionable &&
            (!requireTargetWorld || isCurrentWorld(player, worldData))
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = MyWorldManagerApi.getConfiguredMenuIcon("world_settings", iconKey, material),
                name = GuiNameSpec.Text(text(player, "$langPrefix.display"), GuiNameStyle.DEFAULT),
                role = if (actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT,
                description = mwmDescription(player, iconKey).map { (it as GuiLoreLine.Text).text },
                warnings = if (requireTargetWorld && !isCurrentWorld(player, worldData)) {
                    listOf(i18n(player, "common.must_be_in_world_warning"))
                } else {
                    emptyList()
                },
                actions = contract.options.zip(actionTexts).map { (option, actionText) ->
                    GuiMenuEntryAction(
                        ACTION_SELECT,
                        option.acceptedClicks,
                        actionText,
                        mapOf(OPERATION to operation),
                        enabled = actionable,
                    )
                },
                sounds = contract.sounds,
            ),
        )
    }

    private fun mwmActionItem(
        player: Player,
        worldData: WorldData,
        action: WorldSettingsAction,
        iconKey: String,
        material: Material,
        langPrefix: String = "gui.settings.$iconKey",
        requireTargetWorld: Boolean = false,
    ): ItemStack {
        val lore = describedStructuredLore(
            player,
            mwmDescription(player, iconKey),
            contractActionLines(
                player,
                worldData,
                action,
                listOf(i18n(player, "mwm_action.action")),
            ),
            requireTargetWorld,
            worldData,
        )
        return componentItem(
            MyWorldManagerApi.getConfiguredMenuIcon("world_settings", iconKey, material),
            text(player, "$langPrefix.display"),
            lore,
        )
    }

    private fun spawnItem(player: Player, worldData: WorldData): ItemStack {
        val actionTexts = if (MyWorldManagerApi.getBedrockFormService()?.isBedrock(player) == true) {
            listOf(text(player, "gui.settings.spawn.action.select_type"))
        } else {
            listOf(
                text(player, "gui.settings.spawn.action.set_guest"),
                text(player, "gui.settings.spawn.action.set_member"),
            )
        }
        val actions = contractActionLines(
            player,
            worldData,
            WorldSettingsAction.SET_SPAWN,
            actionTexts,
        )
        return componentItem(
            MyWorldManagerApi.getConfiguredMenuIcon("world_settings", "spawn", Material.COMPASS),
            text(player, "gui.settings.spawn.display"),
            describedStructuredLore(
                player,
                mwmDescription(player, "spawn"),
                actions,
                requireTargetWorld = true,
                worldData = worldData,
            ),
        )
    }

    private fun announcementItem(player: Player, worldData: WorldData): ItemStack =
        componentItem(
            MyWorldManagerApi.getConfiguredMenuIcon(
                "world_settings",
                "announcement",
                Material.OAK_SIGN,
            ),
            text(player, "gui.settings.announcement.display"),
            describedStructuredLore(
                player,
                mwmDescription(player, "announcement"),
                contractActionLines(
                    player,
                    worldData,
                    WorldSettingsAction.EDIT_ANNOUNCEMENT,
                    listOf(
                        text(player, "gui.settings.announcement.action.set_message"),
                        text(player, "gui.settings.announcement.action.reset_message"),
                    ),
                ),
                worldData = worldData,
            ),
        )

    private fun worldInfoElement(player: Player, worldData: WorldData): MenuElement {
        val outside = !isCurrentWorld(player, worldData)
        val warpContract = MyWorldManagerApi.getWorldSettingsActionContract(
            player,
            worldData.uuid,
            WorldSettingsAction.WARP,
        )
        val actionable = outside && warpContract?.actionable == true
        val lore = CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Blocks(
                buildList {
                    add(
                        GuiLoreBlock(
                            listOf(
                                GuiLoreLine.Data(
                                    i18n(player, "info.description_label"),
                                    worldData.description.ifBlank { "-" },
                                    "§f",
                                ),
                            ),
                        ),
                    )
                    if (outside) {
                        add(
                            GuiLoreBlock(
                                listOf(
                                    contractActionLines(
                                        player,
                                        worldData,
                                        WorldSettingsAction.WARP,
                                        listOf(text(player, "gui.player_world.world_item.warp")),
                                    ).single(),
                                ),
                            ),
                        )
                    }
                },
            ),
        )
        return MenuElement(
            4,
            componentItem(worldData.icon, "§e${worldData.name}", lore),
            if (actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT,
            interaction = if (actionable) {
                MenuInteraction.Action(
                    ACTION_SELECT,
                    warpContract.acceptedClicks,
                    mapOf(OPERATION to OPERATION_WARP),
                    warpContract.sounds,
                )
            } else {
                MenuInteraction.DisplayOnly
            },
        )
    }

    private fun hasWorldPortals(worldData: WorldData): Boolean =
        MyWorldManagerApi.hasWorldPortals(worldData.uuid)

    private fun capabilityPresentation(
        material: Material,
        display: String,
        lore: List<Component>,
        role: GuiElementRole = GuiElementRole.ACTION,
    ) = MenuCapabilityPresentation(
        GuiItemSpec(
            material = material,
            name = GuiNameSpec.Text(display, GuiNameStyle.DEFAULT),
            lore = if (lore.isEmpty()) {
                GuiLoreSpec.None
            } else {
                GuiLoreSpec.Rich(lore.map(GuiLoreLine::Component), GuiLoreFrame.NONE)
            },
            role = role,
            amount = 1,
        ),
    )

    /**
     * アイコンの表示案内と受理するクリックを同じ定義に保持する。
     * 左右で意味が分かれる設定を単一クリック表示へ退化させないための正本。
     */



    private fun mwmDescription(player: Player, iconKey: String): List<GuiLoreLine> {
        val key = when (iconKey) {
            "info" -> "gui.settings.info.blocks.summary"
            "icon" -> "gui.settings.icon.blocks.description"
            "spawn" -> "gui.settings.spawn.blocks.description"
            "announcement" -> "gui.settings.announcement.blocks.description"
            "member" -> "gui.settings.member.blocks.description"
            "environment" -> "gui.settings.environment.blocks.summary"
            "tour" -> "gui.tour.worldmenu.blocks.description"
            "portals" -> "gui.settings.portals.blocks.summary"
            else -> return emptyList()
        }
        return textList(player, key).map(GuiLoreLine::Text)
    }

    private fun describedStructuredLore(
        player: Player,
        description: List<GuiLoreLine>,
        existing: List<GuiLoreLine>,
        requireTargetWorld: Boolean = false,
        worldData: WorldData? = null
    ): List<Component> {
        val outsideTargetWorld = requireTargetWorld && worldData != null && !isCurrentWorld(player, worldData)
        val actionLines = if (outsideTargetWorld) emptyList() else existing
        return CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(
                buildList {
                    if (outsideTargetWorld) {
                        add(GuiLoreLine.Warning(i18n(player, "common.must_be_in_world_warning")))
                    }
                    addAll(description)
                    if (description.isNotEmpty() && actionLines.any { it != GuiLoreLine.Spacer }) {
                        add(GuiLoreLine.Spacer)
                    }
                    addAll(actionLines)
                },
                GuiLoreFrame.BOTH
            )
        )
    }

    private fun expansionOptionItem(
        player: Player,
        key: String,
        material: Material,
    ): ItemStack =
        componentItem(
            material,
            i18n(player, "expand_menu.$key.display"),
            loreBlocks(listOf(
                GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "expand_menu.$key.description")))),
                GuiLoreBlock(listOf(singleClick(player, i18n(player, "expand_menu.$key.action"))))
            )),
        )

    private fun stepBackItem(player: Player, worldData: WorldData): ItemStack {
        val modified = worldData.latestBorderExpansionRecord()?.modified == true
        val lines = buildList {
            add(GuiLoreLine.Text(i18n(player, "step_back.description")))
            if (modified) add(GuiLoreLine.Warning(i18n(player, "step_back.modified_warning")))
        }
        return componentItem(
            Material.RECOVERY_COMPASS,
            i18n(player, "step_back.display"),
            loreBlocks(listOf(
                GuiLoreBlock(lines),
                GuiLoreBlock(listOf(singleClick(player, i18n(player, "step_back.action"))))
            )),
        )
    }

    private fun modifiedWarningItem(player: Player): ItemStack {
        return componentItem(
            Material.REDSTONE_TORCH,
            i18n(player, "modified_warning.display"),
            loreBlocks(listOf(
                GuiLoreBlock(listOf(
                    GuiLoreLine.Warning(i18n(player, "modified_warning.description")),
                    GuiLoreLine.Text(i18n(player, "modified_warning.consequence"))
                )),
                GuiLoreBlock(listOf(singleClick(player, i18n(player, "modified_warning.action"))))
            )),
        )
    }

    private fun confirmCenterItem(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?): ItemStack {
        val directionName = direction?.let { directionName(player, it) } ?: i18n(player, "direction.center")
        val modified = action == ConfirmAction.STEP_BACK && worldData.latestBorderExpansionRecord()?.modified == true
        val lines = when (action) {
            ConfirmAction.EXPAND -> listOf(
                GuiLoreLine.Data(i18n(player, "confirm.expand.direction_label"), directionName, "§e"),
                GuiLoreLine.Text(i18n(player, "confirm.expand.description"))
            )
            ConfirmAction.STEP_BACK -> buildList {
                add(GuiLoreLine.Text(i18n(player, "confirm.step_back.description")))
                if (modified) {
                    add(GuiLoreLine.Warning(i18n(player, "confirm.step_back.modified_warning")))
                }
            }
        }
        return componentItem(
            action.material,
            i18n(player, "confirm.${action.key}.display"),
            loreBlocks(listOf(GuiLoreBlock(lines))),
        )
    }

    private fun confirmContentLines(player: Player, action: ConfirmAction, directionName: String, modified: Boolean): List<String> {
        return when (action) {
            ConfirmAction.EXPAND -> listOf(
                "${i18n(player, "confirm.expand.direction_label")}: $directionName",
                i18n(player, "confirm.expand.description")
            )
            ConfirmAction.STEP_BACK -> buildList {
                add(i18n(player, "confirm.step_back.description"))
                if (modified) add(i18n(player, "confirm.step_back.modified_warning"))
            }
        }
    }

    private fun confirmButton(player: Player, confirm: Boolean): ItemStack {
        return item(
            if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            i18n(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel"),
            emptyList(),
        )
    }

    private fun warningButton(player: Player, continueAction: Boolean): ItemStack {
        return item(
            if (continueAction) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            i18n(player, if (continueAction) "modified_warning.button.continue" else "modified_warning.button.cancel"),
            emptyList(),
        )
    }

    private fun returnItem(player: Player): ItemStack {
        return item(Material.REDSTONE, i18n(player, "common.back"), emptyList())
    }

    private fun item(
        material: Material,
        display: String,
        loreLines: List<GuiLoreLine>,
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(display))
            if (loreLines.isNotEmpty()) {
                meta.lore(ChanponLore.build(loreLines))
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        return item
    }

    private fun componentItem(
        material: Material,
        display: String,
        loreComponents: List<Component>,
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(display))
            if (loreComponents.isNotEmpty()) {
                // loreComponents are already the final output of CC-System's LoreService.
                meta.lore(loreComponents)
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        return item
    }

    private fun isCurrentWorld(player: Player, worldData: WorldData): Boolean =
        MyWorldManagerApi.getWorldService()?.isPlayerInWorld(player, worldData) == true

    private fun maxExpansionLevel(): Int {
        return plugin.config.getInt("world_expansion.max_level", 3).coerceAtLeast(0)
    }

    private fun loreBlocks(blocks: List<GuiLoreBlock>): List<Component> {
        return CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(blocks))
    }

    private fun directionName(player: Player, direction: BlockFace): String {
        return i18n(player, "direction.${direction.name.lowercase()}")
    }

    private fun visibilityName(player: Player?, visibility: ChanponWorldVisibility): String {
        return i18n(player, "visibility.${visibility.storageValue}")
    }

    private fun cycleVisibility(
        current: ChanponWorldVisibility,
        direction: GuiCycleDirection
    ): ChanponWorldVisibility {
        return GuiCycle.select(
            current,
            listOf(
            ChanponWorldVisibility.PUBLIC,
            ChanponWorldVisibility.DEVELOPMENT,
            ChanponWorldVisibility.PRIVATE
            ),
            direction
        )
    }

    private fun text(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, key)

    private fun textList(player: Player, key: String): List<String> =
        CCSystem.getAPI().getI18nStringList(player, key)

    private fun singleClick(player: Player, action: String): GuiLoreLine.SingleAction {
        val operation = text(player, "lore.click.any")
        return GuiLoreLine.SingleAction(
            operation,
            action,
            textWithPlaceholders(
                player,
                "lore.action_single_with_operation",
                mapOf("operation" to operation, "action" to action),
            ),
        )
    }

    private fun contractActionLines(
        player: Player,
        worldData: WorldData,
        action: WorldSettingsAction,
        actionTexts: List<String>,
    ): List<GuiLoreLine> {
        val contract = requireNotNull(
            MyWorldManagerApi.getWorldSettingsActionContract(player, worldData.uuid, action),
        ) { "Missing world settings action contract: $action/${worldData.uuid}" }
        require(contract.options.size == actionTexts.size) {
            "World settings action presentation mismatch: $action options=${contract.options.size} texts=${actionTexts.size}"
        }
        val actionService = CCSystem.getAPI().getGuiActionService()
        val resolved = contract.options.zip(actionTexts)
        return if (resolved.size == 1) {
            resolved.map { (option, actionText) ->
                actionService.single(
                    player,
                    actionService.clickLabel(player, option.displayClick),
                    actionText,
                )
            }
        } else {
            resolved.map { (option, actionText) ->
                GuiLoreLine.Action(
                    actionService.clickLabel(player, option.displayClick),
                    actionText,
                )
            }
        }
    }

    private fun cycleAction(player: Player): GuiLoreLine.Action =
        GuiLoreLine.Action(
            text(player, "lore.click.left_right"),
            text(player, "gui.common.action.cycle"),
        )

    private fun textWithPlaceholders(
        player: Player,
        key: String,
        placeholders: Map<String, Any>,
    ): String = CCSystem.getAPI().getI18nString(player, key, placeholders)

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.settings.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.settings.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.settings.$key", placeholders)
    }

    internal fun isFeatureVisible(
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
    ): Boolean {
        if (storage.isImportedArchive(worldData.uuid)) return false
        val owner = worldData.owner == player.uniqueId ||
            player.hasPermission("myworldmanager.admin")
        val moderator = worldData.moderators.contains(player.uniqueId)
        if (protectionService.isProtected(worldData)) {
            return owner && feature in setOf(
                WorldSettingsFeature.PUBLISH,
                WorldSettingsFeature.NOTIFICATION,
            )
        }
        return when (feature) {
            WorldSettingsFeature.NOTIFICATION -> owner || moderator
            else -> owner
        }
    }

    internal fun isFeatureActionable(
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
    ): Boolean {
        if (!isFeatureVisible(player, worldData, feature)) return false
        val inWorld = isCurrentWorld(player, worldData)
        return when (feature) {
            WorldSettingsFeature.EXPANSION ->
                inWorld &&
                    worldData.borderExpansionLevel != WorldData.EXPANSION_LEVEL_SPECIAL &&
                    worldData.borderExpansionLevel < maxExpansionLevel()
            WorldSettingsFeature.ENVIRONMENT,
            WorldSettingsFeature.CRITICAL -> inWorld
            WorldSettingsFeature.PUBLISH,
            WorldSettingsFeature.NOTIFICATION -> true
        }
    }

    internal fun renderFeature(
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
    ): MenuCapabilityPresentation = when (feature) {
        WorldSettingsFeature.EXPANSION -> expansionPresentation(player, worldData)
        WorldSettingsFeature.PUBLISH -> publishPresentation(player, worldData)
        WorldSettingsFeature.NOTIFICATION -> notificationPresentation(player, worldData)
        WorldSettingsFeature.ENVIRONMENT -> mwmPresentation(
            player,
            worldData,
            "environment",
            Material.GRASS_BLOCK,
            requireTargetWorld = true,
        )
        WorldSettingsFeature.CRITICAL -> criticalPresentation(player, worldData)
    }

    internal fun actionTrigger(feature: WorldSettingsFeature):
        com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger =
        if (feature in setOf(
                WorldSettingsFeature.PUBLISH,
                WorldSettingsFeature.EXPANSION,
                WorldSettingsFeature.NOTIFICATION,
            )
        ) {
            com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger.LEFT_RIGHT
        } else {
            com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger.LEFT
        }

    internal fun featureActionText(
        player: Player,
        feature: WorldSettingsFeature,
    ): String = when (feature) {
        WorldSettingsFeature.PUBLISH -> text(player, "gui.common.action.cycle")
        WorldSettingsFeature.EXPANSION ->
            text(player, "gui.settings.expand.action.open_menu")
        WorldSettingsFeature.NOTIFICATION ->
            i18n(player, "notification.action")
        WorldSettingsFeature.ENVIRONMENT ->
            i18n(player, "mwm_action.action")
        WorldSettingsFeature.CRITICAL ->
            i18n(player, "critical.action")
    }

    internal fun handleFeature(
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
        click: org.bukkit.event.inventory.ClickType,
    ): MenuActionResult {
        if (!isFeatureActionable(player, worldData, feature)) return MenuActionResult.Ignored
        return when (feature) {
            WorldSettingsFeature.EXPANSION -> {
                if (MyWorldManagerApi.getBedrockFormService()?.isBedrock(player) == true) {
                    confirmResultByPlatform(player, worldData, ConfirmAction.EXPAND, null)
                } else {
                    openExpansionMenu(player, worldData)
                }
            }
            WorldSettingsFeature.PUBLISH -> {
                val next = cycleVisibility(
                    storage.getVisibility(worldData.uuid),
                    GuiCycle.direction(click) ?: return MenuActionResult.Ignored,
                )
                storage.setVisibility(worldData.uuid, next)
                MenuActionResult.Success(MenuUpdate.Refresh)
            }
            WorldSettingsFeature.NOTIFICATION -> {
                if (MyWorldManagerApi.toggleWorldVisitNotification(player, worldData.uuid)) {
                    MenuActionResult.Success(MenuUpdate.Refresh)
                } else {
                    MenuActionResult.Rejected()
                }
            }
            WorldSettingsFeature.ENVIRONMENT -> {
                MenuActionResult.Success(
                    MenuUpdate.Navigate(environmentGui.route(worldData.uuid)),
                )
            }
            WorldSettingsFeature.CRITICAL -> openCriticalMenu(player, worldData)
        }
    }

    private fun confirmationElements(
        layout: GuiConfirmationLayout,
        center: ItemStack,
        confirm: ItemStack,
        cancel: ItemStack,
    ): List<MenuElement> = listOf(
        MenuElement(
            layout.previewSlot,
            center,
            GuiElementRole.CONTENT,
            interaction = MenuInteraction.DisplayOnly,
        ),
        actionElement(
            layout.confirmSlot,
            confirm,
            OPERATION_CONFIRM,
            GuiElementRole.CONFIRM,
        ),
        actionElement(
            layout.cancelSlot,
            cancel,
            OPERATION_BACK,
            GuiElementRole.CANCEL,
        ),
    )

    private fun actionElement(
        slot: Int,
        item: ItemStack,
        operation: String,
        role: GuiElementRole = GuiElementRole.ACTION,
    ) = MenuElement(
        slot = slot,
        item = item,
        role = role,
        interaction = MenuInteraction.Action(
            actionId = ACTION_SELECT,
            payload = mapOf(OPERATION to operation),
        ),
    )

    private data class Source(
        val screen: Screen,
        val worldUuid: UUID,
        val request: WorldSettingsNavigationRequest = WorldSettingsNavigationRequest(),
        val confirmAction: ConfirmAction? = null,
        val direction: BlockFace? = null,
        val finalStep: Boolean = false,
    )

    private enum class Screen {
        MAIN,
        EXPANSION,
        MODIFIED_WARNING,
        CONFIRM,
        CRITICAL,
        DELETE_CONFIRM,
    }

    private fun route(source: Source): MenuRoute =
        MenuRoute(RUNTIME_OWNER, ROUTE_SETTINGS, payload(source))

    private fun source(route: MenuRoute): Source {
        val payload = route.payload
        return Source(
            screen = payload[PAYLOAD_SCREEN]
                ?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
                ?: Screen.EXPANSION,
            worldUuid = payload[PAYLOAD_WORLD_UUID]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID(0L, 0L),
            request = WorldSettingsNavigationRequest(
                showBackButton = payload[PAYLOAD_SHOW_BACK]?.toBooleanStrictOrNull() ?: false,
                isAdminFlow =
                    payload[PAYLOAD_ADMIN_FLOW]?.toBooleanStrictOrNull() ?: false,
                isPlayerWorldFlow = payload[PAYLOAD_PLAYER_FLOW]?.toBooleanStrictOrNull(),
                parentShowBackButton = payload[PAYLOAD_PARENT_BACK]?.toBooleanStrictOrNull(),
            ),
            confirmAction = payload[PAYLOAD_CONFIRM_ACTION]
                ?.let { runCatching { ConfirmAction.valueOf(it) }.getOrNull() },
            direction = payload[PAYLOAD_DIRECTION]
                ?.let { runCatching { BlockFace.valueOf(it) }.getOrNull() },
            finalStep = payload[PAYLOAD_FINAL_STEP]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun payload(source: Source): Map<String, String> = buildMap {
        put(PAYLOAD_SCREEN, source.screen.name)
        put(PAYLOAD_WORLD_UUID, source.worldUuid.toString())
        put(PAYLOAD_SHOW_BACK, source.request.showBackButton.toString())
        put(PAYLOAD_ADMIN_FLOW, source.request.isAdminFlow.toString())
        source.request.isPlayerWorldFlow?.let { put(PAYLOAD_PLAYER_FLOW, it.toString()) }
        source.request.parentShowBackButton?.let { put(PAYLOAD_PARENT_BACK, it.toString()) }
        source.confirmAction?.let { put(PAYLOAD_CONFIRM_ACTION, it.name) }
        source.direction?.let { put(PAYLOAD_DIRECTION, it.name) }
        put(PAYLOAD_FINAL_STEP, source.finalStep.toString())
    }

    private enum class ConfirmAction(
        val key: String,
        val material: Material,
        val successKey: String,
        val failedKey: String
    ) {
        EXPAND("expand", Material.FILLED_MAP, "message.expand_success", "message.expand_failed"),
        STEP_BACK("step_back", Material.RECOVERY_COMPASS, "message.step_back_success", "message.step_back_failed")
    }

    companion object {
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val ROUTE_SETTINGS = "world-settings"
        private const val ACTION_SELECT = "select"
        private const val WORLD_UUID = "world_uuid"
        private const val PAYLOAD_SCREEN = "screen"
        private const val PAYLOAD_WORLD_UUID = "world_uuid"
        private const val PAYLOAD_SHOW_BACK = "show_back"
        private const val PAYLOAD_ADMIN_FLOW = "admin_flow"
        private const val PAYLOAD_PLAYER_FLOW = "player_flow"
        private const val PAYLOAD_PARENT_BACK = "parent_back"
        private const val PAYLOAD_CONFIRM_ACTION = "confirm_action"
        private const val PAYLOAD_DIRECTION = "direction"
        private const val PAYLOAD_FINAL_STEP = "final_step"
        private const val OPERATION = "operation"
        private const val OPERATION_BACK = "back"
        private const val OPERATION_CONFIRM = "confirm"
        private const val OPERATION_DELETE = "delete"
        private const val OPERATION_EXPAND_CENTER = "expand-center"
        private const val OPERATION_EXPAND_DIRECTION = "expand-direction"
        private const val OPERATION_STEP_BACK = "step-back"
        private const val OPERATION_EDIT_INFO = "edit-info"
        private const val OPERATION_SELECT_ICON = "select-icon"
        private const val OPERATION_SET_SPAWN = "set-spawn"
        private const val OPERATION_MEMBERS = "members"
        private const val OPERATION_ANNOUNCEMENT = "announcement"
        private const val OPERATION_TOUR = "tour"
        private const val OPERATION_PORTALS = "portals"
        private const val OPERATION_WARP = "warp"
        private const val PROTECTED_SETTINGS_SIZE = 45
    }
}
