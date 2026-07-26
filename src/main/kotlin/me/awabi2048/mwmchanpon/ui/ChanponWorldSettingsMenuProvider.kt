package me.awabi2048.mwmchanpon.ui
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiCycleDirection
import com.awabi2048.ccsystem.api.gui.GuiConfirmationLayout
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuIconAction
import com.awabi2048.ccsystem.api.gui.GuiMenuIconData
import com.awabi2048.ccsystem.api.gui.GuiMenuIconSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuProvider
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.api.event.MwmWarpReason
import me.awabi2048.myworldmanager.api.service.ExpansionExecutionMode
import me.awabi2048.myworldmanager.api.service.ExpansionSequenceOptions
import me.awabi2048.myworldmanager.api.service.ExpansionSequencePhase
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.util.GuiHelper
import me.awabi2048.myworldmanager.util.GuiItemFactory
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ChanponWorldSettingsMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val environmentGui: ChanponEnvironmentGui
) : WorldSettingsMenuProvider {
    private val accessGuard = WorldSettingsAccessGuard(storage, protectionService)
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_SETTINGS,
                renderer = { context -> render(context.player, source(context.route)) },
                actions = mapOf(ACTION_SELECT to MenuActionHandler(::handleAction)),
            )
        )
    }

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, worldData: WorldData, request: WorldSettingsMenuRequest): Boolean {
        runtime.navigate(player, route(Source(Screen.MAIN, worldData.uuid, request)))
        return true
    }

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

    private fun renderMain(player: Player, worldData: WorldData, request: WorldSettingsMenuRequest): InventoryMenuView {
        val mwm = mwm()
        mwm.logWorldSettingsDebug(
            "chanpon_open=request player=${player.name}/${player.uniqueId} world=${worldData.uuid} " +
                "showBack=${request.showBackButton} playerFlow=${request.isPlayerWorldFlow} " +
                "currentHolder=${player.openInventory.topInventory.holder?.javaClass?.name ?: "none"}"
        )
        mwm.settingsSessionManager.updateSessionAction(
            player,
            worldData.uuid,
            SettingsAction.VIEW_SETTINGS,
            true,
            null,
            request.isPlayerWorldFlow,
            request.parentShowBackButton
        )
        mwm.settingsSessionManager.getSession(player)?.showBackButton = request.showBackButton
        GuiHelper.scheduleGuiTransitionReset(mwm, player)

        val session = mwm.settingsSessionManager.getSession(player)
        val isAdminFlow = session?.isAdminFlow == true
        val isActualOwner = worldData.owner == player.uniqueId || isAdminFlow
        val isModerator = worldData.moderators.contains(player.uniqueId)
        val isImportedArchive = storage.isImportedArchive(worldData.uuid)
        val isOwner = isActualOwner && !isImportedArchive
        val hasManagePermission = (isOwner || isModerator) && !isImportedArchive
        val isProtected = protectionService.isProtected(worldData)

        val title = GuiHelper.inventoryTitle(
            mwm.languageManager.getComponent(
                player,
                "gui.settings.title",
                mapOf("world" to worldData.name)
            )
        )
        val layout = ChanponGuiElements.settingsLayout()
        val inventorySize = if (isProtected) PROTECTED_SETTINGS_SIZE else layout.size
        var items = mutableMapOf<Int, ItemStack>()

        if (isProtected && isOwner) {
            // 完成済み/提出済みのワールドでは、公開レベル・訪問通知・ポータル取得のみ中央行に中央寄せで配置する。
            // それ以外の設定は事後調整の対象外のため表示しない。
            items[21] = publishItem(player, worldData)
            items[22] = notificationItem(player, worldData)
        } else if (hasManagePermission) {
            items[19] = mwmItem(player, worldData, "info", Material.NAME_TAG, ItemTag.TYPE_GUI_SETTING_INFO)
            items[20] = mwmItem(player, worldData, "icon", Material.ANVIL, ItemTag.TYPE_GUI_SETTING_ICON)
            items[21] = mwmItem(player, worldData, "spawn", Material.COMPASS, ItemTag.TYPE_GUI_SETTING_SPAWN, requireTargetWorld = true)
            items[28] = mwmItem(player, worldData, "announcement", Material.OAK_SIGN, ItemTag.TYPE_GUI_SETTING_ANNOUNCEMENT)
            items[29] = notificationItem(player, worldData)
            items[47] = mwmItem(player, worldData, "tour", Material.PALE_OAK_BOAT, ItemTag.TYPE_GUI_SETTING_TOUR, "gui.tour.worldmenu")
        }
        if (isOwner && !isProtected) {
            items[23] = expansionItem(player, worldData)
            items[24] = publishItem(player, worldData)
            items[25] = mwmItem(player, worldData, "member", Material.PLAYER_HEAD, ItemTag.TYPE_GUI_SETTING_MEMBER)
            items[32] = mwmItem(player, worldData, "environment", Material.GRASS_BLOCK, ItemTag.TYPE_GUI_SETTING_ENVIRONMENT, requireTargetWorld = true)
            items[33] = criticalItem(player, worldData)
            if (mwm.portalRepository.findAll().any { it.worldKey == worldData.worldKey }) {
                items[ChanponWorldSettingsLayout.PORTAL_MANAGEMENT_SLOT] = mwmItem(
                    player,
                    worldData,
                    "portals",
                    Material.END_PORTAL_FRAME,
                    ItemTag.TYPE_GUI_SETTING_PORTALS,
                )
            }
        }
        items[4] = worldInfoItem(player, worldData)
        if (request.showBackButton) {
            items[if (isProtected) PROTECTED_BACK_SLOT else layout.infoSlot] = returnItem(player)
        }

        items = renderExtensions(
            items,
            player,
            worldData,
            request.showBackButton,
            isOwner,
            isModerator,
            isProtected,
            inventorySize,
        ).toMutableMap()
        mwm.logWorldSettingsDebug(
            "chanpon_open=inventory player=${player.name}/${player.uniqueId} world=${worldData.uuid} " +
                "size=$inventorySize protected=$isProtected"
        )
        return InventoryMenuView(inventorySize, title, menuElements(items))
    }

    private fun handleAction(context: MenuActionContext): MenuActionResult {
        val source = source(context.route)
        when (source.screen) {
            Screen.MAIN -> handleSettingsClick(context.player, source, context.item, context.click)
            Screen.EXPANSION -> handleExpansionClick(context.player, source, context.item)
            Screen.MODIFIED_WARNING -> handleWarningClick(context.player, source, context.item)
            Screen.CONFIRM -> handleConfirmClick(context.player, source, context.item)
            Screen.CRITICAL -> handleCriticalClick(context.player, source, context.item)
            Screen.DELETE_CONFIRM -> handleDeleteConfirmClick(context.player, source, context.item)
        }
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun handleSettingsClick(
        player: Player,
        source: Source,
        item: ItemStack,
        click: org.bukkit.event.inventory.ClickType,
    ) {
        val worldUuid = source.worldUuid
        val mwm = mwm()
        if (mwm.settingsSessionManager.getSession(player) == null) {
            mwm.logWorldSettingsDebug(
                "chanpon_click=session_recovery player=${player.name}/${player.uniqueId} world=$worldUuid"
            )
            mwm.settingsSessionManager.updateSessionAction(
                player,
                worldUuid,
                SettingsAction.VIEW_SETTINGS,
                isGui = false
            )
        }
        mwm.settingsSessionManager.getSession(player)?.let { it.isGuiTransition = false }
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val session = mwm.settingsSessionManager.getSession(player)
        val isOwner = worldData.owner == player.uniqueId || session?.isAdminFlow == true
        val isModerator = worldData.moderators.contains(player.uniqueId)

        val type = ItemTag.getType(item)
        mwm.logWorldSettingsDebug(
            "chanpon_click=dispatch player=${player.name}/${player.uniqueId} world=$worldUuid " +
                "itemType=${type ?: "none"} sessionAction=${session?.action ?: "none"}"
        )
        // オーバーレイはMWM公開APIで表現できる設定だけを処理し、未公開のタグ編集入口を再公開しない。
        if (type == ItemTag.TYPE_GUI_INFO && ItemTag.getWorldUuid(item) == worldData.uuid) {
            if (isCurrentWorld(player, worldData)) return
            mwm.settingsSessionManager.getSession(player)?.isGuiTransition = true
            mwm.worldService.teleportToWorldKeepingInventory(
                player,
                worldData.uuid,
                MwmWarpReason.DIRECT,
                Runnable {
                    mwm.logWorldSettingsDebug(
                        "chanpon_warp=callback player=${player.name}/${player.uniqueId} world=${worldData.uuid} " +
                            "currentWorld=${player.world.name} holder=${player.openInventory.topInventory.holder?.javaClass?.name ?: "none"}"
                    )
                    mwm.settingsSessionManager.getSession(player)?.isGuiTransition = true
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (player.isOnline) {
                            player.sendMessage(
                                mwm.languageManager.getMessage(
                                    player,
                                    "messages.warp_success",
                                    mapOf("world" to worldData.name)
                                )
                            )
                            open(player, worldData, currentRequest(player))
                            mwm.settingsSessionManager.getSession(player)?.isGuiTransition = false
                            mwm.logWorldSettingsDebug(
                                "chanpon_warp=reopened player=${player.name}/${player.uniqueId} world=${worldData.uuid} " +
                                    "holder=${player.openInventory.topInventory.holder?.javaClass?.name ?: "none"}"
                            )
                        }
                    }, 3L)
                }
            )
            return
        }
        val ownerOnlyTypes = setOf(
            ItemTag.TYPE_GUI_SETTING_EXPAND,
            ItemTag.TYPE_GUI_SETTING_CRITICAL,
            ItemTag.TYPE_GUI_SETTING_ENVIRONMENT,
            ItemTag.TYPE_GUI_SETTING_PUBLISH,
            ItemTag.TYPE_GUI_SETTING_MEMBER,
        )
        val managerTypes = setOf(
            ItemTag.TYPE_GUI_SETTING_INFO,
            ItemTag.TYPE_GUI_SETTING_ICON,
            ItemTag.TYPE_GUI_SETTING_SPAWN,
            ItemTag.TYPE_GUI_SETTING_NOTIFICATION,
            ItemTag.TYPE_GUI_SETTING_ANNOUNCEMENT,
            ItemTag.TYPE_GUI_SETTING_TOUR,
        )
        val inWorldTypes = setOf(
            ItemTag.TYPE_GUI_SETTING_SPAWN,
            ItemTag.TYPE_GUI_SETTING_EXPAND,
            ItemTag.TYPE_GUI_SETTING_ENVIRONMENT,
            ItemTag.TYPE_GUI_SETTING_CRITICAL,
        )
        if (type in ownerOnlyTypes || type in managerTypes) {
            // 完成済み/提出済みでも許可する設定（公開レベル・訪問通知・ポータル取得）。
            val protectedKind = when (type) {
                ItemTag.TYPE_GUI_SETTING_PUBLISH -> WorldSettingsAccessGuard.ProtectedSettingsKind.PUBLISH
                ItemTag.TYPE_GUI_SETTING_NOTIFICATION -> WorldSettingsAccessGuard.ProtectedSettingsKind.NOTIFICATION
                ItemTag.TYPE_GUI_SETTING_PORTALS -> WorldSettingsAccessGuard.ProtectedSettingsKind.PORTAL
                else -> WorldSettingsAccessGuard.ProtectedSettingsKind.NONE
            }
            if (!accessGuard.allow(
                    player,
                    worldData,
                    ownerOnly = type in ownerOnlyTypes,
                    requireTargetWorld = type in inWorldTypes,
                    protectedSettings = protectedKind,
                )
            ) return
        }
        when (type) {
            ItemTag.TYPE_GUI_SETTING_EXPAND -> {
                if (worldData.borderExpansionLevel == WorldData.EXPANSION_LEVEL_SPECIAL) return
                if (worldData.borderExpansionLevel >= maxExpansionLevel()) {
                    player.sendMessage(i18nComponent(player, "message.expand_max"))
                    return
                }
                pushSettingsRoute(player, worldData)
                if (mwm.playerPlatformResolver.isBedrock(player)) {
                    openConfirmByPlatform(player, worldData, ConfirmAction.EXPAND, null)
                } else {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_SETTING_CRITICAL -> {
                pushSettingsRoute(player, worldData)
                openCriticalMenu(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_ENVIRONMENT -> {
                pushSettingsRoute(player, worldData)
                environmentGui.open(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_INFO -> {
                pushSettingsRoute(player, worldData)
                mwm.worldSettingsGui.editWorldInfo(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_ICON -> {
                pushSettingsRoute(player, worldData)
                mwm.worldSettingsGui.enterIconSelection(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_SPAWN -> {
                pushSettingsRoute(player, worldData)
                val isGuest = if (mwm.playerPlatformResolver.isBedrock(player)) true else click.isLeftClick
                mwm.worldSettingsGui.enterSpawnSetting(player, worldData, isGuest)
            }
            ItemTag.TYPE_GUI_SETTING_PUBLISH -> {
                val nextVisibility = cycleVisibility(
                    storage.getVisibility(worldUuid),
                    GuiCycle.direction(click) ?: return
                )
                storage.setVisibility(worldUuid, nextVisibility)
                open(player, worldData, currentRequest(player))
            }
            ItemTag.TYPE_GUI_SETTING_MEMBER -> {
                pushSettingsRoute(player, worldData)
                mwm.worldSettingsGui.openMemberManagement(player, worldData, 0, true)
            }
            ItemTag.TYPE_GUI_SETTING_NOTIFICATION -> {
                mwm.worldSettingsGui.toggleNotification(player, worldData)
                open(player, worldData, currentRequest(player))
            }
            ItemTag.TYPE_GUI_SETTING_ANNOUNCEMENT -> {
                if (click.isLeftClick) {
                    pushSettingsRoute(player, worldData)
                    mwm.worldSettingsGui.editAnnouncement(player, worldData)
                } else {
                    mwm.worldSettingsGui.clearAnnouncements(player, worldData)
                }
                open(player, worldData, currentRequest(player))
            }
            ItemTag.TYPE_GUI_SETTING_TOUR -> {
                pushSettingsRoute(player, worldData)
                mwm.tourGui.openEditMenu(player, worldData, 0)
            }
            ItemTag.TYPE_GUI_SETTING_VISITOR -> {
                pushSettingsRoute(player, worldData)
                mwm.worldSettingsGui.openVisitorManagement(player, worldData, 0)
            }
            ItemTag.TYPE_GUI_SETTING_PORTALS -> {
                mwm.worldSettingsGui.openPortalManagement(player, worldData, 0)
            }
            ItemTag.TYPE_GUI_RETURN -> {
                runtime.back(player)
            }
            ItemTag.TYPE_GUI_EXTENSION -> {
                val extensionId = ItemTag.getExtensionId(item) ?: return
                val extension = MyWorldManagerApi.getMenuExtensions().firstOrNull { it.getId() == extensionId } ?: return
                val extensionMenuType = "world_settings"
                extension.onClick(click, item, player, MenuExtensionContext(
                    extensionMenuType,
                    mutableMapOf(
                        "worldData" to worldData,
                        "showBackButton" to currentRequest(player).showBackButton,
                        "isOwner" to isOwner,
                        "isModerator" to isModerator
                    )
                ))
            }
        }
    }

    private fun handleExpansionClick(player: Player, source: Source, item: ItemStack) {
        val worldUuid = source.worldUuid
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!runtime.back(player)) {
                    open(player, worldData, currentRequest(player))
                }
            }
            TYPE_EXPAND_DIRECTION -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                when (ItemTag.getString(item, DIRECTION_KEY)) {
                    ACTION_CENTER -> {
                        openConfirmByPlatform(player, worldData, ConfirmAction.EXPAND, null)
                    }
                    ACTION_SELECT_DIRECTION -> {
                        val started = MyWorldManagerApi.getWorldService()?.startWorldBorderExpansionSequence(
                            player,
                            worldUuid,
                            ExpansionSequenceOptions(
                                ExpansionSequencePhase.DIRECTION_SELECT,
                                null,
                                ExpansionExecutionMode.IMMEDIATE_NO_COST,
                                setOf(ExpansionSequencePhase.METHOD_SELECT)
                            )
                        ) == true
                        if (!started) {
                            player.sendMessage(i18nComponent(player, "message.expand_failed"))
                        }
                    }
                }
            }
            ItemTag.TYPE_GUI_SETTING_STEP_BACK_EXPANSION -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                if (worldData.latestBorderExpansionRecord()?.modified == true) {
                    openModifiedWarning(player, worldData, ConfirmAction.STEP_BACK)
                } else {
                    openConfirmByPlatform(player, worldData, ConfirmAction.STEP_BACK, null)
                }
            }
        }
    }

    private fun handleWarningClick(player: Player, source: Source, item: ItemStack) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid) ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!runtime.back(player)) {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                val action = source.confirmAction ?: return
                openConfirmByPlatform(player, worldData, action, null)
            }
        }
    }

    private fun handleConfirmClick(player: Player, source: Source, item: ItemStack) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid) ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!runtime.back(player)) {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                executeConfirmAction(
                    player,
                    worldData,
                    source.confirmAction ?: return,
                    source.direction,
                )
            }
        }
    }

    private fun handleCriticalClick(player: Player, source: Source, item: ItemStack) {
        val worldUuid = source.worldUuid
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!runtime.back(player)) {
                    open(player, worldData, currentRequest(player))
                }
            }
            ItemTag.TYPE_GUI_SETTING_DELETE_WORLD -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                if (storage.isProductionWorld(worldUuid)) return
                openDeleteConfirm(player, worldData, false)
            }
        }
    }

    private fun handleDeleteConfirmClick(player: Player, source: Source, item: ItemStack) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid) ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!runtime.back(player)) {
                    openCriticalMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                if (!source.finalStep) {
                    openDeleteConfirm(player, worldData, true)
                    return
                }
                runtime.close(player)
                mwm().worldService.deleteWorld(source.worldUuid, player).thenAccept { success ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(i18nComponent(player, if (success) "critical.deleted" else "critical.delete_failed"))
                        if (success) {
                            mwm().settingsSessionManager.endSession(player)
                        }
                    })
                }
            }
        }
    }

    private fun openExpansionMenu(player: Player, worldData: WorldData) {
        runtime.navigate(player, route(Source(Screen.EXPANSION, worldData.uuid, currentRequest(player))))
    }

    private fun openModifiedWarning(player: Player, worldData: WorldData, action: ConfirmAction) {
        runtime.navigate(
            player,
            route(Source(Screen.MODIFIED_WARNING, worldData.uuid, currentRequest(player), action)),
        )
    }

    private fun openConfirm(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?) {
        runtime.navigate(
            player,
            route(
                Source(
                    Screen.CONFIRM,
                    worldData.uuid,
                    currentRequest(player),
                    action,
                    direction,
                ),
            ),
        )
    }

    private fun openCriticalMenu(player: Player, worldData: WorldData) {
        if (criticalUnavailableLoreKey(player, worldData) != null) {
            player.sendMessage(i18nComponent(player, "critical.unavailable_message"))
            return
        }
        runtime.navigate(player, route(Source(Screen.CRITICAL, worldData.uuid, currentRequest(player))))
    }

    private fun openConfirmByPlatform(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?) {
        if (mwm().playerPlatformResolver.isBedrock(player) && openBedrockConfirmForm(player, worldData, action, direction)) {
            return
        }
        openConfirm(player, worldData, action, direction)
    }

    private fun openBedrockConfirmForm(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?): Boolean {
        val directionName = direction?.let { directionName(player, it) } ?: i18n(player, "direction.center")
        val modified = action == ConfirmAction.STEP_BACK && worldData.latestBorderExpansionRecord()?.modified == true
        return mwm().floodgateFormBridge.sendSimpleForm(
            player = player,
            title = i18n(player, "confirm.${action.key}.title"),
            content = confirmContentLines(player, action, directionName, modified).joinToString("\n"),
            buttons = listOf(
                i18n(player, "confirm.button.confirm"),
                i18n(player, "confirm.button.cancel")
            ),
            onSelect = { index ->
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@Runnable
                    if (index == 0) {
                        executeConfirmAction(player, latest, action, direction)
                    } else {
                        open(player, latest, currentRequest(player))
                    }
                })
            },
            onClosed = {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@Runnable
                    open(player, latest, currentRequest(player))
                })
            }
        )
    }

    private fun executeConfirmAction(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?) {
        val service = MyWorldManagerApi.getWorldService()
        val success = when (action) {
            ConfirmAction.EXPAND -> service?.expandWorldBorder(worldData.uuid, direction) == true
            ConfirmAction.STEP_BACK -> service?.stepBackWorldBorder(worldData.uuid) == true
        }
        val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: worldData
        player.sendMessage(i18nComponent(player, if (success) action.successKey else action.failedKey))
        when {
            success && action == ConfirmAction.EXPAND -> {
                mwm().settingsSessionManager.endSession(player)
                runtime.close(player)
            }
            success -> openExpansionMenu(player, latest)
            else -> open(player, latest, currentRequest(player))
        }
    }

    private fun openDeleteConfirm(player: Player, worldData: WorldData, finalStep: Boolean) {
        runtime.navigate(
            player,
            route(Source(Screen.DELETE_CONFIRM, worldData.uuid, currentRequest(player), finalStep = finalStep)),
        )
    }

    private fun renderExpansion(player: Player, worldData: WorldData): InventoryMenuView {
        val items = mutableMapOf(
            20 to expansionOptionItem(player, "center", Material.MAP, ACTION_CENTER),
            24 to expansionOptionItem(player, "direction_select", Material.COMPASS, ACTION_SELECT_DIRECTION),
            40 to returnItem(player),
        )
        if (worldData.latestBorderExpansionRecord() != null) {
            items[42] = stepBackItem(player, worldData)
        }
        return InventoryMenuView(45, i18nComponent(player, "expand_menu.title"), menuElements(items))
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
            menuElements(mapOf(36 to returnItem(player), 22 to deleteWorldItem(player))),
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
        val centerItem =
            componentItem(
                Material.TNT,
                i18n(player, "${key}_display"),
                lore,
                ItemTag.TYPE_GUI_INFO
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

    private fun pushSettingsRoute(player: Player, worldData: WorldData) {
        val request = currentRequest(player)
        mwm().mwmMenuRoutes.pushCustom(
            player,
            "chanpon.world_settings:${worldData.uuid}:$request"
        ) { target ->
            val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@pushCustom false
            open(target, latest, request)
            true
        }
    }

    private fun renderExtensions(
        sourceItems: Map<Int, ItemStack>,
        player: Player,
        worldData: WorldData,
        showBackButton: Boolean,
        isOwner: Boolean,
        isModerator: Boolean,
        isProtected: Boolean = false,
        inventorySize: Int,
    ): Map<Int, ItemStack> {
        val context = MenuExtensionContext(
            "world_settings",
            mutableMapOf(
                "worldData" to worldData,
                "showBackButton" to showBackButton,
                "isOwner" to isOwner,
                "isMember" to worldData.members.contains(player.uniqueId),
                "isModerator" to isModerator,
                "isProtected" to isProtected,
                "inventorySize" to inventorySize,
            )
        )
        var items = sourceItems
        MyWorldManagerApi.getMenuExtensions().forEach { extension ->
            items = extension.onRender(items, player, context)
        }
        return items
    }

    private fun publishItem(player: Player, worldData: WorldData): ItemStack {
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
                add(GuiLoreLine.Spacer)
                addAll(GuiLoreActions.cyclePreviousNext(mwm().languageManager, player))
            }, GuiLoreFrame.BOTH)
        )
        return componentItem(Material.OAK_SIGN, i18n(player, "publish.title"), lore, ItemTag.TYPE_GUI_SETTING_PUBLISH)
    }

    private fun expansionItem(player: Player, worldData: WorldData): ItemStack {
        val level = worldData.borderExpansionLevel
        val max = maxExpansionLevel()
        val lines = if (level >= max) i18nList(player, "expand.status_max") else emptyList()
        return componentItem(
            Material.FILLED_MAP,
            i18n(player, "expand.display"),
            describedStructuredLore(
                player,
                mwm().languageManager.getMessageList(player, "gui.settings.expand.blocks.description").map(GuiLoreLine::Text),
                lines.map(GuiLoreLine::Text),
                requireTargetWorld = true,
                worldData = worldData
            ),
            ItemTag.TYPE_GUI_SETTING_EXPAND
        )
    }

    private fun notificationItem(player: Player, worldData: WorldData): ItemStack {
        val status = i18n(player, if (worldData.notificationEnabled) "common.on" else "common.off")
        val singleAction = GuiLoreActions.singleClick(
            mwm().languageManager,
            player,
            i18n(player, "notification.action")
        )
        val item = CCSystem.getAPI().getGuiElementService().menuIcon(
            GuiMenuIconSpec(
                Material.BELL,
                GuiNameSpec.Text(
                    mwm().languageManager.getMessage(player, "gui.settings.notification.display"),
                    GuiNameStyle.DEFAULT
                ),
                GuiElementRole.CONTENT,
                1,
                mwm().languageManager.getMessageList(player, "gui.settings.notification.blocks.description"),
                listOf(GuiMenuIconData(i18n(player, "notification.current_label"), status, "§e")),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(
                    GuiMenuIconAction(
                        singleAction.operation,
                        singleAction.action,
                        singleAction.resolvedText,
                        true
                    )
                ),
                worldData.notificationEnabled
            )
        )
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_SETTING_NOTIFICATION)
        return item
    }

    private fun criticalItem(player: Player, worldData: WorldData): ItemStack {
        criticalUnavailableLoreKey(player, worldData)?.let { loreKey ->
            return criticalUnavailableItem(player, loreKey)
        }
        return componentItem(
            Material.TNT,
            i18n(player, "critical.display"),
            describedStructuredLore(
                player,
                mwm().languageManager.getMessageList(player, "gui.settings.critical.blocks.summary").map(GuiLoreLine::Text),
                listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "critical.action"))),
                requireTargetWorld = true,
                worldData = worldData
            ),
            ItemTag.TYPE_GUI_SETTING_CRITICAL
        )
    }

    private fun criticalUnavailableLoreKey(player: Player, worldData: WorldData): String? {
        // 重大な設定に入れない理由を一覧表示と遷移防御で共有し、専用メニューへの侵入を防ぐ。
        if (storage.isProductionWorld(worldData.uuid)) return "critical.production_warning"
        if (!mwm().playerStatsRepository.findByUuid(player.uniqueId).criticalSettingsEnabled) return "critical.disabled_warning"
        return null
    }

    private fun deleteWorldItem(player: Player): ItemStack {
        val lore = CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(listOf(
            GuiLoreBlock(listOf(
                GuiLoreLine.Text(i18n(player, "critical.delete_description")),
                GuiLoreLine.Danger(i18n(player, "critical.delete_danger"))
            )),
            GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "critical.delete_action"))))
        )))
        return componentItem(Material.TNT, i18n(player, "critical.delete_display"), lore, ItemTag.TYPE_GUI_SETTING_DELETE_WORLD)
    }

    private fun criticalUnavailableItem(player: Player, loreKey: String): ItemStack {
        return componentItem(
            Material.BARRIER,
            i18n(player, "critical.unavailable_display"),
            describedStructuredLore(
                player,
                mwm().languageManager.getMessageList(player, "gui.settings.critical.blocks.summary").map(GuiLoreLine::Text),
                listOf(GuiLoreLine.Warning(i18n(player, loreKey)))
            ),
            ItemTag.TYPE_GUI_INFO
        )
    }

    private fun worldInfoItem(player: Player, worldData: WorldData): ItemStack {
        val lore = CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(buildList {
            add(GuiLoreBlock(listOf(
                GuiLoreLine.Data(i18n(player, "info.description_label"), worldData.description.ifBlank { "-" }, "§f")
            )))
            if (!isCurrentWorld(player, worldData)) {
                add(GuiLoreBlock(listOf(GuiLoreActions.singleClick(
                    mwm().languageManager,
                    player,
                    mwm().languageManager.getMessage(player, "gui.player_world.world_item.warp")
                ))))
            }
        }))
        val item = componentItem(
            worldData.icon,
            "§e${worldData.name}",
            lore,
            ItemTag.TYPE_GUI_INFO
        )
        ItemTag.setWorldUuid(item, worldData.uuid)
        return item
    }

    private fun mwmItem(
        player: Player,
        worldData: WorldData,
        iconKey: String,
        material: Material,
        type: String,
        langPrefix: String = "gui.settings.$iconKey",
        requireTargetWorld: Boolean = false
    ): ItemStack {
        val display = mwm().languageManager.getMessage(player, "$langPrefix.display")
        val lore = describedStructuredLore(
            player,
            mwmDescription(player, iconKey),
            listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "mwm_action.action"))),
            requireTargetWorld = requireTargetWorld,
            worldData = worldData
        )
        val icon = mwm().menuConfigManager.getIconMaterial("world_settings", iconKey, material)
        return componentItem(icon, display, lore, type)
    }

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
        return mwm().languageManager.getMessageList(player, key).map(GuiLoreLine::Text)
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

    private fun expansionOptionItem(player: Player, key: String, material: Material, action: String): ItemStack {
        val item = componentItem(
            material,
            i18n(player, "expand_menu.$key.display"),
            loreBlocks(listOf(
                GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "expand_menu.$key.description")))),
                GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "expand_menu.$key.action"))))
            )),
            TYPE_EXPAND_DIRECTION
        )
        ItemTag.setString(item, DIRECTION_KEY, action)
        return item
    }

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
                GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "step_back.action"))))
            )),
            ItemTag.TYPE_GUI_SETTING_STEP_BACK_EXPANSION
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
                GuiLoreBlock(listOf(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "modified_warning.action"))))
            )),
            ItemTag.TYPE_GUI_INFO
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
        return componentItem(action.material, i18n(player, "confirm.${action.key}.display"), loreBlocks(listOf(GuiLoreBlock(lines))), ItemTag.TYPE_GUI_INFO)
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
            if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL
        )
    }

    private fun warningButton(player: Player, continueAction: Boolean): ItemStack {
        return item(
            if (continueAction) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            i18n(player, if (continueAction) "modified_warning.button.continue" else "modified_warning.button.cancel"),
            emptyList(),
            if (continueAction) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL
        )
    }

    private fun returnItem(player: Player): ItemStack {
        return item(Material.REDSTONE, i18n(player, "common.back"), emptyList(), ItemTag.TYPE_GUI_RETURN)
    }

    private fun item(material: Material, display: String, loreLines: List<GuiLoreLine>, type: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(display))
            if (loreLines.isNotEmpty()) {
                meta.lore(ChanponLore.build(loreLines))
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, type)
        return item
    }

    private fun componentItem(material: Material, display: String, loreComponents: List<Component>, type: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(display))
            if (loreComponents.isNotEmpty()) {
                // loreComponents are already the final output of CC-System's LoreService.
                meta.lore(loreComponents)
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, type)
        return item
    }

    private fun currentRequest(player: Player): WorldSettingsMenuRequest {
        val session = mwm().settingsSessionManager.getSession(player)
        return WorldSettingsMenuRequest(
            session?.showBackButton ?: false,
            session?.isPlayerWorldFlow,
            session?.parentShowBackButton
        )
    }

    private fun isCurrentWorld(player: Player, worldData: WorldData): Boolean =
        mwm().worldConfigRepository.findByWorldName(player.world.name)?.uuid == worldData.uuid

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

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.settings.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.settings.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.settings.$key", placeholders)
    }

    private fun menuElements(items: Map<Int, ItemStack>): List<MenuElement> =
        items.map { (slot, item) ->
            val type = ItemTag.getType(item)
            val role = when (type) {
                ItemTag.TYPE_GUI_DECORATION -> GuiElementRole.DECORATION
                ItemTag.TYPE_GUI_CONFIRM -> GuiElementRole.CONFIRM
                ItemTag.TYPE_GUI_CANCEL -> GuiElementRole.CANCEL
                ItemTag.TYPE_GUI_RETURN -> GuiElementRole.BACK
                ItemTag.TYPE_GUI_INFO -> GuiElementRole.CONTENT
                else -> GuiElementRole.ACTION
            }
            val clickableInfo =
                type == ItemTag.TYPE_GUI_INFO && ItemTag.getWorldUuid(item) != null
            MenuElement(
                slot,
                item,
                if (clickableInfo) GuiElementRole.ACTION else role,
                if (role == GuiElementRole.DECORATION ||
                    (role == GuiElementRole.CONTENT && !clickableInfo)
                ) null else ACTION_SELECT,
            )
        }

    private fun confirmationElements(
        layout: GuiConfirmationLayout,
        center: ItemStack,
        confirm: ItemStack,
        cancel: ItemStack,
    ): List<MenuElement> = menuElements(
        mapOf(
            layout.previewSlot to center,
            layout.confirmSlot to confirm,
            layout.cancelSlot to cancel,
        ),
    )

    private data class Source(
        val screen: Screen,
        val worldUuid: UUID,
        val request: WorldSettingsMenuRequest,
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
            request = WorldSettingsMenuRequest(
                showBackButton = payload[PAYLOAD_SHOW_BACK]?.toBooleanStrictOrNull() ?: false,
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
        private const val PROVIDER_ID = "chanpon.world_settings"
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val ROUTE_SETTINGS = "world-settings"
        private const val ACTION_SELECT = "select"
        private const val PAYLOAD_SCREEN = "screen"
        private const val PAYLOAD_WORLD_UUID = "world_uuid"
        private const val PAYLOAD_SHOW_BACK = "show_back"
        private const val PAYLOAD_PLAYER_FLOW = "player_flow"
        private const val PAYLOAD_PARENT_BACK = "parent_back"
        private const val PAYLOAD_CONFIRM_ACTION = "confirm_action"
        private const val PAYLOAD_DIRECTION = "direction"
        private const val PAYLOAD_FINAL_STEP = "final_step"
        private const val PROTECTED_SETTINGS_SIZE = 45
        private const val PROTECTED_BACK_SLOT = 40
        private const val TYPE_EXPAND_DIRECTION = "chanpon_expand_direction"
        private const val DIRECTION_KEY = "chanpon_direction"
        private const val ACTION_CENTER = "center"
        private const val ACTION_SELECT_DIRECTION = "select_direction"
    }
}
