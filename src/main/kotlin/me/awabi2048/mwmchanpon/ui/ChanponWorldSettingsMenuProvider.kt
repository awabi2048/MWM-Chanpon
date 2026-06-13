package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuProvider
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.api.service.ExpansionExecutionMode
import me.awabi2048.myworldmanager.api.service.ExpansionSequenceOptions
import me.awabi2048.myworldmanager.api.service.ExpansionSequencePhase
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.util.GuiHelper
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, worldData: WorldData, request: WorldSettingsMenuRequest): Boolean {
        if (storage.isProductionWorld(worldData.uuid)) {
            if (protectionService.isProtected(worldData)) {
                return false
            }
        }

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
        GuiHelper.INSTANCE.scheduleGuiTransitionReset(mwm, player)

        val holder = SettingsHolder(worldData.uuid)
        val title = GuiHelper.INSTANCE.inventoryTitle(
            mwm.languageManager.getComponent(
                player,
                "gui.settings.title",
                mapOf("world" to worldData.name)
            )
        )
        val inventory = Bukkit.createInventory(holder, 54, title)
        holder.inv = inventory

        applyFrame(inventory)
        if (request.showBackButton) inventory.setItem(45, returnItem(player))

        val session = mwm.settingsSessionManager.getSession(player)
        val isAdminFlow = session?.isAdminFlow == true
        val isActualOwner = worldData.owner == player.uniqueId || isAdminFlow
        val isModerator = worldData.moderators.contains(player.uniqueId)
        val isImportedArchive = storage.isImportedArchive(worldData.uuid)
        val isOwner = isActualOwner && !isImportedArchive
        val hasManagePermission = (isOwner || isModerator) && !isImportedArchive
        val isProduction = storage.isProductionWorld(worldData.uuid)

        if (hasManagePermission) {
            inventory.setItem(19, mwmItem(player, "info", Material.NAME_TAG, ItemTag.TYPE_GUI_SETTING_INFO))
            inventory.setItem(20, mwmItem(player, "icon", Material.ANVIL, ItemTag.TYPE_GUI_SETTING_ICON))
            inventory.setItem(21, mwmItem(player, "spawn", Material.COMPASS, ItemTag.TYPE_GUI_SETTING_SPAWN))
            inventory.setItem(28, mwmItem(player, "announcement", Material.OAK_SIGN, ItemTag.TYPE_GUI_SETTING_ANNOUNCEMENT))
            inventory.setItem(29, notificationItem(player, worldData))
            inventory.setItem(47, mwmItem(player, "tour", Material.PALE_OAK_BOAT, ItemTag.TYPE_GUI_SETTING_TOUR, "gui.tour.worldmenu"))
        }
        if (isOwner) {
            inventory.setItem(23, expansionItem(player, worldData))
            inventory.setItem(24, publishItem(player, worldData))
            inventory.setItem(25, mwmItem(player, "member", Material.PLAYER_HEAD, ItemTag.TYPE_GUI_SETTING_MEMBER))
            inventory.setItem(32, mwmItem(player, "environment", Material.GRASS_BLOCK, ItemTag.TYPE_GUI_SETTING_ENVIRONMENT))
            inventory.setItem(33, criticalItem(player, isProduction))
        }
        inventory.setItem(49, worldInfoItem(player, worldData))

        fill(inventory)
        renderExtensions(inventory, player, worldData, request.showBackButton, isOwner, isModerator)
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
        event.isCancelled = true
        val mwm = mwm()
        mwm.settingsSessionManager.getSession(player)?.let { it.isGuiTransition = false }
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val session = mwm.settingsSessionManager.getSession(player)
        val isOwner = worldData.owner == player.uniqueId || session?.isAdminFlow == true
        val isModerator = worldData.moderators.contains(player.uniqueId)

        val type = ItemTag.getType(item)
        when (type) {
            ItemTag.TYPE_GUI_SETTING_EXPAND -> {
                if (worldData.borderExpansionLevel == WorldData.EXPANSION_LEVEL_SPECIAL) return
                if (worldData.borderExpansionLevel >= maxExpansionLevel()) {
                    player.sendMessage(i18nComponent(player, "message.expand_max"))
                    return
                }
                mwm.soundManager.playClickSound(player, item, "world_settings")
                pushSettingsRoute(player, worldData)
                openExpansionMenu(player, worldData)
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
                mwm.worldSettingsGui.editWorldInfo(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_ICON -> {
                mwm.worldSettingsGui.enterIconSelection(player, worldData)
            }
            ItemTag.TYPE_GUI_SETTING_SPAWN -> {
                val isGuest = if (mwm.playerPlatformResolver.isBedrock(player)) true else event.isLeftClick
                mwm.worldSettingsGui.enterSpawnSetting(player, worldData, isGuest)
            }
            ItemTag.TYPE_GUI_SETTING_PUBLISH -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                val nextVisibility = when (storage.getVisibility(worldUuid)) {
                    ChanponWorldVisibility.PUBLIC -> ChanponWorldVisibility.DEVELOPMENT
                    ChanponWorldVisibility.DEVELOPMENT -> ChanponWorldVisibility.PRIVATE
                    ChanponWorldVisibility.PRIVATE -> ChanponWorldVisibility.PUBLIC
                }
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
                    mwm.worldSettingsGui.editAnnouncement(player, worldData)
                } else {
                    mwm.worldSettingsGui.clearAnnouncements(player, worldData)
                }
                open(player, worldData, currentRequest(player))
            }
            ItemTag.TYPE_GUI_SETTING_TOUR -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                mwm.tourGui.openEditMenu(player, worldData, 0)
            }
            ItemTag.TYPE_GUI_SETTING_VISITOR -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
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
                    player.performCommand(mwm.config.getString("menu_command", "mwm")?.removePrefix("/") ?: "mwm")
                }
            }
            ItemTag.TYPE_GUI_EXTENSION -> {
                val extensionId = ItemTag.getExtensionId(item) ?: return
                val extension = MyWorldManagerApi.getMenuExtensions().firstOrNull { it.getId() == extensionId } ?: return
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
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    open(player, worldData, currentRequest(player))
                }
            }
            TYPE_EXPAND_DIRECTION -> {
                when (ItemTag.getString(item, DIRECTION_KEY)) {
                    ACTION_CENTER -> {
                        pushExpansionRoute(player, worldData)
                        openConfirm(player, worldData, ConfirmAction.EXPAND, null)
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
                if (worldData.latestBorderExpansionRecord()?.modified == true) {
                    pushExpansionRoute(player, worldData)
                    openModifiedWarning(player, worldData, ConfirmAction.STEP_BACK)
                } else {
                    pushExpansionRoute(player, worldData)
                    openConfirm(player, worldData, ConfirmAction.STEP_BACK, null)
                }
            }
        }
    }

    private fun handleWarningClick(event: InventoryClickEvent, player: Player, holder: WarningHolder) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                pushWarningRoute(player, worldData, holder.action)
                openConfirm(player, worldData, holder.action, null)
            }
        }
    }

    private fun handleConfirmClick(event: InventoryClickEvent, player: Player, holder: ConfirmHolder) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    openExpansionMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
                val service = MyWorldManagerApi.getWorldService()
                val success = when (holder.action) {
                    ConfirmAction.EXPAND -> service?.expandWorldBorder(holder.worldUuid, holder.direction) == true
                    ConfirmAction.STEP_BACK -> service?.stepBackWorldBorder(holder.worldUuid) == true
                }
                val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: worldData
                player.sendMessage(i18nComponent(player, if (success) holder.action.successKey else holder.action.failedKey))
                when {
                    success && holder.action == ConfirmAction.EXPAND -> {
                        mwm().settingsSessionManager.endSession(player)
                        player.closeInventory()
                    }
                    success -> openExpansionMenu(player, latest)
                    else -> open(player, latest, currentRequest(player))
                }
            }
        }
    }

    private fun handleCriticalClick(event: InventoryClickEvent, player: Player, worldUuid: UUID) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    open(player, worldData, currentRequest(player))
                }
            }
            ItemTag.TYPE_GUI_SETTING_DELETE_WORLD -> {
                if (storage.isProductionWorld(worldUuid)) return
                pushCriticalRoute(player, worldData)
                openDeleteConfirm(player, worldData, false)
            }
        }
    }

    private fun handleDeleteConfirmClick(event: InventoryClickEvent, player: Player, holder: DeleteConfirmHolder) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    openCriticalMenu(player, worldData)
                }
            }
            ItemTag.TYPE_GUI_CONFIRM -> {
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
        val holder = ExpansionHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "expand_menu.title"))
        holder.inv = inventory
        applyFrame(inventory)

        inventory.setItem(20, expansionOptionItem(player, "center", Material.MAP, ACTION_CENTER))
        inventory.setItem(24, expansionOptionItem(player, "direction_select", Material.COMPASS, ACTION_SELECT_DIRECTION))
        inventory.setItem(36, returnItem(player))
        if (worldData.latestBorderExpansionRecord() != null) {
            inventory.setItem(40, stepBackItem(player, worldData))
        }
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openModifiedWarning(player: Player, worldData: WorldData, action: ConfirmAction) {
        val holder = WarningHolder(worldData.uuid, action)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "modified_warning.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(22, modifiedWarningItem(player))
        inventory.setItem(20, warningButton(player, true))
        inventory.setItem(24, warningButton(player, false))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openConfirm(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?) {
        val holder = ConfirmHolder(worldData.uuid, action, direction)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "confirm.${action.key}.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(22, confirmCenterItem(player, worldData, action, direction))
        inventory.setItem(20, confirmButton(player, true))
        inventory.setItem(24, confirmButton(player, false))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openCriticalMenu(player: Player, worldData: WorldData) {
        val holder = CriticalHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "critical.menu_title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(36, returnItem(player))
        inventory.setItem(22, if (storage.isProductionWorld(worldData.uuid)) criticalUnavailableItem(player) else deleteWorldItem(player))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openDeleteConfirm(player: Player, worldData: WorldData, finalStep: Boolean) {
        val holder = DeleteConfirmHolder(worldData.uuid, finalStep)
        val key = if (finalStep) "critical.final" else "critical.confirm"
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "${key}_title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(22, item(Material.TNT, i18n(player, "${key}_display"), i18nList(player, "${key}_lore", mapOf("world" to worldData.name)), ItemTag.TYPE_GUI_INFO))
        inventory.setItem(20, confirmButton(player, true))
        inventory.setItem(24, confirmButton(player, false))
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
        isModerator: Boolean
    ) {
        val context = MenuExtensionContext(
            "world_settings",
            mutableMapOf(
                "worldData" to worldData,
                "showBackButton" to showBackButton,
                "isOwner" to isOwner,
                "isMember" to worldData.members.contains(player.uniqueId),
                "isModerator" to isModerator
            )
        )
        MyWorldManagerApi.getMenuExtensions().forEach { it.onRender(inventory, player, context) }
    }

    private fun publishItem(player: Player, worldData: WorldData): ItemStack {
        val visibility = storage.getVisibility(worldData.uuid)
        val display = visibilityName(player, visibility)
        val lines = mutableListOf<String>()
        lines.add(i18n(player, "publish.current", mapOf("level" to display)))
        lines.add(i18n(player, "publish.public_desc", mapOf("prefix" to marker(visibility == ChanponWorldVisibility.PUBLIC))))
        lines.add(i18n(player, "publish.development_desc", mapOf("prefix" to marker(visibility == ChanponWorldVisibility.DEVELOPMENT))))
        lines.add(i18n(player, "publish.private_desc", mapOf("prefix" to marker(visibility == ChanponWorldVisibility.PRIVATE))))
        lines.add("")
        lines.add(i18n(player, "publish.cycle"))
        return item(Material.OAK_SIGN, i18n(player, "publish.title", mapOf("level" to display)), lines, ItemTag.TYPE_GUI_SETTING_PUBLISH)
    }

    private fun expansionItem(player: Player, worldData: WorldData): ItemStack {
        val level = worldData.borderExpansionLevel
        val max = maxExpansionLevel()
        val border = currentBorder(worldData)
        val lines = i18nList(
            player,
            if (level >= max) "expand.status_max" else "expand.status_available",
            mapOf(
                "current" to level,
                "max" to max,
                "size" to formatNumber(border.size),
                "x" to formatNumber(border.x),
                "z" to formatNumber(border.z)
            )
        )
        return item(Material.FILLED_MAP, i18n(player, "expand.display"), lines, ItemTag.TYPE_GUI_SETTING_EXPAND)
    }

    private fun notificationItem(player: Player, worldData: WorldData): ItemStack {
        val status = i18n(player, if (worldData.notificationEnabled) "common.on" else "common.off")
        return item(
            if (worldData.notificationEnabled) Material.BELL else Material.OAK_DOOR,
            mwm().languageManager.getMessage(player, "gui.settings.notification.display"),
            i18nList(player, "notification.lore", mapOf("status" to status)),
            ItemTag.TYPE_GUI_SETTING_NOTIFICATION
        )
    }

    private fun criticalItem(player: Player, isProduction: Boolean): ItemStack {
        val lore = if (isProduction) i18nList(player, "critical.production_lore") else i18nList(player, "critical.lore")
        return item(Material.TNT, i18n(player, "critical.display"), lore, ItemTag.TYPE_GUI_SETTING_CRITICAL)
    }

    private fun deleteWorldItem(player: Player): ItemStack {
        return item(Material.TNT, i18n(player, "critical.delete_display"), i18nList(player, "critical.delete_lore"), ItemTag.TYPE_GUI_SETTING_DELETE_WORLD)
    }

    private fun criticalUnavailableItem(player: Player): ItemStack {
        return item(Material.BARRIER, i18n(player, "critical.unavailable_display"), i18nList(player, "critical.unavailable_lore"), ItemTag.TYPE_GUI_INFO)
    }

    private fun worldInfoItem(player: Player, worldData: WorldData): ItemStack {
        return item(
            worldData.icon,
            "§e${worldData.name}",
            i18nList(player, "info.lore", mapOf("description" to worldData.description.ifBlank { "-" })),
            ItemTag.TYPE_GUI_INFO
        )
    }

    private fun mwmItem(
        player: Player,
        iconKey: String,
        material: Material,
        type: String,
        langPrefix: String = "gui.settings.$iconKey"
    ): ItemStack {
        val display = mwm().languageManager.getMessage(player, "$langPrefix.display")
        val lore = i18nList(player, "mwm_action.lore")
        val icon = mwm().menuConfigManager.getIconMaterial("world_settings", iconKey, material)
        return item(icon, display, lore, type)
    }

    private fun expansionOptionItem(player: Player, key: String, material: Material, action: String): ItemStack {
        val item = item(material, i18n(player, "expand_menu.$key.display"), i18nList(player, "expand_menu.$key.lore"), TYPE_EXPAND_DIRECTION)
        ItemTag.setString(item, DIRECTION_KEY, action)
        return item
    }

    private fun stepBackItem(player: Player, worldData: WorldData): ItemStack {
        val key = if (worldData.latestBorderExpansionRecord()?.modified == true) "step_back.lore_modified" else "step_back.lore"
        return item(Material.RECOVERY_COMPASS, i18n(player, "step_back.display"), i18nList(player, key), ItemTag.TYPE_GUI_SETTING_STEP_BACK_EXPANSION)
    }

    private fun modifiedWarningItem(player: Player): ItemStack {
        return item(
            Material.REDSTONE_TORCH,
            i18n(player, "modified_warning.display"),
            i18nList(player, "modified_warning.lore"),
            ItemTag.TYPE_GUI_INFO
        )
    }

    private fun confirmCenterItem(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?): ItemStack {
        val directionName = direction?.let { directionName(player, it) } ?: i18n(player, "direction.center")
        val key = if (action == ConfirmAction.STEP_BACK && worldData.latestBorderExpansionRecord()?.modified == true) {
            "confirm.${action.key}.lore_modified"
        } else {
            "confirm.${action.key}.lore"
        }
        return item(action.material, i18n(player, "confirm.${action.key}.display"), i18nList(player, key, mapOf("direction" to directionName)), ItemTag.TYPE_GUI_INFO)
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
        return item(Material.REDSTONE, i18n(player, "common.back"), i18nList(player, "common.back_lore"), ItemTag.TYPE_GUI_RETURN)
    }

    private fun item(material: Material, display: String, loreLines: List<String>, type: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(display))
            meta.lore(CCSystem.getAPI().buildLore(loreLines, closingSeparator = true))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, type)
        return item
    }

    private fun applyFrame(inventory: Inventory) {
        val black = decoration(Material.BLACK_STAINED_GLASS_PANE)
        for (slot in 0..8) inventory.setItem(slot, black)
        for (slot in inventory.size - 9 until inventory.size) inventory.setItem(slot, black)
    }

    private fun fill(inventory: Inventory) {
        val gray = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0 until inventory.size) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, gray)
        }
    }

    private fun decoration(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
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

    private fun maxExpansionLevel(): Int {
        return mwm().config.getConfigurationSection("expansion.costs")?.getKeys(false)?.size ?: 3
    }

    private fun currentBorder(worldData: WorldData): BorderInfo {
        val world = Bukkit.getWorld(worldData.customWorldName ?: "my_world.${worldData.uuid}")
        val border = world?.worldBorder
        val center = worldData.borderCenterPos ?: border?.center
        val size = border?.size ?: mwm().config.getDouble("expansion.initial_size", 100.0)
        return BorderInfo(center?.x ?: 0.0, center?.z ?: 0.0, size)
    }

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    private fun directionName(player: Player, direction: BlockFace): String {
        return i18n(player, "direction.${direction.name.lowercase()}")
    }

    private fun visibilityName(player: Player?, visibility: ChanponWorldVisibility): String {
        return i18n(player, "visibility.${visibility.storageValue}")
    }

    private fun marker(active: Boolean): String = if (active) "§a▶ " else "§8- "

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

    private data class BorderInfo(val x: Double, val z: Double, val size: Double)

    private class SettingsHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class ExpansionHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class WarningHolder(
        val worldUuid: UUID,
        val action: ConfirmAction
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class ConfirmHolder(
        val worldUuid: UUID,
        val action: ConfirmAction,
        val direction: BlockFace?
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class CriticalHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class DeleteConfirmHolder(
        val worldUuid: UUID,
        val finalStep: Boolean
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
        private const val TYPE_EXPAND_DIRECTION = "chanpon_expand_direction"
        private const val DIRECTION_KEY = "chanpon_direction"
        private const val ACTION_CENTER = "center"
        private const val ACTION_SELECT_DIRECTION = "select_direction"
    }
}
