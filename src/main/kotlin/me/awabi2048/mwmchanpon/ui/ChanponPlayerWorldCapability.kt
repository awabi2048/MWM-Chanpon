package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiBedrockKeys
import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiCommonKeys
import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiSettingsKeys
import com.awabi2048.ccsystem.api.localization.generated.MyworldMessagesKeys
import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys

import com.awabi2048.ccsystem.api.localization.generated.ChanponSystemKeys

import com.awabi2048.ccsystem.api.localization.generated.CommonKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiInteractionGuidance
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuCapabilityInvocationSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiValueTone
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuElement
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.mwmchanpon.service.EasyVoidWorldCreationService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.PlayerWorldCapabilityContract
import me.awabi2048.myworldmanager.api.extension.PlayerWorldCapabilitySubject
import me.awabi2048.myworldmanager.api.extension.PlayerWorldRouteCapability
import me.awabi2048.myworldmanager.api.extension.PlayerWorldRouteRequest
import me.awabi2048.myworldmanager.api.extension.WorldCreationOperation
import me.awabi2048.myworldmanager.api.extension.WorldCreationRequest
import me.awabi2048.myworldmanager.api.extension.WorldSettingsNavigationRequest
import me.awabi2048.myworldmanager.api.service.WorldPointBillingMode
import me.awabi2048.myworldmanager.service.MwmReversibleContracts
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MWMが所有するPlayerWorld画面へChanpon固有の表示値と操作だけを提供する。
 *
 * 画面、Route、履歴、MWMのGUIセッションは所有しない。
 */
