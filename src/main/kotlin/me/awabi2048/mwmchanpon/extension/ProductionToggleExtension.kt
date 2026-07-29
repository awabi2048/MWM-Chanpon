package me.awabi2048.mwmchanpon.extension
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.ManagedMenuInteraction
import com.awabi2048.ccsystem.api.gui.ManagedMenuInteractionOutcome
import com.awabi2048.ccsystem.api.gui.MenuClickType
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
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
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
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ProductionToggleExtension(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController
) : MenuExtension, Listener {
    private val pendingProduction = ConcurrentHashMap<Pair<UUID, String>, Boolean>()

    override fun getId(): String = EXTENSION_ID

    override fun onRender(items: Map<Int, ItemStack>, player: Player, context: MenuExtensionContext): Map<Int, ItemStack> {
        when (context.menuType) {
            "world_settings" -> {
                if (context.get<Boolean>("isProtected") == true) return items
                if (context.get<Boolean>("isActualOwner") != true) return items
                if (globalStorage.getWorldCreationAvailability() != WorldCreationAvailability.PRODUCTION) return items
                val worldData = context.get<WorldData>("worldData") ?: return items
                if (plugin.isSubmissionClosed()) {
                    return items + (WORLD_MENU_SLOT to createDecorationItem(Material.GRAY_STAINED_GLASS_PANE))
                }
                val item = if (storage.isProductionWorld(worldData.uuid)) {
                    createSubmissionItem(player, worldData.uuid)
                } else {
                    if (ownerHasProductionWorld(worldData.owner, worldData.uuid)) return items
                    createProductionItem(player)
                }
                return items + (WORLD_MENU_SLOT to item)
            }
        }
        return items
    }

    override fun onClick(click: ClickType, item: ItemStack, player: Player, context: MenuExtensionContext): Boolean {
        if (ItemTag.getExtensionId(item) != getId()) return false
        return when (context.menuType) {
            "world_settings" -> handleWorldSettingsClick(click, player, context, item)
            else -> false
        }
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

    private fun handleWorldSettingsClick(
        click: ClickType,
        player: Player,
        context: MenuExtensionContext,
        item: ItemStack
    ): Boolean {
        val worldData = context.get<WorldData>("worldData") ?: return false
        when (ItemTag.getString(item, "chanpon_action")) {
            ACTION_SET_PRODUCTION -> {
                if (!canUseProduction(player)) return true
                if (ownerHasProductionWorld(worldData.owner, worldData.uuid)) {
                    feedback(player, ManagedMenuInteractionOutcome.REJECTED)
                    player.sendMessage(i18n(player, "message.production_limit"))
                    return true
                }
                feedback(player, ManagedMenuInteractionOutcome.SUCCESS, MenuClickType.NAVIGATION)
                openConfirmation(player, worldData.uuid, ConfirmationAction.SET_PRODUCTION)
            }
            ACTION_TOGGLE_SUBMISSION -> {
                // 本番用設定の解除は管理者メニューに集約し、通常の /worldmenu では提出操作だけを扱う。
                if (!click.isLeftClick) {
                    return false
                }
                if (worldData.owner != player.uniqueId) {
                    feedback(player, ManagedMenuInteractionOutcome.REJECTED)
                    player.sendMessage(i18n(player, "message.owner_only_submit"))
                    return true
                }
                val current = try {
                    storage.getSubmissionState(worldData.uuid)
                } catch (ex: IllegalStateException) {
                    feedback(player, ManagedMenuInteractionOutcome.REJECTED)
                    player.sendMessage(i18n(player, "message.state_error"))
                    return true
                }
                if (plugin.isSubmissionClosed()) {
                    feedback(player, ManagedMenuInteractionOutcome.REJECTED)
                    player.sendMessage(i18n(player, "message.deadline_closed"))
                    return true
                }
                when (current) {
                    SubmissionState.UNSUBMITTED -> {
                        feedback(player, ManagedMenuInteractionOutcome.SUCCESS, MenuClickType.NAVIGATION)
                        openConfirmation(player, worldData.uuid, ConfirmationAction.SUBMIT)
                    }
                    SubmissionState.SUBMITTED -> return false
                    SubmissionState.COMPLETED -> return false
                }
            }
            else -> return false
        }
        return true
    }

    private fun openConfirmation(
        player: Player,
        worldUuid: UUID,
        action: ConfirmationAction,
    ) {
        mwm().confirmationMenuGui.openSimple(
            player = player,
            menuId = "production-${action.key}-confirmation",
            title = i18nComponent(player, "confirm.${action.key}.title"),
            bodyLines = i18nList(player, "confirm.${action.key}.lore").map(Component::text),
            confirmLabel = i18nComponent(player, "confirm.button.confirm.display"),
            cancelLabel = i18nComponent(player, "confirm.button.cancel.display"),
            onConfirm = { executeConfirmedAction(player, worldUuid, action) },
            onCancel = { player.sendMessage(i18n(player, "message.cancelled_confirmation")) },
            returnOnConfirm = true,
        )
    }

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
        ItemTag.setExtensionId(item, getId())
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
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, getId())
        ItemTag.setString(item, "chanpon_action", ACTION_SET_PRODUCTION)
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
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, getId())
        ItemTag.setString(item, "chanpon_action", ACTION_TOGGLE_SUBMISSION)
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
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, getId())
        ItemTag.setString(item, "chanpon_action", ACTION_TOGGLE_SUBMISSION)
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
        const val EXTRA_KEY = "chanpon.production"
        private const val WORLD_MENU_SLOT = 51
        private const val CRITICAL_MENU_SLOT = 33
        private const val ACTION_TOGGLE_PRODUCTION = "toggle_production"
        private const val ACTION_SET_PRODUCTION = "set_production"
        private const val ACTION_TOGGLE_SUBMISSION = "toggle_submission"
        private const val ACTION_SUBMIT = "submit"
        private const val ACTION_UNSET_PRODUCTION = "unset_production"
    }

    private enum class ConfirmationAction(val key: String) {
        SET_PRODUCTION("set_production"),
        UNSET_PRODUCTION("unset_production"),
        SUBMIT("submit")
    }

    private fun feedback(
        player: Player,
        outcome: ManagedMenuInteractionOutcome,
        clickType: MenuClickType = MenuClickType.DEFAULT,
    ) {
        CCSystem.getAPI().getMenuRuntimeService().feedback(
            player,
            ManagedMenuInteraction(outcome, clickType),
        )
    }

}
