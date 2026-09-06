package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.generated.ChanponSystemKeys

import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiBedrockKeys
import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiCommonKeys
import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiSettingsKeys
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiCycleDirection
import com.awabi2048.ccsystem.api.gui.GuiConfirmationLayout
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiInteractionGuidance
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuCapabilityInvocationSpec
import com.awabi2048.ccsystem.api.gui.GuiUnavailableInteraction
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiStructuredMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.PlayerInventoryInteraction
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsAction
import me.awabi2048.myworldmanager.api.extension.WorldSettingsActionRequest
import me.awabi2048.myworldmanager.api.extension.WorldSettingsCapabilityPlacements
import me.awabi2048.myworldmanager.api.extension.WorldSettingsNavigationRequest
import me.awabi2048.myworldmanager.api.extension.WorldSettingsRouteCapability
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

class ChanponWorldSettingsMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val environmentGui: ChanponEnvironmentGui
) : WorldSettingsRouteCapability {
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
    ): MenuRoute = route(Source(Screen.MAIN, worldData.uuid, request))

    private fun render(player: Player, source: Source): InventoryMenuView {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return InventoryMenuView(9, Component.text("World settings"), emptyList())
        val view = when (source.screen) {
            Screen.MAIN -> renderMain(player, worldData)
            Screen.EXPANSION -> renderExpansion(player, worldData)
            Screen.MODIFIED_WARNING -> renderModifiedWarning(player)
            Screen.CONFIRM -> renderConfirmation(player, worldData, source)
            Screen.CRITICAL -> renderCritical(player, worldData)
            Screen.DELETE_CONFIRM -> renderDeleteConfirmation(player, worldData, source.finalStep)
        }
        return if (source.screen == Screen.CONFIRM || source.screen == Screen.DELETE_CONFIRM) {
            view.asConfirmation()
        } else {
            view
        }
    }


    private fun handleAction(context: MenuActionContext): MenuActionResult {
        val source = source(context.route)
        val player = context.player
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return MenuActionResult.Rejected()
        return when (context.payload[OPERATION]) {
            OPERATION_EDIT_INFO -> executeMwmAction(player, worldData, WorldSettingsAction.EDIT_INFO, context.click)
            OPERATION_SELECT_ICON -> executeMwmAction(player, worldData, WorldSettingsAction.SELECT_ICON, context.click)
            OPERATION_SET_SPAWN -> executeMwmAction(player, worldData, WorldSettingsAction.SET_SPAWN, context.click)
            OPERATION_MEMBERS -> executeMwmAction(player, worldData, WorldSettingsAction.MANAGE_MEMBERS, context.click)
            OPERATION_ANNOUNCEMENT -> executeMwmAction(player, worldData, WorldSettingsAction.EDIT_ANNOUNCEMENT, context.click)
            OPERATION_TOUR -> executeMwmAction(player, worldData, WorldSettingsAction.MANAGE_TOUR, context.click)
            OPERATION_PORTALS -> executeMwmAction(player, worldData, WorldSettingsAction.MANAGE_PORTALS, context.click)
            OPERATION_WARP -> executeMwmAction(player, worldData, WorldSettingsAction.WARP, context.click)
            OPERATION_BACK -> if (source.screen == Screen.DELETE_CONFIRM) {
                // 2段階削除確認のキャンセルは前段階を再表示せず、確認開始前の設定メニューへ戻します。
                // 確認画面群だけの履歴切り戻しはRuntimeが行います。
                MenuActionResult.Success(MenuUpdate.Cancel)
            } else {
                MenuActionResult.Success(MenuUpdate.Back)
            }
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
                    player.sendMessage(i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_MESSAGE_EXPAND_FAILED))
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
    ): MenuActionResult = MyWorldManagerApi.executeWorldSettingsAction(
        WorldSettingsActionRequest(player, worldData.uuid, action, click),
    )

    private fun renderMain(player: Player, worldData: WorldData): InventoryMenuView {
        val admin = MyWorldManagerApi.hasWorldSettingsAdminAccess(player)
        val actualOwner = worldData.owner == player.uniqueId || admin
        val moderator = player.uniqueId in worldData.moderators
        val member = player.uniqueId in worldData.members
        val imported = storage.isImportedArchive(worldData.uuid)
        val owner = actualOwner && !imported
        val viewerRole = worldSettingsViewerRole(owner, moderator, member)
        val canInspectSettings = !imported && viewerRole != WorldSettingsViewerRole.NONE
        val protected = protectionService.isProtected(worldData)
        // メンバーも設定の全体像を確認できるよう、表示専用項目を含む通常画面は54枠を使います。
        // 保護ワールドのオーナー向け短縮画面だけは、既存の配置契約を維持します。
        val size = if (protected && owner) PROTECTED_SETTINGS_SIZE else 54
        val elements = mutableListOf<MenuElement>()

        if (protected && owner) {
            addCapability(elements, player, worldData, WorldSettingsCapabilityPlacements.PUBLISH_ACTION, 21)
            addCapability(elements, player, worldData, WorldSettingsCapabilityPlacements.NOTIFICATION_ACTION, 22)
        } else if (canInspectSettings) {
            elements += mwmActionEntry(player, 19, worldData, WorldSettingsAction.EDIT_INFO, "info", MyworldGuiSettingsKeys.GUI_SETTINGS_INFO_DISPLAY, Material.NAME_TAG, OPERATION_EDIT_INFO, listOf(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MWM_ACTION_ACTION)))
            elements += mwmActionEntry(player, 20, worldData, WorldSettingsAction.SELECT_ICON, "icon", MyworldGuiSettingsKeys.GUI_SETTINGS_ICON_DISPLAY, Material.ANVIL, OPERATION_SELECT_ICON, listOf(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MWM_ACTION_ACTION)))
            elements += mwmActionEntry(player, 21, worldData, WorldSettingsAction.SET_SPAWN, "spawn", MyworldGuiSettingsKeys.GUI_SETTINGS_SPAWN_DISPLAY, Material.COMPASS, OPERATION_SET_SPAWN,
                if (MyWorldManagerApi.getBedrockFormService()?.isBedrock(player) == true) listOf(text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_SPAWN_ACTION_SELECT_TYPE)) else listOf(text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_SPAWN_ACTION_SET_GUEST), text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_SPAWN_ACTION_SET_MEMBER)),
                requireTargetWorld = true,
                interactionGuidance = GuiInteractionGuidance.SINGLE_ACTION_CLICK,
            )
            elements += mwmActionEntry(player, 28, worldData, WorldSettingsAction.EDIT_ANNOUNCEMENT, "announcement", MyworldGuiSettingsKeys.GUI_SETTINGS_ANNOUNCEMENT_DISPLAY, Material.OAK_SIGN, OPERATION_ANNOUNCEMENT,
                listOf(text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_ANNOUNCEMENT_ACTION_SET_MESSAGE), text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_ANNOUNCEMENT_ACTION_RESET_MESSAGE)))
            addCapability(
                elements,
                player,
                worldData,
                WorldSettingsCapabilityPlacements.NOTIFICATION_ACTION,
                29,
                displayOnlyWhenUnavailable = !isFeatureRoleActionable(player, worldData, WorldSettingsFeature.NOTIFICATION),
            )
            elements += mwmActionEntry(player, 47, worldData, WorldSettingsAction.MANAGE_TOUR, "tour", MyworldGuiCommonKeys.GUI_TOUR_WORLDMENU_DISPLAY, Material.PALE_OAK_BOAT, OPERATION_TOUR,
                listOf(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MWM_ACTION_ACTION)), requireTargetWorld = true)
        }
        if (canInspectSettings && !protected) {
            addCapability(
                elements,
                player,
                worldData,
                WorldSettingsCapabilityPlacements.EXPANSION_ACTION,
                23,
                displayOnlyWhenUnavailable = !isCurrentWorld(player, worldData) ||
                    !isFeatureRoleActionable(player, worldData, WorldSettingsFeature.EXPANSION),
            )
            addCapability(
                elements,
                player,
                worldData,
                WorldSettingsCapabilityPlacements.PUBLISH_ACTION,
                24,
                displayOnlyWhenUnavailable = !isFeatureRoleActionable(player, worldData, WorldSettingsFeature.PUBLISH),
            )
            elements += mwmActionEntry(player, 25, worldData, WorldSettingsAction.MANAGE_MEMBERS, "member", MyworldGuiSettingsKeys.GUI_SETTINGS_MEMBER_DISPLAY, Material.PLAYER_HEAD, OPERATION_MEMBERS, listOf(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MWM_ACTION_ACTION)))
            addCapability(
                elements,
                player,
                worldData,
                WorldSettingsCapabilityPlacements.ENVIRONMENT_ACTION,
                32,
                displayOnlyWhenUnavailable = !isCurrentWorld(player, worldData) ||
                    !isFeatureRoleActionable(player, worldData, WorldSettingsFeature.ENVIRONMENT),
            )
            addCapability(
                elements,
                player,
                worldData,
                WorldSettingsCapabilityPlacements.CRITICAL_ACTION,
                33,
                displayOnlyWhenUnavailable = !isCurrentWorld(player, worldData) ||
                    !isFeatureRoleActionable(player, worldData, WorldSettingsFeature.CRITICAL),
            )
            if (MyWorldManagerApi.hasWorldPortals(worldData.uuid)) {
                elements += mwmActionEntry(player, ChanponWorldSettingsLayout.PORTAL_MANAGEMENT_SLOT, worldData, WorldSettingsAction.MANAGE_PORTALS, "portals", MyworldGuiSettingsKeys.GUI_SETTINGS_PORTALS_DISPLAY, Material.END_PORTAL_FRAME, OPERATION_PORTALS, listOf(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MWM_ACTION_ACTION)))
            }
        }
        elements += worldInfoElement(player, worldData)
        if (CCSystem.getAPI().getMenuNavigationService().canGoBack(player)) {
            elements += actionElement(player, ChanponGuiElements.backSlot(size), null, i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_COMMON_BACK), OPERATION_BACK, GuiElementRole.BACK)
        }
        if (owner) {
            addCapability(elements, player, worldData, WorldSettingsCapabilityPlacements.FOOTER_ACTIONS, ChanponWorldSettingsLayout.productionSubmissionSlot(size), displayOnlyWhenUnavailable = true)
        }
        addCapability(elements, player, worldData, WorldSettingsCapabilityPlacements.FOOTER_LEFT_ACTIONS, ChanponWorldSettingsLayout.linkedPortalSlot(size, protected))
        if (owner) {
            addCapability(elements, player, worldData, WorldSettingsCapabilityPlacements.FOOTER_RIGHT_ACTIONS, ChanponWorldSettingsLayout.backupSlot(size), displayOnlyWhenUnavailable = true)
        }
        return InventoryMenuView(
            size,
            CCSystem.getAPI().getI18nComponent(player, MyworldGuiSettingsKeys.GUI_SETTINGS_TITLE, mapOf("world" to worldData.name)),
            elements,
            playerInventoryInteraction = PlayerInventoryInteraction.INTERACTIVE,
        )
    }

    private fun addCapability(
        elements: MutableList<MenuElement>,
        player: Player,
        worldData: WorldData,
        placement: String,
        slot: Int,
        displayOnlyWhenUnavailable: Boolean = false,
    ) {
        val service = CCSystem.getAPI().getMenuCapabilityService()
        val arguments = mapOf(WORLD_UUID to worldData.uuid.toString())
        val capability = service.definitions(placement).firstNotNullOfOrNull {
            service.resolve(it.capabilityId, player, arguments = arguments)
        }?.requireExplicitActionSafety() ?: return
        elements += CCSystem.getAPI().getGuiElementService().menuCapabilityEntry(
            player,
            GuiMenuCapabilityInvocationSpec(
                slot,
                capability.requireExplicitActionSafety(),
                arguments = arguments,
                unavailableInteraction = if (displayOnlyWhenUnavailable) {
                    GuiUnavailableInteraction.DISPLAY_ONLY
                } else {
                    GuiUnavailableInteraction.REJECT_WITH_REASON
                },
            ),
        )
    }

    private fun mwmActionEntry(
        player: Player, slot: Int, worldData: WorldData, action: WorldSettingsAction,
        iconKey: String, displayKey: LocalizationKey<String>, material: Material, operation: String, actionTexts: List<String>,
        requireTargetWorld: Boolean = false,
        interactionGuidance: GuiInteractionGuidance = GuiInteractionGuidance.DEFAULT,
    ): MenuElement {
        val contract = requireNotNull(MyWorldManagerApi.getWorldSettingsActionContract(player, worldData.uuid, action))
        require(contract.options.size == actionTexts.size)
        val outsideTargetWorld = requireTargetWorld && !isCurrentWorld(player, worldData)
        val actionable = contract.actionable && !outsideTargetWorld
        val warningKey = worldSettingsRestrictionWarningKey(
            worldSettingsViewerRole(
                owner = worldData.owner == player.uniqueId || MyWorldManagerApi.hasWorldSettingsAdminAccess(player),
                moderator = player.uniqueId in worldData.moderators,
                member = player.uniqueId in worldData.members,
            ),
            outsideTargetWorld,
        )?.takeUnless { actionable }
        return CCSystem.getAPI().getGuiElementService().menuEntry(player, GuiMenuEntrySpec(
            slot = slot,
            material = MyWorldManagerApi.getConfiguredMenuIcon("world_settings", iconKey, material),
            name = fixedName(text(player, displayKey), GuiNameStyle.DEFAULT),
            role = if (actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT,
            description = mwmDescription(player, iconKey),
            warnings = warningKey?.let { listOf(i18n(player, it)) } ?: emptyList(),
            // メンバー／モデレーターは同じ配置で項目を確認できますが、非操作時は操作案内を生成しません。
            actions = if (outsideTargetWorld || !contract.actionable) {
                emptyList()
            } else {
                contract.options.zip(actionTexts).map { (option, actionText) ->
                    chanponGestureAction(ACTION_SELECT, option.gesture, actionText, mapOf(OPERATION to operation), enabled = actionable, safety = mwmActionSafety(action))
                }
            },
            sounds = contract.sounds,
            interactionGuidance = interactionGuidance,
        ))
    }

    private fun worldInfoElement(player: Player, worldData: WorldData): MenuElement {
        val contract = MyWorldManagerApi.getWorldSettingsActionContract(player, worldData.uuid, WorldSettingsAction.WARP)
        val actionable = !isCurrentWorld(player, worldData) && contract?.actionable == true
        return CCSystem.getAPI().getGuiElementService().menuEntry(player, GuiMenuEntrySpec(
            slot = 4, material = worldData.icon, name = fixedName(worldData.name, GuiNameStyle.PRIMARY),
            role = if (actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT,
            data = listOf(GuiMenuEntryData(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_INFO_DESCRIPTION_LABEL), worldData.description.ifBlank { "-" })),
            actions = if (actionable) listOf(chanponGestureAction(ACTION_SELECT, MenuGesture.fromClicks(requireNotNull(contract).acceptedClicks), text(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_WARP), mapOf(OPERATION to OPERATION_WARP), safety = MenuActionSafety.EXTERNAL_SIDE_EFFECT)) else emptyList(),
            sounds = contract?.sounds,
        ))
    }

    private fun mwmDescription(player: Player, iconKey: String): List<String> {
        val key = when (iconKey) {
            "info" -> MyworldGuiSettingsKeys.GUI_SETTINGS_INFO_BLOCKS_SUMMARY
            "icon" -> MyworldGuiSettingsKeys.GUI_SETTINGS_ICON_BLOCKS_DESCRIPTION
            "spawn" -> MyworldGuiSettingsKeys.GUI_SETTINGS_SPAWN_BLOCKS_DESCRIPTION
            "announcement" -> MyworldGuiSettingsKeys.GUI_SETTINGS_ANNOUNCEMENT_BLOCKS_DESCRIPTION
            "member" -> MyworldGuiSettingsKeys.GUI_SETTINGS_MEMBER_BLOCKS_DESCRIPTION
            "tour" -> MyworldGuiCommonKeys.GUI_TOUR_WORLDMENU_BLOCKS_DESCRIPTION
            "portals" -> MyworldGuiSettingsKeys.GUI_SETTINGS_PORTALS_BLOCKS_SUMMARY
            else -> return emptyList()
        }
        return textList(player, key)
    }

    private fun mwmActionSafety(action: WorldSettingsAction): MenuActionSafety = when (action) {
        WorldSettingsAction.WARP -> MenuActionSafety.EXTERNAL_SIDE_EFFECT
        WorldSettingsAction.EDIT_INFO, WorldSettingsAction.SELECT_ICON, WorldSettingsAction.EDIT_ANNOUNCEMENT -> MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE
        WorldSettingsAction.SET_SPAWN -> MenuActionSafety.IRREVERSIBLE
        WorldSettingsAction.MANAGE_MEMBERS, WorldSettingsAction.MANAGE_TOUR, WorldSettingsAction.MANAGE_PORTALS -> MenuActionSafety.NAVIGATION_ONLY
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
                        if (success) ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DELETED else ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DELETE_FAILED,
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
            player.sendMessage(i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_UNAVAILABLE_MESSAGE))
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
        val directionName = direction?.let { directionName(player, it) } ?: i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_DIRECTION_CENTER)
        val modified = action == ConfirmAction.STEP_BACK && worldData.latestBorderExpansionRecord()?.modified == true
        val forms = MyWorldManagerApi.getBedrockFormService() ?: return false
        val opened = forms.sendSimpleForm(
            player = player,
            title = i18n(player, action.titleKey),
            content = confirmContentLines(player, action, directionName, modified).joinToString("\n"),
            buttons = listOf(
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CONFIRM),
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CANCEL)
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
            simpleActionEntry(
                player, 20, Material.MAP,
                ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_MENU_CENTER_DISPLAY,
                ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_MENU_CENTER_DESCRIPTION,
                ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_MENU_CENTER_ACTION,
                OPERATION_EXPAND_CENTER,
            ),
            simpleActionEntry(
                player, 24, Material.COMPASS,
                ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_MENU_DIRECTION_SELECT_DISPLAY,
                ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_MENU_DIRECTION_SELECT_DESCRIPTION,
                ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_MENU_DIRECTION_SELECT_ACTION,
                OPERATION_EXPAND_DIRECTION,
            ),
            actionElement(
                player,
                ChanponGuiElements.backSlot(45),
                null,
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_COMMON_BACK),
                OPERATION_BACK,
                GuiElementRole.BACK,
            ),
        )
        if (worldData.latestBorderExpansionRecord() != null) {
            elements += simpleActionEntry(
                player,
                42,
                Material.RECOVERY_COMPASS,
                ChanponSystemKeys.CHANPON_SETTINGS_STEP_BACK_DISPLAY,
                ChanponSystemKeys.CHANPON_SETTINGS_STEP_BACK_DESCRIPTION,
                ChanponSystemKeys.CHANPON_SETTINGS_STEP_BACK_ACTION,
                OPERATION_STEP_BACK,
                warnings = if (worldData.latestBorderExpansionRecord()?.modified == true) {
                    listOf(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_STEP_BACK_MODIFIED_WARNING))
                } else {
                    emptyList()
                },
            )
        }
        return InventoryMenuView(45, i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_MENU_TITLE), elements)
    }

    private fun renderModifiedWarning(player: Player): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_TITLE),
            confirmationElements(
                player,
                layout,
                modifiedWarningItem(player),
                warningButton(player, true),
                warningButton(player, false),
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_BUTTON_CONTINUE),
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_BUTTON_CANCEL),
                MenuActionSafety.CONFIRM_ENTRY,
            ),
        )
    }

    private fun renderConfirmation(player: Player, worldData: WorldData, source: Source): InventoryMenuView {
        val action = source.confirmAction ?: ConfirmAction.EXPAND
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, action.titleKey),
            confirmationElements(
                player,
                layout,
                confirmCenterItem(player, worldData, action, source.direction),
                confirmButton(player, true),
                confirmButton(player, false),
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CONFIRM),
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CANCEL),
                MenuActionSafety.IRREVERSIBLE,
            ),
        )
    }

    private fun renderCritical(player: Player, worldData: WorldData): InventoryMenuView =
        InventoryMenuView(
            45,
            i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_MENU_TITLE),
            listOf(
                actionElement(
                    player,
                    ChanponGuiElements.backSlot(45),
                    null,
                    i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_COMMON_BACK),
                    OPERATION_BACK,
                    GuiElementRole.BACK,
                ),
                simpleActionEntry(
                    player,
                    22,
                    Material.TNT,
                    ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DELETE_DISPLAY,
                    ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DELETE_DESCRIPTION,
                    ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DELETE_ACTION,
                    OPERATION_DELETE,
                    dangers = listOf(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DELETE_DANGER)),
                ),
            ),
        )

    private fun renderDeleteConfirmation(
        player: Player,
        worldData: WorldData,
        finalStep: Boolean,
    ): InventoryMenuView {
        val presentation = if (finalStep) {
            CriticalConfirmationPresentation(ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_FINAL_TITLE, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_FINAL_DISPLAY)
        } else {
            CriticalConfirmationPresentation(ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_CONFIRM_TITLE, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_CONFIRM_DISPLAY)
        }
        val layout = ChanponGuiElements.confirmationLayout()
        val lore = GuiLoreSpec.Blocks(if (finalStep) {
            listOf(GuiLoreBlock(listOf(
                GuiLoreLine.Text(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_FINAL_DESCRIPTION)),
                GuiLoreLine.Danger(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_FINAL_DANGER))
            )))
        } else {
            listOf(GuiLoreBlock(listOf(
                GuiLoreLine.Data(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_CONFIRM_TARGET_LABEL), worldData.name, "§e"),
                GuiLoreLine.Danger(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_CONFIRM_DANGER))
            )))
        })
        val centerItem = GuiItemSpec(
            material = Material.TNT,
            name = me.awabi2048.mwmchanpon.ui.fixedName(i18n(player, presentation.display), GuiNameStyle.DEFAULT),
            lore = lore,
            role = GuiElementRole.CONTENT,
            amount = 1,
        )
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, presentation.title),
            confirmationElements(
                player,
                layout,
                centerItem,
                confirmButton(player, true),
                confirmButton(player, false),
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CONFIRM),
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CANCEL),
                if (finalStep) MenuActionSafety.IRREVERSIBLE else MenuActionSafety.CONFIRM_ENTRY,
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
            ChanponWorldVisibility.PUBLIC -> i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_PUBLISH_PUBLIC_DESCRIPTION)
            ChanponWorldVisibility.DEVELOPMENT -> i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_PUBLISH_DEVELOPMENT_DESCRIPTION)
            ChanponWorldVisibility.PRIVATE -> i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_PUBLISH_PRIVATE_DESCRIPTION)
        }
        val loreBlocks = buildList {
            add(GuiLoreBlock(listOf(GuiLoreLine.Text(description))))
            if (isFeatureRoleActionable(player, worldData, WorldSettingsFeature.PUBLISH)) {
                add(GuiLoreBlock(listOf(
                    GuiLoreLine.Data(
                        i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_PUBLISH_CURRENT_LABEL),
                        display,
                        options.first { it.first == visibility }.third,
                    ),
                )))
                add(GuiLoreBlock(options.map { (option, displayName, selectedColor) ->
                    GuiLoreLine.Option(
                        displayName,
                        option == visibility,
                        selectedColor,
                        "\u00A77",
                    )
                }))
            }
            featureRestrictionWarning(player, worldData, WorldSettingsFeature.PUBLISH)?.let {
                add(GuiLoreBlock(listOf(it)))
            }
        }
        return capabilityPresentation(
            Material.OAK_SIGN,
            i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_PUBLISH_TITLE),
            loreBlocks,
            role = if (isFeatureActionable(player, worldData, WorldSettingsFeature.PUBLISH)) GuiElementRole.ACTION else GuiElementRole.CONTENT,
        )
    }

    private fun expansionPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        val level = worldData.borderExpansionLevel
        val max = maxExpansionLevel()
        val lines = if (level >= max) {
            i18nList(player, ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_STATUS_MAX).map(GuiLoreLine::Text)
        } else {
            emptyList()
        }
        return capabilityPresentation(
            Material.FILLED_MAP,
            i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_EXPAND_DISPLAY),
            describedStructuredLore(
                player,
                textList(player, MyworldGuiSettingsKeys.GUI_SETTINGS_EXPAND_BLOCKS_DESCRIPTION).map(GuiLoreLine::Text),
                lines,
                requireTargetWorld = true,
                worldData = worldData,
                restrictionWarning = featureRestrictionWarning(player, worldData, WorldSettingsFeature.EXPANSION),
            ),
            role = if (isFeatureRoleActionable(player, worldData, WorldSettingsFeature.EXPANSION)) GuiElementRole.ACTION else GuiElementRole.CONTENT,
        )
    }

    private fun notificationPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        val status = i18n(player, if (worldData.notificationEnabled) ChanponSystemKeys.CHANPON_SETTINGS_COMMON_ON else ChanponSystemKeys.CHANPON_SETTINGS_COMMON_OFF)
        val restrictionWarning = featureRestrictionWarning(player, worldData, WorldSettingsFeature.NOTIFICATION)
        val loreBlocks = buildList {
            add(GuiLoreBlock(
                textList(player, MyworldGuiSettingsKeys.GUI_SETTINGS_NOTIFICATION_BLOCKS_DESCRIPTION)
                    .map(GuiLoreLine::Text),
            ))
            if (isFeatureRoleActionable(player, worldData, WorldSettingsFeature.NOTIFICATION)) {
                add(GuiLoreBlock(listOf(
                    GuiLoreLine.Data(
                        i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_NOTIFICATION_CURRENT_LABEL),
                        status,
                        "\u00A7e",
                    ),
                )))
            }
            restrictionWarning?.let { add(GuiLoreBlock(listOf(it))) }
        }
        return MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.BELL,
                name = fixedName(
                    text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_NOTIFICATION_DISPLAY),
                    GuiNameStyle.DEFAULT,
                ),
                lore = GuiLoreSpec.Blocks(loreBlocks),
                role = if (isFeatureActionable(player, worldData, WorldSettingsFeature.NOTIFICATION)) GuiElementRole.ACTION else GuiElementRole.CONTENT,
                amount = 1,
            ),
            glint = worldData.notificationEnabled,
        )
    }

    private fun criticalPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        featureRestrictionWarning(player, worldData, WorldSettingsFeature.CRITICAL)?.let { warning ->
            return capabilityPresentation(
                Material.BARRIER,
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_UNAVAILABLE_DISPLAY),
                describedStructuredLore(
                    player,
                    textList(player, MyworldGuiSettingsKeys.GUI_SETTINGS_CRITICAL_BLOCKS_SUMMARY).map(GuiLoreLine::Text),
                    emptyList(),
                    requireTargetWorld = true,
                    worldData = worldData,
                    restrictionWarning = warning,
                ),
                role = GuiElementRole.CONTENT,
            )
        }
        criticalUnavailableLoreKey(player, worldData)?.let { loreKey ->
            return criticalUnavailablePresentation(player, worldData, loreKey)
        }
        return capabilityPresentation(
            Material.TNT,
            i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DISPLAY),
            describedStructuredLore(
                player,
                textList(player, MyworldGuiSettingsKeys.GUI_SETTINGS_CRITICAL_BLOCKS_SUMMARY).map(GuiLoreLine::Text),
                emptyList(),
                requireTargetWorld = true,
                worldData = worldData,
                restrictionWarning = featureRestrictionWarning(player, worldData, WorldSettingsFeature.CRITICAL),
            ),
        )
    }

    private fun criticalUnavailableLoreKey(player: Player, worldData: WorldData): LocalizationKey<String>? {
        // 重大な設定に入れない理由を一覧表示と遷移防御で共有し、専用メニューへの侵入を防ぐ。
        if (storage.isProductionWorld(worldData.uuid)) return ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_PRODUCTION_WARNING
        if (!MyWorldManagerApi.isCriticalSettingsEnabled(player.uniqueId)) {
            return ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_DISABLED_WARNING
        }
        return null
    }

    private fun criticalUnavailablePresentation(
        player: Player,
        worldData: WorldData,
        loreKey: LocalizationKey<String>,
    ): MenuCapabilityPresentation {
        return capabilityPresentation(
            Material.BARRIER,
            i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_UNAVAILABLE_DISPLAY),
            describedStructuredLore(
                player,
                textList(player, MyworldGuiSettingsKeys.GUI_SETTINGS_CRITICAL_BLOCKS_SUMMARY).map(GuiLoreLine::Text),
                listOf(GuiLoreLine.Warning(i18n(player, loreKey))),
                requireTargetWorld = true,
                worldData = worldData,
            ),
            role = GuiElementRole.CONTENT,
        )
    }

    private fun environmentPresentation(
        player: Player,
        worldData: WorldData,
    ): MenuCapabilityPresentation {
        val display = text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_ENVIRONMENT_DISPLAY)
        val lore = describedStructuredLore(
            player,
            textList(player, MyworldGuiSettingsKeys.GUI_SETTINGS_ENVIRONMENT_BLOCKS_SUMMARY).map(GuiLoreLine::Text),
            emptyList(),
            requireTargetWorld = true,
            worldData = worldData,
            restrictionWarning = featureRestrictionWarning(player, worldData, WorldSettingsFeature.ENVIRONMENT),
        )
        val icon = MyWorldManagerApi.getConfiguredMenuIcon(
            "world_settings",
            "environment",
            Material.GRASS_BLOCK,
        )
        return capabilityPresentation(
            icon,
            display,
            lore,
            role = if (isFeatureRoleActionable(player, worldData, WorldSettingsFeature.ENVIRONMENT)) GuiElementRole.ACTION else GuiElementRole.CONTENT,
        )
    }

    private fun capabilityPresentation(
        material: Material,
        display: String,
        loreBlocks: List<GuiLoreBlock>,
        role: GuiElementRole = GuiElementRole.ACTION,
    ) = MenuCapabilityPresentation(
        GuiItemSpec(
            material = material,
            name = me.awabi2048.mwmchanpon.ui.fixedName(display, GuiNameStyle.DEFAULT),
            lore = GuiLoreSpec.None,
            role = role,
            amount = 1,
        ),
        embeddedLoreBlocks = loreBlocks,
    )

    private fun featureRestrictionWarning(
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
    ): GuiLoreLine.Warning? {
        if (isFeatureActionable(player, worldData, feature)) return null
        return worldSettingsRestrictionWarningKey(
            worldSettingsViewerRole(
                owner = worldData.owner == player.uniqueId || MyWorldManagerApi.hasWorldSettingsAdminAccess(player),
                moderator = player.uniqueId in worldData.moderators,
                member = player.uniqueId in worldData.members,
            ),
            outsideTargetWorld = !isCurrentWorld(player, worldData),
        )?.let { GuiLoreLine.Warning(i18n(player, it)) }
    }

    private fun describedStructuredLore(
        player: Player,
        description: List<GuiLoreLine>,
        existing: List<GuiLoreLine>,
        requireTargetWorld: Boolean = false,
        worldData: WorldData? = null,
        restrictionWarning: GuiLoreLine.Warning? = null,
    ): List<GuiLoreBlock> {
        val outsideTargetWorld = requireTargetWorld && worldData != null && !isCurrentWorld(player, worldData)
        return buildList {
            if (description.isNotEmpty()) add(GuiLoreBlock(description))
            // ワールド外では、この項目固有の警告／危険文を重ねず、既存のData/Textだけを残します。
            // 共通のワールド外警告を、既存画面と同じ警告ブロック位置へ集約します。
            val visibleExisting = if (outsideTargetWorld) {
                existing.filter { it is GuiLoreLine.Data || it is GuiLoreLine.Text }
            } else {
                existing
            }
            if (visibleExisting.isNotEmpty()) add(GuiLoreBlock(visibleExisting))
            if (outsideTargetWorld) add(GuiLoreBlock(listOf(GuiLoreLine.Warning(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING)))))
            else if (restrictionWarning != null) add(GuiLoreBlock(listOf(restrictionWarning)))
        }
    }

    private fun simpleActionEntry(
        player: Player,
        slot: Int,
        material: Material,
        nameKey: LocalizationKey<String>,
        descriptionKey: LocalizationKey<String>,
        actionKey: LocalizationKey<String>,
        operation: String,
        warnings: List<String> = emptyList(),
        dangers: List<String> = emptyList(),
    ): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = material,
                name = me.awabi2048.mwmchanpon.ui.fixedName(i18n(player, nameKey), GuiNameStyle.DEFAULT),
                role = GuiElementRole.ACTION,
                description = listOf(i18n(player, descriptionKey)),
                warnings = warnings,
                dangers = dangers,
                actions = listOf(
                    chanponGestureAction(
                        ACTION_SELECT,
                        MenuGesture.ANY,
                        i18n(player, actionKey),
                        mapOf(OPERATION to operation),
                        safety = worldSettingsOperationSafety(operation),
                    ),
                ),
            ),
        )

    private fun modifiedWarningItem(player: Player): GuiItemSpec =
        GuiItemSpec(
            material = Material.REDSTONE_TORCH,
            name = me.awabi2048.mwmchanpon.ui.fixedName(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_DISPLAY), GuiNameStyle.DEFAULT),
            lore = GuiLoreSpec.Blocks(
                listOf(
                    GuiLoreBlock(
                        listOf(
                            GuiLoreLine.Warning(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_DESCRIPTION)),
                            GuiLoreLine.Text(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_CONSEQUENCE)),
                        ),
                    ),
                ),
            ),
            role = GuiElementRole.CONTENT,
            amount = 1,
        )

    private fun confirmCenterItem(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?): GuiItemSpec {
        val directionName = direction?.let { directionName(player, it) } ?: i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_DIRECTION_CENTER)
        val modified = action == ConfirmAction.STEP_BACK && worldData.latestBorderExpansionRecord()?.modified == true
        val lines = when (action) {
            ConfirmAction.EXPAND -> listOf(
                GuiLoreLine.Data(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_EXPAND_DIRECTION_LABEL), directionName, "§e"),
                GuiLoreLine.Text(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_EXPAND_DESCRIPTION))
            )
            ConfirmAction.STEP_BACK -> buildList {
                add(GuiLoreLine.Text(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_STEP_BACK_DESCRIPTION)))
                if (modified) {
                    add(GuiLoreLine.Warning(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_STEP_BACK_MODIFIED_WARNING)))
                }
            }
        }
        return GuiItemSpec(
            material = action.material,
            name = me.awabi2048.mwmchanpon.ui.fixedName(i18n(player, action.displayKey), GuiNameStyle.DEFAULT),
            lore = GuiLoreSpec.Blocks(listOf(GuiLoreBlock(lines))),
            role = GuiElementRole.CONTENT,
            amount = 1,
        )
    }

    private fun confirmContentLines(player: Player, action: ConfirmAction, directionName: String, modified: Boolean): List<String> {
        return when (action) {
            ConfirmAction.EXPAND -> listOf(
                "${i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_EXPAND_DIRECTION_LABEL)}: $directionName",
                i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_EXPAND_DESCRIPTION)
            )
            ConfirmAction.STEP_BACK -> buildList {
                add(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_STEP_BACK_DESCRIPTION))
                if (modified) add(i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_STEP_BACK_MODIFIED_WARNING))
            }
        }
    }

    private fun confirmButton(player: Player, confirm: Boolean): GuiItemSpec {
        return GuiItemSpec(
            material = if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            name = me.awabi2048.mwmchanpon.ui.fixedName(
                i18n(player, if (confirm) ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CONFIRM else ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_BUTTON_CANCEL),
                GuiNameStyle.DEFAULT,
            ),
            lore = GuiLoreSpec.None,
            role = if (confirm) GuiElementRole.CONFIRM else GuiElementRole.CANCEL,
            amount = 1,
        )
    }

    private fun warningButton(player: Player, continueAction: Boolean): GuiItemSpec {
        return GuiItemSpec(
            material = if (continueAction) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            name = me.awabi2048.mwmchanpon.ui.fixedName(
                i18n(player, if (continueAction) ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_BUTTON_CONTINUE else ChanponSystemKeys.CHANPON_SETTINGS_MODIFIED_WARNING_BUTTON_CANCEL),
                GuiNameStyle.DEFAULT,
            ),
            lore = GuiLoreSpec.None,
            role = if (continueAction) GuiElementRole.CONFIRM else GuiElementRole.CANCEL,
            amount = 1,
        )
    }

    private fun isCurrentWorld(player: Player, worldData: WorldData): Boolean =
        MyWorldManagerApi.getWorldService()?.isPlayerInWorld(player, worldData) == true

    private fun maxExpansionLevel(): Int {
        return plugin.config.getInt("world_expansion.max_level", 3).coerceAtLeast(0)
    }

    private fun directionName(player: Player, direction: BlockFace): String {
        val key = when (direction) {
            BlockFace.NORTH_WEST -> ChanponSystemKeys.CHANPON_SETTINGS_DIRECTION_NORTH_WEST
            BlockFace.NORTH_EAST -> ChanponSystemKeys.CHANPON_SETTINGS_DIRECTION_NORTH_EAST
            BlockFace.SOUTH_WEST -> ChanponSystemKeys.CHANPON_SETTINGS_DIRECTION_SOUTH_WEST
            BlockFace.SOUTH_EAST -> ChanponSystemKeys.CHANPON_SETTINGS_DIRECTION_SOUTH_EAST
            else -> error("未定義の拡張方向です: $direction")
        }
        return i18n(player, key)
    }

    private fun visibilityName(player: Player?, visibility: ChanponWorldVisibility): String {
        return i18n(player, when (visibility) {
            ChanponWorldVisibility.PUBLIC -> ChanponSystemKeys.CHANPON_SETTINGS_VISIBILITY_PUBLIC
            ChanponWorldVisibility.DEVELOPMENT -> ChanponSystemKeys.CHANPON_SETTINGS_VISIBILITY_DEVELOPMENT
            ChanponWorldVisibility.PRIVATE -> ChanponSystemKeys.CHANPON_SETTINGS_VISIBILITY_PRIVATE
        })
    }

    private fun cycleVisibility(
        current: ChanponWorldVisibility,
        direction: GuiCycleDirection,
    ): ChanponWorldVisibility {
        return GuiCycle.select(
            current,
            listOf(
                ChanponWorldVisibility.PUBLIC,
                ChanponWorldVisibility.DEVELOPMENT,
                ChanponWorldVisibility.PRIVATE,
            ),
            direction,
        )
    }

    private fun text(player: Player, key: LocalizationKey<String>): String =
        CCSystem.getAPI().getLocalized(player, key)

    private fun textList(player: Player, key: LocalizationKey<List<String>>): List<String> =
        CCSystem.getAPI().getLocalized(player, key)

    private fun textWithPlaceholders(
        player: Player,
        key: LocalizationKey<String>,
        placeholders: Map<String, Any>,
    ): String = CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getLocalized(player, key, placeholders)
    }

    private fun i18nList(player: Player?, key: LocalizationKey<List<String>>, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getLocalized(player, key, placeholders)
    }

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, key, placeholders)
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
        // capability自体はメンバー／モデレーターにも解決し、actionable=falseの
        // 表示専用アイコンとして項目説明と変更不可理由を表示します。実行権限は下の
        // isFeatureRoleActionableと既存handlerが引き続き担当します。
        return owner || moderator || player.uniqueId in worldData.members
    }

    internal fun isFeatureRoleActionable(
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
    ): Boolean {
        val owner = worldData.owner == player.uniqueId || MyWorldManagerApi.hasWorldSettingsAdminAccess(player)
        val moderator = player.uniqueId in worldData.moderators
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
        if (!isFeatureRoleActionable(player, worldData, feature)) return false
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
        WorldSettingsFeature.ENVIRONMENT -> environmentPresentation(player, worldData)
        WorldSettingsFeature.CRITICAL -> criticalPresentation(player, worldData)
    }

    internal fun actionTrigger(feature: WorldSettingsFeature):
        com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger =
        com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger.ANY

    internal fun featureActionText(
        player: Player,
        feature: WorldSettingsFeature,
    ): String = when (feature) {
        WorldSettingsFeature.PUBLISH -> i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_PUBLISH_CYCLE_ACTION)
        WorldSettingsFeature.EXPANSION ->
            text(player, MyworldGuiSettingsKeys.GUI_SETTINGS_EXPAND_ACTION_OPEN_MENU)
        WorldSettingsFeature.NOTIFICATION ->
            i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_NOTIFICATION_ACTION)
        WorldSettingsFeature.ENVIRONMENT ->
            i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_MWM_ACTION_ACTION)
        WorldSettingsFeature.CRITICAL ->
            i18n(player, ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_ACTION)
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
                val direction = GuiCycle.direction(click) ?: return MenuActionResult.Ignored
                val next = cycleVisibility(storage.getVisibility(worldData.uuid), direction)
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
        player: Player,
        layout: GuiConfirmationLayout,
        center: GuiItemSpec,
        confirm: GuiItemSpec,
        cancel: GuiItemSpec,
        confirmLabel: String,
        cancelLabel: String,
        confirmSafety: MenuActionSafety,
    ): List<MenuElement> = listOf(
        CCSystem.getAPI().getGuiElementService().menuDisplay(
            GuiMenuDisplaySpec(layout.previewSlot, center),
        ),
        actionElement(
            player,
            layout.confirmSlot,
            confirm,
            confirmLabel,
            OPERATION_CONFIRM,
            GuiElementRole.CONFIRM,
            safety = confirmSafety,
        ),
        actionElement(
            player,
            layout.cancelSlot,
            cancel,
            cancelLabel,
            OPERATION_BACK,
            GuiElementRole.CANCEL,
            safety = MenuActionSafety.NAVIGATION_ONLY,
        ),
    )

    private fun actionElement(
        player: Player,
        slot: Int,
        item: GuiItemSpec?,
        actionLabel: String,
        operation: String,
        role: GuiElementRole = GuiElementRole.ACTION,
        safety: MenuActionSafety = worldSettingsOperationSafety(operation),
    ): MenuElement {
        if (role == GuiElementRole.BACK) {
            return CCSystem.getAPI().getGuiElementService().backEntry(player, slot)
        }
        val actionItem = requireNotNull(item) { "Non-back action requires an item spec" }
        return CCSystem.getAPI().getGuiElementService().menuStructuredEntry(
        player,
        GuiStructuredMenuEntrySpec(
            slot = slot,
            item = actionItem.copy(role = role),
            actions = listOf(
                chanponGestureAction(
                    ACTION_SELECT,
                    MenuGesture.ANY,
                    actionLabel,
                    mapOf(OPERATION to operation),
                    safety = safety,
                ),
            ),
        ),
        )
    }

    internal fun featureUnavailableReason(
        player: Player,
        worldData: WorldData,
        feature: WorldSettingsFeature,
    ): Component {
        if (!isCurrentWorld(player, worldData)) {
            return i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING)
        }
        if (!isFeatureRoleActionable(player, worldData, feature)) {
            return i18nComponent(
                player,
                worldSettingsRestrictionWarningKey(
                    worldSettingsViewerRole(
                        owner = worldData.owner == player.uniqueId || MyWorldManagerApi.hasWorldSettingsAdminAccess(player),
                        moderator = player.uniqueId in worldData.moderators,
                        member = player.uniqueId in worldData.members,
                    ),
                    outsideTargetWorld = false,
                ) ?: ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MEMBER_CANNOT_CHANGE_WARNING,
            )
        }
        return when (feature) {
            WorldSettingsFeature.EXPANSION -> i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_MESSAGE_EXPAND_MAX)
            WorldSettingsFeature.CRITICAL -> i18nComponent(
                player,
                criticalUnavailableLoreKey(player, worldData) ?: ChanponSystemKeys.CHANPON_SETTINGS_CRITICAL_UNAVAILABLE_MESSAGE,
            )
            WorldSettingsFeature.ENVIRONMENT,
            WorldSettingsFeature.PUBLISH,
            WorldSettingsFeature.NOTIFICATION -> i18nComponent(player, ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING)
        }
    }

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
                ?: Screen.MAIN,
            worldUuid = payload[PAYLOAD_WORLD_UUID]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID(0L, 0L),
            request = WorldSettingsNavigationRequest(
                showBackButton = payload[PAYLOAD_SHOW_BACK]?.toBooleanStrictOrNull() ?: false,
                isAdminFlow = payload[PAYLOAD_ADMIN_FLOW]?.toBooleanStrictOrNull() ?: false,
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

    private fun worldSettingsOperationSafety(operation: String): MenuActionSafety = when (operation) {
        OPERATION_BACK -> MenuActionSafety.NAVIGATION_ONLY
        OPERATION_EXPAND_CENTER,
        OPERATION_EXPAND_DIRECTION,
        OPERATION_STEP_BACK -> MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE
        OPERATION_DELETE -> MenuActionSafety.CONFIRM_ENTRY
        OPERATION_CONFIRM -> MenuActionSafety.IRREVERSIBLE
        else -> error("Chanpon world-settings action must declare a dedicated safety mapping: $operation")
    }

    private enum class ConfirmAction(
        val key: String,
        val material: Material,
        val titleKey: LocalizationKey<String>,
        val displayKey: LocalizationKey<String>,
        val successKey: LocalizationKey<String>,
        val failedKey: LocalizationKey<String>,
    ) {
        EXPAND("expand", Material.FILLED_MAP, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_EXPAND_TITLE, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_EXPAND_DISPLAY, ChanponSystemKeys.CHANPON_SETTINGS_MESSAGE_EXPAND_SUCCESS, ChanponSystemKeys.CHANPON_SETTINGS_MESSAGE_EXPAND_FAILED),
        STEP_BACK("step_back", Material.RECOVERY_COMPASS, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_STEP_BACK_TITLE, ChanponSystemKeys.CHANPON_SETTINGS_CONFIRM_STEP_BACK_DISPLAY, ChanponSystemKeys.CHANPON_SETTINGS_MESSAGE_STEP_BACK_SUCCESS, ChanponSystemKeys.CHANPON_SETTINGS_MESSAGE_STEP_BACK_FAILED),
    }

    private data class CriticalConfirmationPresentation(
        val title: LocalizationKey<String>,
        val display: LocalizationKey<String>,
    )

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