class ChanponPlayerWorldCapability(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val easyVoidCreationService: EasyVoidWorldCreationService,
) : PlayerWorldRouteCapability {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        val service = CCSystem.getAPI().getMenuCapabilityService()
        service.register(
            MenuCapabilityDefinition(
                owner = CAPABILITY_OWNER,
                id = CURRENT_WORLD_CAPABILITY_ID,
                placement = PlayerWorldCapabilityContract.WORLD_ITEM_PLACEMENT,
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                    subject(it) != null && world(it)?.let { world -> isCurrentWorld(it.player, world) } == true
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    createWorldPresentation(it.player, requireNotNull(world(it)))
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "settings",
                        trigger = MenuCapabilityTrigger.LEFT_RIGHT,
                        textProvider = MenuCapabilityActionTextProvider {
                            text(it.player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_SETTINGS)
                        },
                        availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                            world(it)?.let { world ->
                                !world.isArchived &&
                                    canOpenSettings(it.player, world)
                            } == true
                        },
                        handler = MenuCapabilityActionHandler(::handleWorld),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                    chanponCapabilityAction(
                        id = "prioritize",
                        trigger = MenuCapabilityTrigger.SHIFT_LEFT,
                        textProvider = MenuCapabilityActionTextProvider {
                            text(it.player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_MOVE_TO_TOP)
                        },
                        availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                            subject(it)?.targetPlayerUuid == it.player.uniqueId
                        },
                        handler = MenuCapabilityActionHandler(::handleWorld),
                        safety = MenuActionSafety.REVERSIBLE,
                        reversibleContract = MwmReversibleContracts.displayOrder(),
                        classification = ChanponReversibleActionClassification.Operation.CURRENT_PLAYER_WORLD_DISPLAY_ORDER,
                    ),
                ),
            ),
        )
        service.register(
            MenuCapabilityDefinition(
                owner = CAPABILITY_OWNER,
                id = AWAY_WORLD_CAPABILITY_ID,
                placement = PlayerWorldCapabilityContract.WORLD_ITEM_PLACEMENT,
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                    subject(it) != null && world(it)?.let { world -> !isCurrentWorld(it.player, world) } == true
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    createWorldPresentation(it.player, requireNotNull(world(it)))
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "primary",
                        trigger = MenuCapabilityTrigger.LEFT,
                        textProvider = MenuCapabilityActionTextProvider(::primaryWorldActionText),
                        availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                            world(it)?.isArchived == false
                        },
                        handler = MenuCapabilityActionHandler(::handleWorld),
                        safety = MenuActionSafety.EXTERNAL_SIDE_EFFECT,
                    ),
                    chanponCapabilityAction(
                        id = "settings",
                        trigger = MenuCapabilityTrigger.RIGHT,
                        textProvider = MenuCapabilityActionTextProvider {
                            text(it.player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_SETTINGS)
                        },
                        availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                            world(it)?.let { world -> canOpenSettings(it.player, world) } == true
                        },
                        handler = MenuCapabilityActionHandler(::handleWorld),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                    chanponCapabilityAction(
                        id = "prioritize",
                        trigger = MenuCapabilityTrigger.SHIFT_LEFT,
                        textProvider = MenuCapabilityActionTextProvider {
                            text(it.player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_MOVE_TO_TOP)
                        },
                        availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                            canPrioritizeWorld(it)
                        },
                        handler = MenuCapabilityActionHandler(::handleWorld),
                        safety = MenuActionSafety.REVERSIBLE,
                        reversibleContract = MwmReversibleContracts.displayOrder(),
                        classification = ChanponReversibleActionClassification.Operation.AWAY_PLAYER_WORLD_DISPLAY_ORDER,
                    ),
                ),
            ),
        )
        service.register(
            MenuCapabilityDefinition(
                owner = CAPABILITY_OWNER,
                id = SUMMARY_CAPABILITY_ID,
                placement = PlayerWorldCapabilityContract.SUMMARY_PLACEMENT,
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability { subject(it) != null },
                presentationProvider = MenuCapabilityPresentationProvider {
                    val subject = requireNotNull(subject(it))
                    createPlayerInfoPresentation(
                        it.player,
                        subject.targetPlayerUuid,
                        subject.targetPlayerName,
                    )
                },
                actions = emptyList(),
            ),
        )
        service.register(
            MenuCapabilityDefinition(
                owner = CAPABILITY_OWNER,
                id = CREATION_CAPABILITY_ID,
                placement = PlayerWorldCapabilityContract.CREATION_PLACEMENT,
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                    subject(it)?.targetPlayerUuid == it.player.uniqueId
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    creationPresentation(it)
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "standard-create",
                        trigger = MenuCapabilityTrigger.LEFT,
                        textProvider = MenuCapabilityActionTextProvider {
                            text(it.player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_BUTTON_ACTION)
                        },
                        availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                            creationBlockReason(it) == null && hasCreatePermission(it.player)
                        },
                        handler = MenuCapabilityActionHandler(::handleCreate),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                    chanponCapabilityAction(
                        id = "easy-create",
                        trigger = MenuCapabilityTrigger.RIGHT,
                        textProvider = MenuCapabilityActionTextProvider {
                            plugin.getMessage(
                                it.player,
                                ChanponSystemKeys.CHANPON_PLAYER_WORLD_EASY_CREATION_ACTION,
                            )
                        },
                        availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                            creationBlockReason(it) == null && hasCreatePermission(it.player)
                        },
                        handler = MenuCapabilityActionHandler(::handleCreate),
                        safety = MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE,
                    ),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = ROUTE_OWNER,
                id = ROUTE_ID,
                renderer = { context -> render(context.player, context.route) },
                actions = mapOf(
                    ACTION_PAGE to MenuActionHandler(::changePage),
                    ACTION_SETTINGS to MenuActionHandler(::openUserSettings),
                    ACTION_PENDING to MenuActionHandler(::openPending),
                    ACTION_BACK to MenuActionHandler {
                        MenuActionResult.Success(MenuUpdate.Back)
                    },
                ),
            ),
        )
    }

    override fun prepare(
        player: Player,
        request: PlayerWorldRouteRequest,
    ): MenuRoute =
        route(request)

    private fun render(player: Player, route: MenuRoute): InventoryMenuView {
        val request = request(route)
        val worlds = MyWorldManagerApi.getPlayerWorlds(request.targetPlayerUuid)
        val layout = CCSystem.getAPI().getGuiLayoutService()
            .sevenColumnPage(worlds.size, request.page)
        val current = request.copy(page = layout.page)
        val currentRoute = route(current)
        val subject = PlayerWorldCapabilitySubject(
            player,
            request.targetPlayerUuid,
            request.targetPlayerName,
            worlds,
            request.showBackButton,
            currentRoute,
        )
        val service = CCSystem.getAPI().getMenuCapabilityService()
        val elements = mutableListOf<MenuElement>()
        worlds.drop(layout.startIndex).take(layout.itemCount)
            .forEachIndexed { index, worldData ->
                val attributes = attributes(subject, worldData)
                val capability = service.resolve(
                    "$CAPABILITY_OWNER:${worldCapabilityId(player, worldData)}",
                    player,
                    attributes = attributes,
                )?.requireExplicitActionSafety() ?: return@forEachIndexed
                elements += CCSystem.getAPI().getGuiElementService().menuCapabilityEntry(
                    player,
                    playerWorldCapabilityInvocation(
                        slot = layout.layout.itemSlots[index],
                        capability = capability.requireExplicitActionSafety(),
                        attributes = attributes,
                        worldUuid = worldData.uuid,
                    ),
                )
            }

        if (request.targetPlayerUuid == player.uniqueId) {
            val capabilityAttributes = attributes(subject)
            service.resolve(
                "$CAPABILITY_OWNER:$CREATION_CAPABILITY_ID",
                player,
                attributes = capabilityAttributes,
            )?.requireExplicitActionSafety()?.let { capability ->
                elements += CCSystem.getAPI().getGuiElementService().menuCapabilityEntry(
                    player,
                    playerWorldCapabilityInvocation(
                        slot = layout.layout.actionSlot - 2,
                        capability = capability.requireExplicitActionSafety(),
                        attributes = capabilityAttributes,
                    ),
                )
            }
        }
        val summaryAttributes = attributes(subject)
        service.resolve(
            "$CAPABILITY_OWNER:$SUMMARY_CAPABILITY_ID",
            player,
            attributes = summaryAttributes,
        )?.requireExplicitActionSafety()?.let { capability ->
            elements += CCSystem.getAPI().getGuiElementService().menuCapabilityEntry(
                player,
                playerWorldCapabilityInvocation(
                    slot = PlayerWorldCapabilityContract.HEADER_CENTER_SLOT,
                    capability = capability.requireExplicitActionSafety(),
                    attributes = summaryAttributes,
                ),
            )
        }
        // ルート差し替え時もMWM標準画面と同じ生成元で、フッター中央へ操作プレイヤーの現在地を表示します。
        elements += MyWorldManagerApi.createCurrentWorldMenuElement(player, layout.layout.actionSlot)
        if (request.targetPlayerUuid == player.uniqueId) {
            elements += simpleActionElement(
                layout.layout.actionSlot + 2,
                player,
                Material.WRITABLE_BOOK,
                text(player, MyworldGuiSettingsKeys.GUI_USER_SETTINGS_BUTTON_DISPLAY),
                text(player, MyworldGuiSettingsKeys.GUI_USER_SETTINGS_BUTTON_ACTION),
                ACTION_SETTINGS,
                description = textList(player, MyworldGuiSettingsKeys.GUI_USER_SETTINGS_BUTTON_DESCRIPTION),
            )
            elements += pendingElement(layout.layout.size - 2, player)
        }
        if (layout.page > 0) {
            elements += navigationElement(
                layout.layout.previousPageSlot,
                player,
                false,
                current.copy(page = layout.page - 1),
            )
        }
        if (CCSystem.getAPI().getMenuNavigationService().canGoBack(player)) {
            elements += CCSystem.getAPI().getGuiElementService().backEntry(player, layout.layout.backSlot)
        }
        if (layout.page + 1 < layout.totalPages) {
            elements += navigationElement(
                // 個人設定・保留通知の旧配置を保ち、ページ操作だけを右端へ分離します。
                layout.layout.size - 1,
                player,
                true,
                current.copy(page = layout.page + 1),
            )
        }
        return InventoryMenuView(
            layout.layout.size,
            component(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_TITLE),
            elements,
        )
    }

    private fun creationPresentation(
        context: MenuCapabilityContext,
    ): MenuCapabilityPresentation {
        val subject = requireNotNull(subject(context))
        val ownerCount = MyWorldManagerApi.getWorldRepository()?.ownerCountIncludingQuarantine(subject.targetPlayerUuid)
            ?: subject.worlds.count { it.owner == subject.targetPlayerUuid }
        val reason = creationBlockReason(
            context.player,
            ownerCount,
            plugin.config.getInt("creation.max_worlds_per_player", 50),
        )
        return if (reason == null && hasCreatePermission(context.player)) {
            createCreationPresentation(context.player)
        } else {
            createCreationUnavailablePresentation(
                context.player,
                reason ?: CreationBlockReason.NO_PERMISSION,
            )
        }
    }

    private fun handleCreate(context: MenuCapabilityActionContext): MenuActionResult {
        val subject = subject(context) ?: return MenuActionResult.Ignored
        val player = context.player
        if (subject.targetPlayerUuid != player.uniqueId) return MenuActionResult.Rejected()
        if (!hasCreatePermission(player)) {
            player.sendMessage(text(player, ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_WORLD_CREATION_NO_PERMISSION))
            return MenuActionResult.Rejected()
        }
        if (context.click.isRightClick && !context.click.isShiftClick) {
            return startEasyCreation(player, subject.returnRoute)
        }
        return MenuActionResult.Success(
            MenuUpdate.Navigate(
                MyWorldManagerApi.prepareStandardWorldCreationRoute(
                    player,
                    WorldPointBillingMode.NONE,
                ),
            ),
        )
    }

    private fun handleWorld(context: MenuCapabilityActionContext): MenuActionResult {
        val subject = subject(context) ?: return MenuActionResult.Ignored
        val worldData = world(context) ?: return MenuActionResult.Ignored
        val player = context.player
        if (
            context.click.isShiftClick &&
            context.click.isLeftClick
        ) {
            if (!canPrioritizeWorld(context)) return MenuActionResult.Ignored
            MyWorldManagerApi.prioritizePlayerWorld(player.uniqueId, worldData.uuid)
            return MenuActionResult.Success(MenuUpdate.Refresh)
        }
        if (context.click.isLeftClick && !context.click.isShiftClick) {
            if (worldData.isArchived) return MenuActionResult.Rejected()
            if (isCurrentWorld(player, worldData)) {
                if (!canOpenSettings(player, worldData)) return MenuActionResult.Rejected()
                return openWorldSettings(subject, worldData)
            }
            val worldService = MyWorldManagerApi.getWorldService() ?: return MenuActionResult.Rejected()
            val accepted = worldService.teleportToWorldWithCompletion(
                player,
                worldData.uuid,
                Runnable {
                    // ワールドのロード待ちを含め、実際のPlayer.teleport成功後に一度だけ案内します。
                    player.sendMessage(
                        text(player, MyworldMessagesKeys.MESSAGES_WARP_SUCCESS, mapOf("world" to worldData.name)),
                    )
                },
            )
            if (!accepted) return MenuActionResult.Rejected()
            return MenuActionResult.Success(MenuUpdate.Close)
        }
        if (context.click.isRightClick && !context.click.isShiftClick && canOpenSettings(player, worldData)) {
            return openWorldSettings(subject, worldData)
        }
        return MenuActionResult.Ignored
    }

    private fun startEasyCreation(player: Player, returnRoute: MenuRoute): MenuActionResult =
        easyVoidCreationService.start(player, returnRoute)

    private fun primaryWorldActionText(context: MenuCapabilityContext): String {
        val world = requireNotNull(world(context))
        return if (isCurrentWorld(context.player, world)) {
            text(context.player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_SETTINGS)
        } else {
            text(context.player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_WARP)
        }
    }

    private fun worldCapabilityId(player: Player, worldData: WorldData): String =
        if (isCurrentWorld(player, worldData)) CURRENT_WORLD_CAPABILITY_ID else AWAY_WORLD_CAPABILITY_ID

    private fun canPrioritizeWorld(context: MenuCapabilityContext): Boolean {
        val subject = subject(context) ?: return false
        val world = world(context) ?: return false
        return isMoveToTopAvailable(
            context.player.uniqueId,
            subject.targetPlayerUuid,
            subject.worlds.map(WorldData::uuid),
            world.uuid,
        )
    }

    private fun canPrioritizeWorld(context: MenuCapabilityActionContext): Boolean {
        val subject = subject(context) ?: return false
        val world = world(context) ?: return false
        return isMoveToTopAvailable(
            context.player.uniqueId,
            subject.targetPlayerUuid,
            subject.worlds.map(WorldData::uuid),
            world.uuid,
        )
    }

    private fun createWorldPresentation(
        player: Player,
        world: WorldData,
    ): MenuCapabilityPresentation {
        val ownerName = Bukkit.getOfflinePlayer(world.owner).name
            ?: text(player, CommonKeys.GENERAL_UNKNOWN)
        val progressLine = SubmissionProgressDisplay.loreLine(player, storage, world.uuid)
        val information = buildList {
            add(
                GuiLoreLine.Data(
                    CCSystem.getAPI().getLocalized(player, CommonKeys.GUI_DATA_OWNER),
                    ownerName,
                    "§f",
                ),
            )
            add(
                GuiLoreLine.Data(
                    CCSystem.getAPI().getLocalized(player, CommonKeys.GUI_DATA_VISIBILITY),
                    visibilityName(player, storage.getVisibility(world.uuid)),
                    "§e",
                ),
            )
            progressLine?.let(::add)
        }
        val blocks = buildList {
            if (world.description.isNotBlank()) {
                add(GuiLoreBlock(listOf(GuiLoreLine.Text(world.description))))
            }
            add(GuiLoreBlock(information))
            if (world.isArchived) {
                add(
                    GuiLoreBlock(
                        listOf(
                            GuiLoreLine.Danger(
                                text(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_WORLD_ITEM_EXPIRED),
                            ),
                        ),
                    ),
                )
            }
        }
        return MenuCapabilityPresentation(
            item = GuiItemSpec(
                material = world.icon,
                name = GuiNameSpec.TargetIdentity(
                    component(
                        player,
                        MyworldGuiCommonKeys.GUI_COMMON_WORLD_ITEM_NAME,
                        mapOf("world" to world.name),
                    ).decoration(TextDecoration.ITALIC, false),
                ),
                lore = GuiLoreSpec.Blocks(blocks),
                role = GuiElementRole.ACTION,
                amount = 1,
            ),
            glint = world.isArchived,
        )
    }

    private fun createCreationPresentation(player: Player): MenuCapabilityPresentation =
        MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.NETHER_STAR,
                name = GuiNameSpec.FixedLabel(
                    component(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_BUTTON_DISPLAY),
                ),
                lore = GuiLoreSpec.Blocks(
                    listOf(
                        GuiLoreBlock(
                            textList(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_BUTTON_DESCRIPTION)
                                .map(GuiLoreLine::Text),
                        ),
                    ),
                ),
                role = GuiElementRole.ACTION,
                amount = 1,
            ),
        )

    private fun createCreationUnavailablePresentation(
        player: Player,
        reason: CreationBlockReason,
    ): MenuCapabilityPresentation =
        MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.BARRIER,
                name = GuiNameSpec.FixedLabel(component(player, reason.displayKey)),
                lore = me.awabi2048.mwmchanpon.ui.framedLore(
                    textList(player, reason.loreKey).map(GuiLoreLine::Text),
                    com.awabi2048.ccsystem.api.gui.GuiLoreFrame.BOTH,
                ),
                role = GuiElementRole.CONTENT,
                amount = 1,
            ),
        )

    private fun createPlayerInfoPresentation(
        player: Player,
        targetPlayerUuid: java.util.UUID,
        targetPlayerName: String?,
    ): MenuCapabilityPresentation =
        MenuCapabilityPresentation(
            item = GuiItemSpec(
                material = Material.PLAYER_HEAD,
                name = GuiNameSpec.TargetIdentity(
                    CCSystem.getAPI().getGuiElementService()
                        .name(targetPlayerName ?: targetPlayerUuid.toString()),
                ),
                lore = GuiLoreSpec.None,
                role = GuiElementRole.CONTENT,
                amount = 1,
            ),
            playerHeadOwner = targetPlayerUuid,
        )

    private fun visibilityName(
        player: Player,
        visibility: ChanponWorldVisibility,
    ): String {
        val key = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> ChanponSystemKeys.CHANPON_PUBLISH_PUBLIC
            ChanponWorldVisibility.DEVELOPMENT -> ChanponSystemKeys.CHANPON_PUBLISH_DEVELOPMENT
            ChanponWorldVisibility.PRIVATE -> ChanponSystemKeys.CHANPON_PUBLISH_PRIVATE
        }
        return plugin.getMessage(player, key)
    }

    private fun canOpenSettings(player: Player, world: WorldData): Boolean =
        player.hasPermission("myworldmanager.admin") ||
            world.owner == player.uniqueId ||
            world.moderators.contains(player.uniqueId) ||
            world.members.contains(player.uniqueId)

    private fun openWorldSettings(
        context: PlayerWorldCapabilitySubject,
        worldData: WorldData,
    ): MenuActionResult {
        val route = MyWorldManagerApi.prepareWorldSettingsRoute(
            context.player,
            worldData.uuid,
            WorldSettingsNavigationRequest(
                showBackButton = true,
                isPlayerWorldFlow = true,
                parentShowBackButton = context.showBackButton,
            ),
        ) ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Navigate(route))
    }

    private fun isCurrentWorld(player: Player, world: WorldData): Boolean =
        MyWorldManagerApi.getWorldService()?.isPlayerInWorld(player, world) == true

    private fun canBypassWorldLimits(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin.bypass_world_limits") ||
            player.hasPermission("myworldmanager.admin")

    private fun hasCreatePermission(player: Player): Boolean =
        player.hasPermission("myworldmanager.world.create") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin")

    private fun creationBlockReason(
        context: MenuCapabilityContext,
    ): CreationBlockReason? {
        val subject = subject(context) ?: return CreationBlockReason.POLICY_DENIED
        val ownerCount = MyWorldManagerApi.getWorldRepository()?.ownerCountIncludingQuarantine(subject.targetPlayerUuid)
            ?: subject.worlds.count { it.owner == subject.targetPlayerUuid }
        return creationBlockReason(
            context.player,
            ownerCount,
            plugin.config.getInt("creation.max_worlds_per_player", 50),
        )
    }

    private fun creationBlockReason(
        player: Player,
        currentCreateCount: Int,
        maxCreateCount: Int,
    ): CreationBlockReason? {
        if (!hasCreatePermission(player)) return CreationBlockReason.NO_PERMISSION
        val creationDecision = MyWorldManagerApi.checkWorldCreation(
            WorldCreationRequest(player, player, WorldCreationOperation.NORMAL, null),
        )
        if (!creationDecision.allowed) return CreationBlockReason.POLICY_DENIED
        if (canBypassWorldLimits(player)) return null
        if (currentCreateCount >= maxCreateCount) return CreationBlockReason.NO_SLOT
        return null
    }

    private fun subject(context: MenuCapabilityContext): PlayerWorldCapabilitySubject? =
        context.attributes[PlayerWorldCapabilityContract.SUBJECT_ATTRIBUTE]
            as? PlayerWorldCapabilitySubject

    private fun subject(context: MenuCapabilityActionContext): PlayerWorldCapabilitySubject? =
        context.attributes[PlayerWorldCapabilityContract.SUBJECT_ATTRIBUTE]
            as? PlayerWorldCapabilitySubject

    private fun world(context: MenuCapabilityContext): WorldData? =
        context.attributes[PlayerWorldCapabilityContract.WORLD_ATTRIBUTE] as? WorldData

    private fun world(context: MenuCapabilityActionContext): WorldData? =
        context.attributes[PlayerWorldCapabilityContract.WORLD_ATTRIBUTE] as? WorldData

    private fun text(
        player: Player,
        key: LocalizationKey<String>,
        placeholders: Map<String, Any> = emptyMap(),
    ): String = CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun textList(player: Player, key: LocalizationKey<List<String>>): List<String> =
        CCSystem.getAPI().getLocalized(player, key)

    private fun component(
        player: Player,
        key: LocalizationKey<String>,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nComponent(player, key, placeholders)

    private fun changePage(context: MenuActionContext): MenuActionResult {
        val page = context.payload[PAYLOAD_PAGE]?.toIntOrNull()
            ?: return MenuActionResult.Ignored
        return MenuActionResult.Success(
            MenuUpdate.Replace(route(request(context.route).copy(page = page))),
        )
    }

    private fun openUserSettings(context: MenuActionContext): MenuActionResult {
        val route = MyWorldManagerApi.prepareUserSettingsRoute(context.player, true)
            ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Navigate(route))
    }

    private fun openPending(context: MenuActionContext): MenuActionResult {
        val request = request(context.route)
        return MenuActionResult.Success(
            MenuUpdate.Navigate(
                MyWorldManagerApi.preparePendingInteractionRoute(
                    page = 0,
                    returnPage = request.page,
                    showBackButton = request.showBackButton,
                ),
            ),
        )
    }

    private fun playerWorldRuntimeActionSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_PAGE, ACTION_SETTINGS, ACTION_PENDING, ACTION_BACK -> MenuActionSafety.NAVIGATION_ONLY
        else -> error("Chanpon player-world action must declare a dedicated safety mapping: $actionId")
    }

    private fun simpleActionElement(
        slot: Int,
        player: Player,
        material: Material,
        name: String,
        actionText: String,
        actionId: String,
        description: List<String> = emptyList(),
    ): MenuElement {
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = material,
                name = me.awabi2048.mwmchanpon.ui.fixedName(name, com.awabi2048.ccsystem.api.gui.GuiNameStyle.DEFAULT),
                role = GuiElementRole.ACTION,
                description = description,
                actions = listOf(chanponGestureAction(
                    actionId,
                    MenuGesture.ANY,
                    actionText,
                    safety = playerWorldRuntimeActionSafety(actionId),
                )),
            ),
        )
    }

    private fun navigationElement(
        slot: Int,
        player: Player,
        next: Boolean,
        request: PlayerWorldRouteRequest,
    ): MenuElement {
        val label = text(player, if (next) CommonKeys.GUI_COMMON_NEXT_PAGE else CommonKeys.GUI_COMMON_PREV_PAGE)
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = Material.ARROW,
                name = me.awabi2048.mwmchanpon.ui.fixedName(
                    label,
                    com.awabi2048.ccsystem.api.gui.GuiNameStyle.DEFAULT,
                ),
                role = GuiElementRole.NAVIGATION,
                actions = listOf(
                    chanponGestureAction(
                        ACTION_PAGE,
                        MenuGesture.LEFT_RIGHT,
                        label,
                        mapOf(PAYLOAD_PAGE to request.page.toString()),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                ),
                interactionGuidance = GuiInteractionGuidance.SINGLE_ACTION_CLICK,
            ),
        )
    }

    private fun pendingElement(slot: Int, player: Player): MenuElement {
        val summary = MyWorldManagerApi.getPendingInteractionSummary(player.uniqueId)
        if (summary.count == 0) {
            return CCSystem.getAPI().getGuiElementService().menuDisplay(
                GuiMenuDisplaySpec(
                    slot,
                    GuiItemSpec(
                        material = Material.BARRIER,
                        name = GuiNameSpec.FixedLabel(
                            component(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_DISPLAY),
                        ),
                        lore = GuiLoreSpec.Blocks(
                            listOf(
                                GuiLoreBlock(
                                    listOf(
                                        GuiLoreLine.Text(
                                            text(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_EMPTY),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        role = GuiElementRole.CONTENT,
                        amount = 1,
                    ),
                ),
            )
        }
        val latest = summary.latestCreatedAt?.let {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it))
        } ?: text(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_NONE)
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = Material.WRITABLE_BOOK,
                name = GuiNameSpec.FixedLabel(component(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_DISPLAY)),
                role = GuiElementRole.ACTION,
                description = textList(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_DESCRIPTION),
                data = listOf(
                    GuiMenuEntryData(
                        text(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_COUNT_LABEL),
                        summary.count,
                        GuiValueTone.PRIMARY,
                    ),
                    GuiMenuEntryData(
                        text(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_LATEST_LABEL),
                        latest,
                        GuiValueTone.INFO,
                    ),
                ),
                actions = listOf(chanponGestureAction(
                    ACTION_PENDING,
                    MenuGesture.ANY,
                    text(player, MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_PENDING_BUTTON_ACTION),
                    safety = MenuActionSafety.NAVIGATION_ONLY,
                )),
                glint = true,
            ),
        )
    }

    private fun subject(
        player: Player,
        route: MenuRoute,
    ): PlayerWorldCapabilitySubject {
        val request = request(route)
        return PlayerWorldCapabilitySubject(
            player,
            request.targetPlayerUuid,
            request.targetPlayerName,
            MyWorldManagerApi.getPlayerWorlds(request.targetPlayerUuid),
            request.showBackButton,
            route,
        )
    }

    private fun attributes(
        subject: PlayerWorldCapabilitySubject,
        worldData: WorldData? = null,
    ): Map<String, Any> = buildMap {
        put(PlayerWorldCapabilityContract.SUBJECT_ATTRIBUTE, subject)
        worldData?.let { put(PlayerWorldCapabilityContract.WORLD_ATTRIBUTE, it) }
    }

    private fun route(request: PlayerWorldRouteRequest): MenuRoute =
        MenuRoute(
            ROUTE_OWNER,
            ROUTE_ID,
            buildMap {
                put(PAYLOAD_PAGE, request.page.coerceAtLeast(0).toString())
                put(PAYLOAD_SHOW_BACK, request.showBackButton.toString())
                put(PAYLOAD_TARGET_UUID, request.targetPlayerUuid.toString())
                request.targetPlayerName?.let { put(PAYLOAD_TARGET_NAME, it) }
            },
        )

    private fun request(route: MenuRoute): PlayerWorldRouteRequest =
        PlayerWorldRouteRequest(
            page = route.payload[PAYLOAD_PAGE]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            showBackButton =
                route.payload[PAYLOAD_SHOW_BACK]?.toBooleanStrictOrNull() ?: false,
            targetPlayerUuid = route.payload[PAYLOAD_TARGET_UUID]
                ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                ?: java.util.UUID(0L, 0L),
            targetPlayerName = route.payload[PAYLOAD_TARGET_NAME],
        )

    private enum class CreationBlockReason(
        val displayKey: LocalizationKey<String>,
        val loreKey: LocalizationKey<List<String>>,
    ) {
        POLICY_DENIED(
            MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_UNAVAILABLE_POLICY_DENIED_DISPLAY,
            MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_UNAVAILABLE_POLICY_DENIED_LORE,
        ),
        NO_SLOT(
            MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_UNAVAILABLE_NO_SLOT_DISPLAY,
            MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_UNAVAILABLE_NO_SLOT_LORE,
        ),
        NO_PERMISSION(
            MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_UNAVAILABLE_NO_PERMISSION_DISPLAY,
            MyworldGuiBedrockKeys.GUI_PLAYER_WORLD_CREATION_UNAVAILABLE_NO_PERMISSION_LORE,
        ),
    }

    private companion object {
        const val CAPABILITY_OWNER = "mwm-chanpon"
        const val CURRENT_WORLD_CAPABILITY_ID = "player-world-item-current"
        const val AWAY_WORLD_CAPABILITY_ID = "player-world-item-away"
        const val SUMMARY_CAPABILITY_ID = "player-world-summary"
        const val CREATION_CAPABILITY_ID = "player-world-creation"
        const val ROUTE_OWNER = "mwm-chanpon"
        const val ROUTE_ID = "player-worlds"
        const val ACTION_PAGE = "page"
        const val ACTION_SETTINGS = "settings"
        const val ACTION_PENDING = "pending"
        const val ACTION_BACK = "back"
        const val PAYLOAD_PAGE = "page"
        const val PAYLOAD_SHOW_BACK = "show_back"
        const val PAYLOAD_TARGET_UUID = "target_uuid"
        const val PAYLOAD_TARGET_NAME = "target_name"
    }
}

internal fun isMoveToTopAvailable(
    viewerUuid: java.util.UUID,
    targetPlayerUuid: java.util.UUID,
    orderedWorldUuids: List<java.util.UUID>,
    targetWorldUuid: java.util.UUID,
): Boolean = viewerUuid == targetPlayerUuid && orderedWorldUuids.indexOf(targetWorldUuid) > 0

internal fun playerWorldCapabilityInvocation(
    slot: Int,
    capability: com.awabi2048.ccsystem.api.gui.ResolvedMenuCapability,
    attributes: Map<String, Any>,
    worldUuid: java.util.UUID? = null,
): GuiMenuCapabilityInvocationSpec = GuiMenuCapabilityInvocationSpec(
    slot = slot,
    capability = capability.requireExplicitActionSafety(),
    arguments = worldUuid?.let(::playerWorldCapabilityArguments).orEmpty(),
    attributes = attributes,
)

internal fun playerWorldCapabilityArguments(worldUuid: java.util.UUID): Map<String, String> =
    mapOf(PLAYER_WORLD_UUID_ARGUMENT to worldUuid.toString())

internal const val PLAYER_WORLD_UUID_ARGUMENT = "capability_world_uuid"
