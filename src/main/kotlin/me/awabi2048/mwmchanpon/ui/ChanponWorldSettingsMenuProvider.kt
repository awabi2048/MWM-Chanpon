package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuProvider
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.api.event.MwmWarpReason
import me.awabi2048.myworldmanager.api.service.ExpansionExecutionMode
import me.awabi2048.myworldmanager.api.service.ExpansionSequenceOptions
import me.awabi2048.myworldmanager.api.service.ExpansionSequencePhase
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.util.GuiHelper
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ChanponWorldSettingsMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val environmentGui: ChanponEnvironmentGui
) : WorldSettingsMenuProvider, Listener {
    private val accessGuard = WorldSettingsAccessGuard(storage, protectionService)

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, worldData: WorldData, request: WorldSettingsMenuRequest): Boolean {
        val mwm = mwm()
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

        val holder = SettingsHolder(worldData.uuid, request)
        val title = GuiHelper.inventoryTitle(
            mwm.languageManager.getComponent(
                player,
                "gui.settings.title",
                mapOf("world" to worldData.name)
            )
        )
        val layout = ChanponGuiElements.settingsLayout()
        val inventorySize = if (isProtected) PROTECTED_SETTINGS_SIZE else layout.size
        val inventory = Bukkit.createInventory(holder, inventorySize, title)
        holder.inv = inventory

        applyFrame(inventory)

        if (isProtected && isOwner) {
            // 完成済み/提出済みのワールドでは、公開レベル・訪問通知・ポータル取得のみ中央行に中央寄せで配置する。
            // それ以外の設定は事後調整の対象外のため表示しない。
            inventory.setItem(21, publishItem(player, worldData))
            inventory.setItem(22, notificationItem(player, worldData))
        } else if (hasManagePermission) {
            inventory.setItem(19, mwmItem(player, "info", Material.NAME_TAG, ItemTag.TYPE_GUI_SETTING_INFO))
            inventory.setItem(20, mwmItem(player, "icon", Material.ANVIL, ItemTag.TYPE_GUI_SETTING_ICON))
            inventory.setItem(21, mwmItem(player, "spawn", Material.COMPASS, ItemTag.TYPE_GUI_SETTING_SPAWN, requireTargetWorld = true))
            inventory.setItem(28, mwmItem(player, "announcement", Material.OAK_SIGN, ItemTag.TYPE_GUI_SETTING_ANNOUNCEMENT))
            inventory.setItem(29, notificationItem(player, worldData))
            inventory.setItem(47, mwmItem(player, "tour", Material.PALE_OAK_BOAT, ItemTag.TYPE_GUI_SETTING_TOUR, "gui.tour.worldmenu"))
        }
        if (isOwner && !isProtected) {
            inventory.setItem(23, expansionItem(player, worldData))
            inventory.setItem(24, publishItem(player, worldData))
            inventory.setItem(25, mwmItem(player, "member", Material.PLAYER_HEAD, ItemTag.TYPE_GUI_SETTING_MEMBER))
            inventory.setItem(32, mwmItem(player, "environment", Material.GRASS_BLOCK, ItemTag.TYPE_GUI_SETTING_ENVIRONMENT, requireTargetWorld = true))
            inventory.setItem(33, criticalItem(player, worldData))
        }
        inventory.setItem(4, worldInfoItem(player, worldData))
        if (request.showBackButton) {
            inventory.setItem(if (isProtected) PROTECTED_BACK_SLOT else layout.backSlot, returnItem(player))
        }

        fill(inventory)
        renderExtensions(inventory, player, worldData, request.showBackButton, isOwner, isModerator, isProtected)
        player.openInventory(inventory)
        return true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        when (val holder = event.view.topInventory.holder) {
            is SettingsHolder -> handleSettingsClick(event, player, holder.worldUuid)
            is ExpansionHolder -> handleExpansionClick(event, player, holder.worldUuid)
            is WarningHolder -> handleWarningClick(event, player, holder)
            is ConfirmHolder -> handleConfirmClick(event, player, holder)
            is CriticalHolder -> handleCriticalClick(event, player, holder.worldUuid)
            is DeleteConfirmHolder -> handleDeleteConfirmClick(event, player, holder)
        }
    }

    private fun handleSettingsClick(event: InventoryClickEvent, player: Player, worldUuid: UUID) {
        event.cancelWithDebug("ChanponWorldSettingsMenuProvider.handleSettingsClick: menu_click")
        val mwm = mwm()
        mwm.settingsSessionManager.getSession(player)?.let { it.isGuiTransition = false }
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val session = mwm.settingsSessionManager.getSession(player)
        val isOwner = worldData.owner == player.uniqueId || session?.isAdminFlow == true
        val isModerator = worldData.moderators.contains(player.uniqueId)

        val type = ItemTag.getType(item)
        if (type == ItemTag.TYPE_GUI_INFO && ItemTag.getWorldUuid(item) == worldData.uuid) {
            if (isCurrentWorld(player, worldData)) return
            mwm.soundManager.playClickSound(player, item, "world_settings")
            mwm.worldService.teleportToWorldKeepingInventory(
                player,
                worldData.uuid,
                MwmWarpReason.DIRECT,
                Runnable {
                player.sendMessage(
                    mwm.languageManager.getMessage(
                        player,
                        "messages.warp_success",
                        mapOf("world" to worldData.name)
                    )
                )
                open(player, worldData, currentRequest(player))
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
                mwm.soundManager.playClickSound(player, item, "world_settings")
                pushSettingsRoute(player, worldData)
                if (mwm.playerPlatformResolver.isBedrock(player)) {
                    openConfirmByPlatform(player, worldData, ConfirmAction.EXPAND, null)
                } else {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_SETTING_CRITICAL -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                pushSettingsRoute(player, worldData)
                openCriticalMenu(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_ENVIRONMENT -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
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
                val isGuest = if (mwm.playerPlatformResolver.isBedrock(player)) true else event.isLeftClick
                mwm.worldSettingsGui.enterSpawnSetting(player, worldData, isGuest)
            }
            ItemTag.TYPE_GUI_SETTING_PUBLISH -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                val nextVisibility = nextVisibility(storage.getVisibility(worldUuid), event.isRightClick)
                storage.setVisibility(worldUuid, nextVisibility)
                open(player, worldData, currentRequest(player))
            }
            ItemTag.TYPE_GUI_SETTING_MEMBER -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                pushSettingsRoute(player, worldData)
                mwm.worldSettingsGui.openMemberManagement(player, worldData, 0, true)
            }
            ItemTag.TYPE_GUI_SETTING_NOTIFICATION -> {
                mwm.worldSettingsGui.toggleNotification(player, worldData)
                open(player, worldData, currentRequest(player))
            }
            ItemTag.TYPE_GUI_SETTING_TAGS -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                mwm.worldSettingsGui.openTagEditor(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_ANNOUNCEMENT -> {
                if (event.isLeftClick) {
                    pushSettingsRoute(player, worldData)
                    mwm.worldSettingsGui.editAnnouncement(player, worldData)
                } else {
                    mwm.worldSettingsGui.clearAnnouncements(player, worldData)
                }
                open(player, worldData, currentRequest(player))
            }
            ItemTag.TYPE_GUI_SETTING_TOUR -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                pushSettingsRoute(player, worldData)
                mwm.tourGui.openEditMenu(player, worldData, 0)
            }
            ItemTag.TYPE_GUI_SETTING_VISITOR -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                pushSettingsRoute(player, worldData)
                mwm.worldSettingsGui.openVisitorManagement(player, worldData, 0)
            }
            ItemTag.TYPE_GUI_SETTING_PORTALS -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                mwm.worldSettingsGui.openPortalManagement(player, worldData, 0)
            }
            ItemTag.TYPE_GUI_RETURN -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                if (!mwm.menuRouteHistory.openPrevious(player)) {
                    player.closeInventory()
                }
            }
            ItemTag.TYPE_GUI_EXTENSION -> {
                val extensionId = ItemTag.getExtensionId(item) ?: return
                val extension = MyWorldManagerApi.getMenuExtensions().firstOrNull { it.getId() == extensionId } ?: return
                mwm.soundManager.playClickSound(player, item, "world_settings")
                val extensionMenuType = "world_settings"
                extension.onClick(event, player, MenuExtensionContext(
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

    private fun handleExpansionClick(event: InventoryClickEvent, player: Player, worldUuid: UUID) {
        event.cancelWithDebug("ChanponWorldSettingsMenuProvider.handleExpansionClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                mwm().soundManager.playClickSound(player, item, "world_settings")
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    open(player, worldData, currentRequest(player))
                }
            }
            TYPE_EXPAND_DIRECTION -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                mwm().soundManager.playClickSound(player, item, "world_settings")
                when (ItemTag.getString(item, DIRECTION_KEY)) {
                    ACTION_CENTER -> {
                        pushExpansionRoute(player, worldData)
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
                    pushExpansionRoute(player, worldData)
                    openModifiedWarning(player, worldData, ConfirmAction.STEP_BACK)
                } else {
                    pushExpansionRoute(player, worldData)
                    openConfirmByPlatform(player, worldData, ConfirmAction.STEP_BACK, null)
                }
            }
        }
    }

    private fun handleWarningClick(event: InventoryClickEvent, player: Player, holder: WarningHolder) {
        event.cancelWithDebug("ChanponWorldSettingsMenuProvider.handleWarningClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                pushWarningRoute(player, worldData, holder.action)
                openConfirmByPlatform(player, worldData, holder.action, null)
            }
        }
    }

    private fun handleConfirmClick(event: InventoryClickEvent, player: Player, holder: ConfirmHolder) {
        event.cancelWithDebug("ChanponWorldSettingsMenuProvider.handleConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                executeConfirmAction(player, worldData, holder.action, holder.direction)
            }
        }
    }

    private fun handleCriticalClick(event: InventoryClickEvent, player: Player, worldUuid: UUID) {
        event.cancelWithDebug("ChanponWorldSettingsMenuProvider.handleCriticalClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    open(player, worldData, currentRequest(player))
                }
            }
            ItemTag.TYPE_GUI_SETTING_DELETE_WORLD -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                if (storage.isProductionWorld(worldUuid)) return
                pushCriticalRoute(player, worldData)
                openDeleteConfirm(player, worldData, false)
            }
        }
    }

    private fun handleDeleteConfirmClick(event: InventoryClickEvent, player: Player, holder: DeleteConfirmHolder) {
        event.cancelWithDebug("ChanponWorldSettingsMenuProvider.handleDeleteConfirmClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    openCriticalMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
                if (!holder.finalStep) {
                    pushDeleteConfirmRoute(player, worldData, false)
                    openDeleteConfirm(player, worldData, true)
                    return
                }
                player.closeInventory()
                mwm().worldService.deleteWorld(holder.worldUuid, player).thenAccept { success ->
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
        val holder = ExpansionHolder(worldData.uuid, currentRequest(player))
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "expand_menu.title"))
        holder.inv = inventory
        applyFrame(inventory)

        inventory.setItem(20, expansionOptionItem(player, "center", Material.MAP, ACTION_CENTER))
        inventory.setItem(24, expansionOptionItem(player, "direction_select", Material.COMPASS, ACTION_SELECT_DIRECTION))
        inventory.setItem(40, returnItem(player))
        if (worldData.latestBorderExpansionRecord() != null) {
            inventory.setItem(42, stepBackItem(player, worldData))
        }
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openModifiedWarning(player: Player, worldData: WorldData, action: ConfirmAction) {
        val holder = WarningHolder(worldData.uuid, action, currentRequest(player))
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "modified_warning.title"))
        holder.inv = inventory
        applyFrame(inventory)
        ChanponGuiElements.setConfirmationItems(
            inventory,
            modifiedWarningItem(player),
            warningButton(player, true),
            warningButton(player, false)
        )
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openConfirm(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?) {
        val holder = ConfirmHolder(worldData.uuid, action, direction, currentRequest(player))
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "confirm.${action.key}.title"))
        holder.inv = inventory
        applyFrame(inventory)
        ChanponGuiElements.setConfirmationItems(
            inventory,
            confirmCenterItem(player, worldData, action, direction),
            confirmButton(player, true),
            confirmButton(player, false)
        )
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openCriticalMenu(player: Player, worldData: WorldData) {
        if (criticalUnavailableLoreKey(player, worldData) != null) {
            player.sendMessage(i18nComponent(player, "critical.unavailable_message"))
            return
        }
        val holder = CriticalHolder(worldData.uuid, currentRequest(player))
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "critical.menu_title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(36, returnItem(player))
        inventory.setItem(22, deleteWorldItem(player))
        fill(inventory)
        player.openInventory(inventory)
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
                player.closeInventory()
            }
            success -> openExpansionMenu(player, latest)
            else -> open(player, latest, currentRequest(player))
        }
    }

    private fun openDeleteConfirm(player: Player, worldData: WorldData, finalStep: Boolean) {
        val holder = DeleteConfirmHolder(worldData.uuid, finalStep, currentRequest(player))
        val key = if (finalStep) "critical.final" else "critical.confirm"
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "${key}_title"))
        holder.inv = inventory
        applyFrame(inventory)
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
        ChanponGuiElements.setConfirmationItems(
            inventory,
            centerItem,
            confirmButton(player, true),
            confirmButton(player, false)
        )
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun pushSettingsRoute(player: Player, worldData: WorldData) {
        val request = currentRequest(player)
        mwm().menuRouteHistory.pushCustom(
            player,
            "chanpon.world_settings:${worldData.uuid}:$request"
        ) { target ->
            val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@pushCustom false
            open(target, latest, request)
            true
        }
    }

    private fun pushExpansionRoute(player: Player, worldData: WorldData) {
        mwm().menuRouteHistory.pushCustom(
            player,
            "chanpon.expansion:${worldData.uuid}"
        ) { target ->
            val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@pushCustom false
            openExpansionMenu(target, latest)
            true
        }
    }

    private fun pushWarningRoute(player: Player, worldData: WorldData, action: ConfirmAction) {
        mwm().menuRouteHistory.pushCustom(
            player,
            "chanpon.warning:${worldData.uuid}:${action.key}"
        ) { target ->
            val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@pushCustom false
            openModifiedWarning(target, latest, action)
            true
        }
    }

    private fun pushCriticalRoute(player: Player, worldData: WorldData) {
        mwm().menuRouteHistory.pushCustom(
            player,
            "chanpon.critical:${worldData.uuid}"
        ) { target ->
            val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@pushCustom false
            openCriticalMenu(target, latest)
            true
        }
    }

    private fun pushDeleteConfirmRoute(player: Player, worldData: WorldData, finalStep: Boolean) {
        mwm().menuRouteHistory.pushCustom(
            player,
            "chanpon.delete_confirm:${worldData.uuid}:$finalStep"
        ) { target ->
            val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@pushCustom false
            openDeleteConfirm(target, latest, finalStep)
            true
        }
    }

    private fun renderExtensions(
        inventory: Inventory,
        player: Player,
        worldData: WorldData,
        showBackButton: Boolean,
        isOwner: Boolean,
        isModerator: Boolean,
        isProtected: Boolean = false
    ) {
        val context = MenuExtensionContext(
            "world_settings",
            mutableMapOf(
                "worldData" to worldData,
                "showBackButton" to showBackButton,
                "isOwner" to isOwner,
                "isMember" to worldData.members.contains(player.uniqueId),
                "isModerator" to isModerator,
                "isProtected" to isProtected
            )
        )
        MyWorldManagerApi.getMenuExtensions().forEach { it.onRender(inventory, player, context) }
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
                    val marker = if (selected) "\u00A7a\u00BB" else "\u00A78\u30FB"
                    val color = if (selected) selectedColor else "\u00A77"
                    add(GuiLoreLine.Raw("$marker $color$displayName"))
                }
                add(GuiLoreLine.Spacer)
                add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.left"), i18n(player, "publish.cycle_next_action")))
                add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.right"), i18n(player, "publish.cycle_previous_action")))
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
            describedLore(
                player,
                mwm().languageManager.getMessageList(player, "gui.settings.expand.blocks.description"),
                lines,
                requireTargetWorld = true
            ),
            ItemTag.TYPE_GUI_SETTING_EXPAND
        )
    }

    private fun notificationItem(player: Player, worldData: WorldData): ItemStack {
        val status = i18n(player, if (worldData.notificationEnabled) "common.on" else "common.off")
        return componentItem(
            if (worldData.notificationEnabled) Material.BELL else Material.OAK_DOOR,
            mwm().languageManager.getMessage(player, "gui.settings.notification.display"),
            describedStructuredLore(
                player,
                mwm().languageManager.getMessageList(player, "gui.settings.notification.blocks.description"),
                listOf(
                    GuiLoreLine.Data(i18n(player, "notification.current_label"), status, "§e"),
                    GuiLoreLine.SingleAction(i18n(player, "notification.action"))
                )
            ),
            ItemTag.TYPE_GUI_SETTING_NOTIFICATION
        )
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
                mwm().languageManager.getMessageList(player, "gui.settings.critical.blocks.summary"),
                listOf(GuiLoreLine.SingleAction(i18n(player, "critical.action"))),
                requireTargetWorld = true
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
            GuiLoreBlock(listOf(GuiLoreLine.SingleAction(i18n(player, "critical.delete_action"))))
        )))
        return componentItem(Material.TNT, i18n(player, "critical.delete_display"), lore, ItemTag.TYPE_GUI_SETTING_DELETE_WORLD)
    }

    private fun criticalUnavailableItem(player: Player, loreKey: String): ItemStack {
        return componentItem(
            Material.BARRIER,
            i18n(player, "critical.unavailable_display"),
            describedStructuredLore(
                player,
                mwm().languageManager.getMessageList(player, "gui.settings.critical.blocks.summary"),
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
                add(GuiLoreBlock(listOf(GuiLoreLine.SingleAction(mwm().languageManager.getMessage(player, "gui.player_world.world_item.warp")))))
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
            listOf(GuiLoreLine.SingleAction(i18n(player, "mwm_action.action"))),
            requireTargetWorld = requireTargetWorld
        )
        val icon = mwm().menuConfigManager.getIconMaterial("world_settings", iconKey, material)
        return componentItem(icon, display, lore, type)
    }

    private fun mwmDescription(player: Player, iconKey: String): List<String> {
        val bedrock = mwm().playerPlatformResolver.isBedrock(player)
        val key = when (iconKey) {
            "info" -> if (bedrock) "gui.settings.info.blocks.summary_be" else "gui.settings.info.blocks.summary_je"
            "icon" -> "gui.settings.icon.blocks.description"
            "spawn" -> if (bedrock) "gui.settings.spawn.blocks.description_be" else "gui.settings.spawn.blocks.description_je"
            "announcement" -> if (bedrock) "gui.settings.announcement.blocks.description_be" else "gui.settings.announcement.blocks.description_je"
            "member" -> "gui.settings.member.blocks.description"
            "environment" -> "gui.settings.environment.blocks.summary"
            "tour" -> "gui.tour.worldmenu.blocks.description"
            else -> return emptyList()
        }
        return mwm().languageManager.getMessageList(player, key)
    }

    private fun describedLore(player: Player, description: List<String>, existing: List<String>, requireTargetWorld: Boolean = false): List<Component> {
        return describedStructuredLore(
            player,
            description,
            existing.map { line -> if (line.isBlank()) GuiLoreLine.Spacer else GuiLoreLine.Raw(line) },
            requireTargetWorld
        )
    }

    private fun describedStructuredLore(player: Player, description: List<String>, existing: List<GuiLoreLine>, requireTargetWorld: Boolean = false): List<Component> {
        return CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(
                buildList {
                    if (requireTargetWorld) {
                        add(GuiLoreLine.Warning(i18n(player, "common.must_be_in_world_warning")))
                    }
                    description.filter { it.isNotBlank() }.forEach { add(GuiLoreLine.Raw(it)) }
                    if (description.any { it.isNotBlank() } && existing.any { it != GuiLoreLine.Spacer }) {
                        add(GuiLoreLine.Spacer)
                    }
                    addAll(existing)
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
                GuiLoreBlock(listOf(GuiLoreLine.SingleAction(i18n(player, "expand_menu.$key.action"))))
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
                GuiLoreBlock(listOf(GuiLoreLine.SingleAction(i18n(player, "step_back.action"))))
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
                GuiLoreBlock(listOf(GuiLoreLine.SingleAction(i18n(player, "modified_warning.action"))))
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

    private fun item(material: Material, display: String, loreLines: List<String>, type: String): ItemStack {
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

    private fun applyFrame(inventory: Inventory) {
        ChanponGuiElements.applyStandardFrame(inventory)
    }

    private fun fill(inventory: Inventory) {
        ChanponGuiElements.fill(inventory)
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun currentRequest(player: Player): WorldSettingsMenuRequest {
        when (val holder = player.openInventory.topInventory.holder) {
            is SettingsHolder -> return holder.request
            is ExpansionHolder -> return holder.request
            is WarningHolder -> return holder.request
            is ConfirmHolder -> return holder.request
            is CriticalHolder -> return holder.request
            is DeleteConfirmHolder -> return holder.request
        }
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

    private fun nextVisibility(current: ChanponWorldVisibility, reverse: Boolean): ChanponWorldVisibility {
        return GuiCycle.select(
            current,
            listOf(
            ChanponWorldVisibility.PUBLIC,
            ChanponWorldVisibility.DEVELOPMENT,
            ChanponWorldVisibility.PRIVATE
            ),
            reverse
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

    private class SettingsHolder(val worldUuid: UUID, val request: WorldSettingsMenuRequest) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class ExpansionHolder(val worldUuid: UUID, val request: WorldSettingsMenuRequest) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class WarningHolder(
        val worldUuid: UUID,
        val action: ConfirmAction,
        val request: WorldSettingsMenuRequest
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class ConfirmHolder(
        val worldUuid: UUID,
        val action: ConfirmAction,
        val direction: BlockFace?,
        val request: WorldSettingsMenuRequest
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class CriticalHolder(val worldUuid: UUID, val request: WorldSettingsMenuRequest) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class DeleteConfirmHolder(
        val worldUuid: UUID,
        val finalStep: Boolean,
        val request: WorldSettingsMenuRequest
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
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
        private const val PROTECTED_SETTINGS_SIZE = 45
        private const val PROTECTED_BACK_SLOT = 40
        private const val TYPE_EXPAND_DIRECTION = "chanpon_expand_direction"
        private const val DIRECTION_KEY = "chanpon_direction"
        private const val ACTION_CENTER = "center"
        private const val ACTION_SELECT_DIRECTION = "select_direction"
    }
}
