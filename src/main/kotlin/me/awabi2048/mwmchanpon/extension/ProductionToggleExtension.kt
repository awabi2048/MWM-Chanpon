package me.awabi2048.mwmchanpon.extension
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityItemRenderer
import com.awabi2048.ccsystem.api.gui.MenuCapabilityRouteResolver
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.WorldCreationAvailability
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.ui.ChanponGuiElements
import me.awabi2048.mwmchanpon.ui.ChanponLore
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.WorldCreationSession
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ProductionToggleExtension(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController
) : Listener {
    private val pendingProduction = ConcurrentHashMap<Pair<UUID, String>, Boolean>()
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = CONFIRMATION_ROUTE,
                renderer = { context -> renderConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_CONFIRM to MenuActionHandler(::confirm),
                    ACTION_CANCEL to MenuActionHandler(::cancel),
                ),
            ),
        )
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = RUNTIME_OWNER,
                id = CAPABILITY_ID,
                availability = MenuCapabilityAvailability(::isCapabilityAvailable),
                itemRenderer = MenuCapabilityItemRenderer(::renderCapabilityItem),
                routeResolver = MenuCapabilityRouteResolver(::resolveCapabilityRoute),
            ),
        )
    }

    @EventHandler
    fun onWorldCreated(event: MwmWorldCreatedEvent) {
        storage.initializeSubmissionState(event.worldUuid)
        val enabled = pendingProduction.remove(event.ownerUuid to event.worldName) ?: return
        if (enabled) {
            val owner = Bukkit.getPlayer(event.ownerUuid)
            if (owner == null || !canUseProduction(owner)) return
            if (ownerHasProductionWorld(event.ownerUuid, event.worldUuid)) return
            storage.setProductionWorld(event.worldUuid, true)
            applyForcedTags(event.worldUuid)
        }
    }

    fun syncForcedTagsForProductionWorlds() {
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        repository.findAll()
            .filter { storage.isProductionWorld(it.uuid) }
            .forEach { applyForcedTags(it.uuid) }
    }

    fun toggleCreationSelection(player: Player, session: WorldCreationSession): ItemStack {
        if (!canUseProduction(player)) {
            return createCreationItem(session.extras[EXTRA_KEY] as? Boolean ?: false)
        }
        val next = !(session.extras[EXTRA_KEY] as? Boolean ?: false)
        if (next && ownerHasProductionWorld(player.uniqueId, null)) {
            player.sendMessage(i18n(player, "message.production_limit"))
            return createCreationItem(false)
        }
        session.extras[EXTRA_KEY] = next
        session.worldName?.let { pendingProduction[player.uniqueId to it] = next }
        return createCreationItem(next)
    }

    private fun isCapabilityAvailable(context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext): Boolean {
        val worldData = worldData(context.arguments) ?: return false
        if (globalStorage.getWorldCreationAvailability() != WorldCreationAvailability.PRODUCTION) return false
        if (protectionService.isProtected(worldData)) return false
        if (worldData.owner != context.player.uniqueId &&
            !context.player.hasPermission("myworldmanager.admin")
        ) return false
        return storage.isProductionWorld(worldData.uuid) ||
            !ownerHasProductionWorld(worldData.owner, worldData.uuid)
    }

    private fun renderCapabilityItem(
        context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext,
    ): ItemStack {
        val worldData = requireNotNull(worldData(context.arguments))
        if (plugin.isSubmissionClosed()) {
            return createDecorationItem(Material.GRAY_STAINED_GLASS_PANE)
        }
        return if (storage.isProductionWorld(worldData.uuid)) {
            createSubmissionItem(context.player, worldData.uuid)
        } else {
            createProductionItem(context.player)
        }
    }

    private fun resolveCapabilityRoute(
        context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext,
    ): MenuRoute? {
        val worldData = worldData(context.arguments) ?: return null
        if (plugin.isSubmissionClosed()) return null
        val action = if (storage.isProductionWorld(worldData.uuid)) {
            when (runCatching { storage.getSubmissionState(worldData.uuid) }.getOrNull()) {
                SubmissionState.UNSUBMITTED -> ConfirmationAction.SUBMIT
                SubmissionState.SUBMITTED,
                SubmissionState.COMPLETED,
                null -> return null
            }
        } else {
            ConfirmationAction.SET_PRODUCTION
        }
        return MenuRoute(
            RUNTIME_OWNER,
            CONFIRMATION_ROUTE,
            mapOf(
                WORLD_UUID_ARGUMENT to worldData.uuid.toString(),
                CONFIRMATION_ACTION_ARGUMENT to action.key,
            ),
        )
    }

    private fun renderConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val action = confirmationAction(route)
        val layout = ChanponGuiElements.confirmationLayout()
        val title = i18nComponent(player, "confirm.${action.key}.title")
        val centerItem = ItemStack(Material.PAPER).apply {
            editMeta {
                it.displayName(title)
                it.lore(i18nList(player, "confirm.${action.key}.lore").map(Component::text))
            }
        }
        val confirmItem = ItemStack(Material.LIME_CONCRETE).apply {
            editMeta { it.displayName(i18nComponent(player, "confirm.button.confirm.display")) }
        }
        val cancelItem = ItemStack(Material.RED_CONCRETE).apply {
            editMeta { it.displayName(i18nComponent(player, "confirm.button.cancel.display")) }
        }
        return InventoryMenuView(
            layout.size,
            title,
            listOf(
                MenuElement(layout.previewSlot, centerItem, GuiElementRole.CONTENT),
                MenuElement(layout.confirmSlot, confirmItem, GuiElementRole.CONFIRM, ACTION_CONFIRM),
                MenuElement(layout.cancelSlot, cancelItem, GuiElementRole.CANCEL, ACTION_CANCEL),
            ),
        )
    }

    private fun confirm(context: MenuActionContext): MenuActionResult {
        val worldUuid = worldUuid(context.route) ?: return MenuActionResult.Rejected()
        val action = confirmationAction(context.route)
        executeConfirmedAction(context.player, worldUuid, action)
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun cancel(context: MenuActionContext): MenuActionResult {
        context.player.sendMessage(i18n(context.player, "message.cancelled_confirmation"))
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun worldData(arguments: Map<String, String>): WorldData? =
        arguments[WORLD_UUID_ARGUMENT]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }

    private fun worldUuid(route: MenuRoute): UUID? =
        route.payload[WORLD_UUID_ARGUMENT]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun confirmationAction(route: MenuRoute): ConfirmationAction =
        route.payload[CONFIRMATION_ACTION_ARGUMENT]
            ?.let { key -> ConfirmationAction.entries.firstOrNull { it.key == key } }
            ?: error("確認操作が見つかりません")

    private fun executeConfirmedAction(player: Player, worldUuid: UUID, action: ConfirmationAction) {
        when (action) {
            ConfirmationAction.SET_PRODUCTION -> {
                if (!canUseProduction(player)) return
                val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
                if (worldData != null && ownerHasProductionWorld(worldData.owner, worldUuid)) {
                    player.sendMessage(i18n(player, "message.production_limit"))
                    return
                }
                storage.setProductionWorld(worldUuid, true)
                applyForcedTags(worldUuid)
                player.sendMessage(i18n(player, "message.production_set"))
            }
            ConfirmationAction.UNSET_PRODUCTION -> {
                storage.setProductionWorld(worldUuid, false)
                MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let {
                    protectionService.sync(it)
                    toolPermissionController.syncWorld(it)
                }
                player.sendMessage(i18n(player, "message.production_unset"))
            }
            ConfirmationAction.SUBMIT -> {
                val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
                if (worldData == null || worldData.owner != player.uniqueId) {
                    player.sendMessage(i18n(player, "message.owner_only_submit"))
                    return
                }
                if (!ensureSubmissionState(player, worldUuid, SubmissionState.UNSUBMITTED)) return
                storage.setSubmissionState(
                    worldUuid,
                    SubmissionState.SUBMITTED,
                    player.uniqueId,
                    player.name,
                    "submit"
                )
                storage.setVisibility(worldUuid, ChanponWorldVisibility.PUBLIC)
                MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let {
                    protectionService.sync(it)
                    toolPermissionController.syncWorld(it)
                }
                player.sendMessage(i18n(player, "message.submitted"))
            }
        }
    }

    private fun ensureSubmissionState(player: Player, worldUuid: UUID, expected: SubmissionState): Boolean {
        val current = try {
            storage.getSubmissionState(worldUuid)
        } catch (ex: IllegalStateException) {
            player.sendMessage(i18n(player, "message.state_error"))
            return false
        }
        if (current != expected) {
            player.sendMessage(i18n(player, "message.state_changed"))
            return false
        }
        return true
    }

    private fun applyForcedTags(worldUuid: UUID) {
        val tags = plugin.config.getStringList("production.forced_tags")
            .mapNotNull { raw ->
                MyWorldManagerApi.getWorldTagService()?.normalizeTagId(raw)
                    ?: raw.trim().lowercase().takeIf { it.isNotBlank() }
            }
            .distinct()
        if (tags.isEmpty()) return

        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        val worldData = repository.findByUuid(worldUuid) ?: return
        val missingTags = tags.filterNot { worldData.tags.contains(it) }
        if (missingTags.isEmpty()) return

        worldData.tags.addAll(missingTags)
        repository.save(worldData)
    }

    private fun ownerHasProductionWorld(ownerUuid: UUID, excludingWorldUuid: UUID?): Boolean {
        val repository = MyWorldManagerApi.getWorldRepository() ?: return false
        return repository.findAll()
            .any { it.owner == ownerUuid && it.uuid != excludingWorldUuid && storage.isProductionWorld(it.uuid) }
    }

    private fun canUseProduction(player: Player): Boolean {
        val decision = MyWorldManagerApi.checkWorldCreation(
            me.awabi2048.myworldmanager.api.extension.WorldCreationRequest(
                actor = player,
                player = player,
                operation = me.awabi2048.myworldmanager.api.extension.WorldCreationOperation.PRODUCTION,
                type = null
            )
        )
        if (!decision.allowed) decision.denialMessage?.let(player::sendMessage)
        return decision.allowed
    }

    fun createCreationItem(enabled: Boolean): ItemStack {
        val item = ItemStack(if (enabled) Material.YELLOW_WOOL else Material.GRAY_WOOL)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(null, "creation.display"))
            meta.lore(structuredLore(listOf(
                GuiLoreLine.Text(i18n(null, "creation.lore.description")),
                GuiLoreLine.Warning(i18n(null, "creation.lore.delete_guard")),
                GuiLoreLine.Data(
                    i18n(null, "creation.lore.state"),
                    i18n(null, if (enabled) "common.on" else "common.off"),
                    if (enabled) "§e" else "§7"
                )
            )))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        if (enabled) item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, EXTENSION_ID)
        ItemTag.setString(item, "chanpon_action", ACTION_TOGGLE_PRODUCTION)
        return item
    }

    private fun createProductionItem(player: Player): ItemStack {
        val item = ItemStack(Material.HEART_OF_THE_SEA)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "production.display"))
            meta.lore(structuredLore(listOf(
                GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "production.lore.action")),
                GuiLoreLine.Warning(i18n(player, "production.lore.limit_warning"))
            )))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun createSubmissionItem(player: Player, worldUuid: UUID): ItemStack {
        val state = try {
            storage.getSubmissionState(worldUuid)
        } catch (ex: IllegalStateException) {
            return createSubmissionErrorItem(player)
        }
        val item = ItemStack(
            when (state) {
                SubmissionState.UNSUBMITTED -> Material.BRICKS
                SubmissionState.SUBMITTED -> Material.FLOW_BANNER_PATTERN
                SubmissionState.COMPLETED -> Material.BOOK
            }
        )
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "submission.display"))
            meta.lore(createSubmissionLore(player, state))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun createSubmissionLore(player: Player, state: SubmissionState): List<Component> {
        val key = when (state) {
            SubmissionState.UNSUBMITTED -> "submission.lore.unsubmitted"
            SubmissionState.SUBMITTED -> "submission.lore.submitted"
            SubmissionState.COMPLETED -> "submission.lore.completed"
        }
        val stateLine = GuiLoreLine.Data(
            i18n(player, "progress.label"),
            i18n(player, "state.${state.i18nKey}"),
            when (state) {
                SubmissionState.UNSUBMITTED -> "§6"
                SubmissionState.SUBMITTED -> "§a"
                SubmissionState.COMPLETED -> "§b"
            }
        )
        return when (state) {
            SubmissionState.UNSUBMITTED -> structuredLore(listOf(
                stateLine,
                GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "$key.action")),
                GuiLoreLine.Warning(i18n(player, "$key.warning"))
            ))
            SubmissionState.SUBMITTED,
            SubmissionState.COMPLETED -> structuredLore(listOf(
                stateLine,
                GuiLoreLine.Text(i18n(player, "$key.description")),
                GuiLoreLine.Warning(i18n(player, "$key.warning"))
            ))
        }
    }

    private fun createSubmissionErrorItem(player: Player): ItemStack {
        val item = ItemStack(Material.BARRIER)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "error.display"))
            meta.lore(ChanponLore.build(i18nList(player, "error.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun createDecorationItem(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun structuredLore(lines: List<GuiLoreLine>): List<Component> {
        return CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(lines, GuiLoreFrame.BOTH))
    }

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.submission.$key", placeholders)
    }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    companion object {
        const val EXTENSION_ID = "chanpon.production_toggle"
        const val MENU_CAPABILITY_ID = "mwm-chanpon:world-production-submission"
        const val EXTRA_KEY = "chanpon.production"
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val CAPABILITY_ID = "world-production-submission"
        private const val CONFIRMATION_ROUTE = "world-production-submission-confirmation"
        private const val WORLD_UUID_ARGUMENT = "world_uuid"
        private const val CONFIRMATION_ACTION_ARGUMENT = "action"
        private const val ACTION_CONFIRM = "confirm"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_TOGGLE_PRODUCTION = "toggle_production"
    }

    private enum class ConfirmationAction(val key: String) {
        SET_PRODUCTION("set_production"),
        UNSET_PRODUCTION("unset_production"),
        SUBMIT("submit")
    }

}
