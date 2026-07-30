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
import com.awabi2048.ccsystem.api.gui.MenuInteraction
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ExpansionExecutionMode
import me.awabi2048.myworldmanager.api.service.ExpansionSequenceOptions
import me.awabi2048.myworldmanager.api.service.ExpansionSequencePhase
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.GuiItemFactory
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class ChanponWorldSettingsMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val environmentGui: ChanponEnvironmentGui
) {
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

    private fun render(player: Player, source: Source): InventoryMenuView {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return InventoryMenuView(9, Component.text("World settings"), emptyList())
        return when (source.screen) {
            Screen.EXPANSION -> renderExpansion(player, worldData)
            Screen.MODIFIED_WARNING -> renderModifiedWarning(player)
            Screen.CONFIRM -> renderConfirmation(player, worldData, source)
            Screen.CRITICAL -> renderCritical(player, worldData)
            Screen.DELETE_CONFIRM -> renderDeleteConfirmation(player, worldData, source.finalStep)
        }
    }


    private fun handleAction(context: MenuActionContext): MenuActionResult {
        val source = source(context.route)
        return when (source.screen) {
            Screen.EXPANSION -> handleExpansionClick(context.player, source, context.item)
            Screen.MODIFIED_WARNING -> handleWarningClick(context.player, source, context.item)
            Screen.CONFIRM -> handleConfirmClick(context.player, source, context.item)
            Screen.CRITICAL -> handleCriticalClick(context.player, source, context.item)
            Screen.DELETE_CONFIRM -> handleDeleteConfirmClick(context.player, source, context.item)
        }
    }


    private fun handleExpansionClick(player: Player, source: Source, item: ItemStack): MenuActionResult {
        val worldUuid = source.worldUuid
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: return MenuActionResult.Rejected()
        return when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> MenuActionResult.Success(MenuUpdate.Back)
            TYPE_EXPAND_DIRECTION -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                when (ItemTag.getString(item, DIRECTION_KEY)) {
                    ACTION_CENTER -> confirmResultByPlatform(player, worldData, ConfirmAction.EXPAND, null)
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
                            MenuActionResult.Rejected()
                        } else {
                            MenuActionResult.Success(MenuUpdate.None)
                        }
                    }
                    else -> MenuActionResult.Ignored
                }
            }
            ItemTag.TYPE_GUI_SETTING_STEP_BACK_EXPANSION -> {
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
            else -> MenuActionResult.Ignored
        }
    }

    private fun handleWarningClick(player: Player, source: Source, item: ItemStack): MenuActionResult {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return MenuActionResult.Rejected()
        return when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_CANCEL -> MenuActionResult.Success(MenuUpdate.Back)
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                val action = source.confirmAction ?: return MenuActionResult.Rejected()
                confirmResultByPlatform(player, worldData, action, null)
            }
            else -> MenuActionResult.Ignored
        }
    }

    private fun handleConfirmClick(player: Player, source: Source, item: ItemStack): MenuActionResult {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return MenuActionResult.Rejected()
        return when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_CANCEL -> MenuActionResult.Success(MenuUpdate.Back)
            ItemTag.TYPE_GUI_CONFIRM -> {
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
            else -> MenuActionResult.Ignored
        }
    }

    private fun handleCriticalClick(player: Player, source: Source, item: ItemStack): MenuActionResult {
        val worldUuid = source.worldUuid
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: return MenuActionResult.Rejected()
        return when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> MenuActionResult.Success(MenuUpdate.Back)
            ItemTag.TYPE_GUI_SETTING_DELETE_WORLD -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                if (storage.isProductionWorld(worldUuid)) return MenuActionResult.Rejected()
                MenuActionResult.Success(
                    MenuUpdate.Navigate(
                        route(Source(Screen.DELETE_CONFIRM, worldData.uuid, finalStep = false))
                    )
                )
            }
            else -> MenuActionResult.Ignored
        }
    }

    private fun handleDeleteConfirmClick(player: Player, source: Source, item: ItemStack): MenuActionResult {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(source.worldUuid)
            ?: return MenuActionResult.Rejected()
        return when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_CANCEL -> MenuActionResult.Success(MenuUpdate.Back)
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) {
                    return MenuActionResult.Rejected()
                }
                if (!source.finalStep) {
                    return MenuActionResult.Success(
                        MenuUpdate.Navigate(
                            route(Source(Screen.DELETE_CONFIRM, worldData.uuid, finalStep = true))
                        )
                    )
                }
                val worldService =
                    MyWorldManagerApi.getWorldService() ?: return MenuActionResult.Rejected()
                worldService.deleteWorld(source.worldUuid).thenAccept { success ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.sendMessage(i18nComponent(player, if (success) "critical.deleted" else "critical.delete_failed"))
                        if (success) {
                            MyWorldManagerApi.closeWorldSettingsContext(player)
                        }
                    })
                }
                MenuActionResult.Success(MenuUpdate.Close)
            }
            else -> MenuActionResult.Ignored
        }
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

    private fun openConfirmByPlatform(player: Player, worldData: WorldData, action: ConfirmAction, direction: BlockFace?) {
        if (
            MyWorldManagerApi.getBedrockFormService()?.isBedrock(player) == true &&
            openBedrockConfirmForm(player, worldData, action, direction)
        ) {
            return
        }
        runtime.navigate(
            player,
            route(
                Source(
                    Screen.CONFIRM,
                    worldData.uuid,
                    confirmAction = action,
                    direction = direction,
                ),
            ),
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
        runtime.suspendForExternal(player)
        val forms = MyWorldManagerApi.getBedrockFormService() ?: run {
            runtime.resumeFromExternal(player)
            return false
        }
        val opened = forms.sendSimpleForm(
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
                        applyExternalResult(executeConfirmAction(player, latest, action, direction, external = true), player)
                    } else {
                        runtime.resumeFromExternal(player)
                    }
                })
            },
            onClosed = {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) == null) return@Runnable
                    runtime.resumeFromExternal(player)
                })
            }
        )
        if (!opened) runtime.resumeFromExternal(player)
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

    private fun applyExternalResult(result: MenuActionResult, player: Player) {
        when (result) {
            MenuActionResult.Ignored -> Unit
            is MenuActionResult.Rejected -> result.message?.let(player::sendMessage)
            is MenuActionResult.Success -> when (val update = result.update) {
                MenuUpdate.None -> runtime.completeExternal(player)
                MenuUpdate.Close -> runtime.close(player)
                MenuUpdate.Refresh,
                MenuUpdate.Resume -> runtime.resumeFromExternal(player)
                MenuUpdate.Back -> runtime.back(player)
                is MenuUpdate.Navigate -> runtime.navigate(player, update.route)
                is MenuUpdate.Replace -> runtime.replace(player, update.route)
            }
        }
    }

    private fun renderExpansion(player: Player, worldData: WorldData): InventoryMenuView {
        val items = mutableMapOf(
            20 to expansionOptionItem(player, "center", Material.MAP, ACTION_CENTER),
            24 to expansionOptionItem(player, "direction_select", Material.COMPASS, ACTION_SELECT_DIRECTION),
            ChanponGuiElements.backSlot(45) to returnItem(player),
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
            menuElements(
                mapOf(
                    ChanponGuiElements.backSlot(45) to returnItem(player),
                    22 to deleteWorldItem(player),
                ),
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
                add(cycleAction(player))
            }, GuiLoreFrame.BOTH)
        )
        return componentItem(Material.OAK_SIGN, i18n(player, "publish.title"), lore, ItemTag.TYPE_GUI_SETTING_PUBLISH)
    }

    private fun expansionItem(player: Player, worldData: WorldData): ItemStack {
        val level = worldData.borderExpansionLevel
        val max = maxExpansionLevel()
        val lines = if (level >= max) {
            i18nList(player, "expand.status_max").map(GuiLoreLine::Text)
        } else {
            listOf(
                singleClick(player, text(player, "gui.settings.expand.action.open_menu")),
            )
        }
        return componentItem(
            Material.FILLED_MAP,
            i18n(player, "expand.display"),
            describedStructuredLore(
                player,
                textList(player, "gui.settings.expand.blocks.description").map(GuiLoreLine::Text),
                lines,
                requireTargetWorld = true,
                worldData = worldData
            ),
            ItemTag.TYPE_GUI_SETTING_EXPAND
        )
    }

    private fun notificationItem(player: Player, worldData: WorldData): ItemStack {
        val status = i18n(player, if (worldData.notificationEnabled) "common.on" else "common.off")
        val singleAction = singleClick(player, i18n(player, "notification.action"))
        val item = CCSystem.getAPI().getGuiElementService().menuIcon(
            GuiMenuIconSpec(
                Material.BELL,
                GuiNameSpec.Text(
                    text(player, "gui.settings.notification.display"),
                    GuiNameStyle.DEFAULT
                ),
                GuiElementRole.CONTENT,
                1,
                textList(player, "gui.settings.notification.blocks.description"),
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
                textList(player, "gui.settings.critical.blocks.summary").map(GuiLoreLine::Text),
                listOf(singleClick(player, i18n(player, "critical.action"))),
                requireTargetWorld = true,
                worldData = worldData
            ),
            ItemTag.TYPE_GUI_SETTING_CRITICAL
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
        return componentItem(Material.TNT, i18n(player, "critical.delete_display"), lore, ItemTag.TYPE_GUI_SETTING_DELETE_WORLD)
    }

    private fun criticalUnavailableItem(player: Player, loreKey: String): ItemStack {
        return componentItem(
            Material.BARRIER,
            i18n(player, "critical.unavailable_display"),
            describedStructuredLore(
                player,
                textList(player, "gui.settings.critical.blocks.summary").map(GuiLoreLine::Text),
                listOf(GuiLoreLine.Warning(i18n(player, loreKey)))
            ),
            ItemTag.TYPE_GUI_INFO
        )
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
        val display = text(player, "$langPrefix.display")
        val lore = describedStructuredLore(
            player,
            mwmDescription(player, iconKey),
            listOf(singleClick(player, i18n(player, "mwm_action.action"))),
            requireTargetWorld = requireTargetWorld,
            worldData = worldData
        )
        val icon = MyWorldManagerApi.getConfiguredMenuIcon("world_settings", iconKey, material)
        return componentItem(icon, display, lore, type)
    }

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

    private fun expansionOptionItem(player: Player, key: String, material: Material, action: String): ItemStack {
        val item = componentItem(
            material,
            i18n(player, "expand_menu.$key.display"),
            loreBlocks(listOf(
                GuiLoreBlock(listOf(GuiLoreLine.Text(i18n(player, "expand_menu.$key.description")))),
                GuiLoreBlock(listOf(singleClick(player, i18n(player, "expand_menu.$key.action"))))
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
                GuiLoreBlock(listOf(singleClick(player, i18n(player, "step_back.action"))))
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
                GuiLoreBlock(listOf(singleClick(player, i18n(player, "modified_warning.action"))))
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

    private fun menuElements(
        items: Map<Int, ItemStack>,
        displayOnlyInfoWorldUuid: UUID? = null,
    ): List<MenuElement> =
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
                type == ItemTag.TYPE_GUI_INFO &&
                    ItemTag.getWorldUuid(item) != null &&
                    ItemTag.getWorldUuid(item) != displayOnlyInfoWorldUuid
            val effectiveRole = if (clickableInfo) GuiElementRole.ACTION else role
            val interaction = when {
                effectiveRole == GuiElementRole.BACK -> MenuInteraction.Back()
                role == GuiElementRole.DECORATION ||
                    (role == GuiElementRole.CONTENT && !clickableInfo) ->
                    MenuInteraction.DisplayOnly
                else -> MenuInteraction.Action(ACTION_SELECT)
            }
            MenuElement(
                slot,
                item,
                effectiveRole,
                interaction = interaction,
            )
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
    ): ItemStack = when (feature) {
        WorldSettingsFeature.EXPANSION -> expansionItem(player, worldData)
        WorldSettingsFeature.PUBLISH -> publishItem(player, worldData)
        WorldSettingsFeature.NOTIFICATION -> notificationItem(player, worldData)
        WorldSettingsFeature.ENVIRONMENT -> mwmItem(
            player,
            worldData,
            "environment",
            Material.GRASS_BLOCK,
            ItemTag.TYPE_GUI_SETTING_ENVIRONMENT,
            requireTargetWorld = true,
        )
        WorldSettingsFeature.CRITICAL -> criticalItem(player, worldData)
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
                    openConfirmByPlatform(player, worldData, ConfirmAction.EXPAND, null)
                    MenuActionResult.Success(MenuUpdate.None)
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
                environmentGui.open(player, worldData)
                MenuActionResult.Success(MenuUpdate.None)
            }
            WorldSettingsFeature.CRITICAL -> openCriticalMenu(player, worldData)
        }
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
        val confirmAction: ConfirmAction? = null,
        val direction: BlockFace? = null,
        val finalStep: Boolean = false,
    )

    private enum class Screen {
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
        private const val PAYLOAD_SCREEN = "screen"
        private const val PAYLOAD_WORLD_UUID = "world_uuid"
        private const val PAYLOAD_CONFIRM_ACTION = "confirm_action"
        private const val PAYLOAD_DIRECTION = "direction"
        private const val PAYLOAD_FINAL_STEP = "final_step"
        private const val TYPE_EXPAND_DIRECTION = "chanpon_expand_direction"
        private const val DIRECTION_KEY = "chanpon_direction"
        private const val ACTION_CENTER = "center"
        private const val ACTION_SELECT_DIRECTION = "select_direction"
    }
}
