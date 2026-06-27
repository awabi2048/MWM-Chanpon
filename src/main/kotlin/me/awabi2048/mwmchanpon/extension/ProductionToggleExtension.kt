package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.util.cancelWithDebug
import com.awabi2048.ccsystem.CCSystem
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.ui.ChanponGuiElements
import me.awabi2048.mwmchanpon.ui.ChanponLore
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import me.awabi2048.myworldmanager.api.extension.CreationConfirmationLayout
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.WorldCreationSession
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class ProductionToggleExtension(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController
) : MenuExtension, Listener {
    private val pendingProduction = ConcurrentHashMap<Pair<UUID, String>, Boolean>()

    override fun getId(): String = EXTENSION_ID

    override fun onPrepare(context: MenuExtensionContext) {
        if (context.menuType != "creation_confirm") return
        // Chanpon は中央のフッター枠を本番ワールド設定に使うため、MWM のスポーン設定を7枠目へ送る。
        // 作成確認の中央行は「ディメンション / 本番用設定 / スポーン位置」の3点を横並びにする。
        context.get<CreationConfirmationLayout>("layout")?.spawnLocationSlot = 41
    }

    override fun onRender(inventory: Inventory, player: Player, context: MenuExtensionContext) {
        when (context.menuType) {
            "creation_confirm" -> {
                val session = context.get<WorldCreationSession>("session") ?: return
                if (ownerHasProductionWorld(player.uniqueId, null)) return
                val enabled = session.extras[EXTRA_KEY] as? Boolean ?: false
                inventory.setItem(plugin.config.getInt("production.menu_slot", 40), createCreationItem(enabled))
            }
            "world_settings" -> {
                val worldData = context.get<WorldData>("worldData") ?: return
                if (plugin.isSubmissionClosed()) {
                    inventory.setItem(WORLD_MENU_SLOT, createDecorationItem(Material.GRAY_STAINED_GLASS_PANE))
                    return
                }
                val item = if (storage.isProductionWorld(worldData.uuid)) {
                    createSubmissionItem(player, worldData.uuid)
                } else {
                    if (ownerHasProductionWorld(worldData.owner, worldData.uuid)) return
                    createProductionItem(player)
                }
                inventory.setItem(WORLD_MENU_SLOT, item)
            }
        }
    }

    override fun onClick(event: InventoryClickEvent, player: Player, context: MenuExtensionContext): Boolean {
        val item = event.currentItem ?: return false
        if (ItemTag.getExtensionId(item) != getId()) return false
        return when (context.menuType) {
            "creation_confirm" -> handleCreationConfirmClick(event, player, context, item)
            "world_settings" -> handleWorldSettingsClick(event, player, context, item)
            else -> false
        }
    }

    @EventHandler
    fun onWorldCreated(event: MwmWorldCreatedEvent) {
        storage.initializeSubmissionState(event.worldUuid)
        val enabled = pendingProduction.remove(event.ownerUuid to event.worldName) ?: return
        if (enabled) {
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

    private fun handleCreationConfirmClick(
        event: InventoryClickEvent,
        player: Player,
        context: MenuExtensionContext,
        item: ItemStack
    ): Boolean {
        if (ItemTag.getString(item, "chanpon_action") != ACTION_TOGGLE_PRODUCTION) return false
        val session = context.get<WorldCreationSession>("session") ?: return false
        val next = !(session.extras[EXTRA_KEY] as? Boolean ?: false)
        if (next && ownerHasProductionWorld(player.uniqueId, null)) {
            player.sendMessage(i18n(player, "message.production_limit"))
            return true
        }
        session.extras[EXTRA_KEY] = next
        val worldName = session.worldName ?: return true
        pendingProduction[player.uniqueId to worldName] = next
        event.inventory.setItem(event.slot, createCreationItem(next))
        return true
    }

    private fun handleWorldSettingsClick(
        event: InventoryClickEvent,
        player: Player,
        context: MenuExtensionContext,
        item: ItemStack
    ): Boolean {
        val worldData = context.get<WorldData>("worldData") ?: return false
        when (ItemTag.getString(item, "chanpon_action")) {
            ACTION_SET_PRODUCTION -> {
                if (ownerHasProductionWorld(worldData.owner, worldData.uuid)) {
                    player.sendMessage(i18n(player, "message.production_limit"))
                    return true
                }
                openConfirmation(player, worldData.uuid, ConfirmationAction.SET_PRODUCTION, currentRequest(player, context))
            }
            ACTION_TOGGLE_SUBMISSION -> {
                // 本番用設定の解除は管理者メニューに集約し、通常の /worldmenu では提出操作だけを扱う。
                if (!event.click.isLeftClick) {
                    return true
                }
                if (worldData.owner != player.uniqueId) {
                    player.sendMessage(i18n(player, "message.owner_only_submit"))
                    return true
                }
                val current = try {
                    storage.getSubmissionState(worldData.uuid)
                } catch (ex: IllegalStateException) {
                    player.sendMessage(i18n(player, "message.state_error"))
                    event.inventory.setItem(event.slot, createSubmissionErrorItem(player))
                    return true
                }
                if (plugin.isSubmissionClosed()) {
                    player.sendMessage(i18n(player, "message.deadline_closed"))
                    event.inventory.setItem(event.slot, createDecorationItem(Material.GRAY_STAINED_GLASS_PANE))
                    return true
                }
                when (current) {
                    SubmissionState.UNSUBMITTED -> {
                        openConfirmation(player, worldData.uuid, ConfirmationAction.SUBMIT, currentRequest(player, context))
                    }
                    SubmissionState.SUBMITTED -> return true
                    SubmissionState.COMPLETED -> return true
                }
            }
            else -> return false
        }
        return true
    }

    @EventHandler
    fun onConfirmationClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? SubmissionConfirmationHolder ?: return
        event.cancelWithDebug("ProductionToggleExtension.onConfirmationClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_CONFIRM -> {
                holder.resolved = true
                player.closeInventory()
                executeConfirmedAction(player, holder.worldUuid, holder.action)
                returnToWorldSettings(player, holder.worldUuid, holder.request)
            }
            ItemTag.TYPE_GUI_CANCEL -> {
                holder.resolved = true
                player.sendMessage(i18n(player, "message.cancelled_confirmation"))
                returnToWorldSettings(player, holder.worldUuid, holder.request)
            }
        }
    }

    private fun openConfirmation(
        player: Player,
        worldUuid: UUID,
        action: ConfirmationAction,
        request: WorldSettingsMenuRequest
    ) {
        val holder = SubmissionConfirmationHolder(worldUuid, action, request)
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(
            holder,
            layout.size,
            i18nComponent(player, "confirm.${action.key}.title")
        )
        holder.inv = inventory

        applyConfirmationFrame(inventory)
        ChanponGuiElements.setConfirmationItems(
            inventory,
            createConfirmationCenterItem(player, action),
            createConfirmationButton(player, true),
            createConfirmationButton(player, false)
        )
        player.openInventory(inventory)
    }

    private fun returnToWorldSettings(player: Player, worldUuid: UUID, request: WorldSettingsMenuRequest) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
        if (worldData == null) {
            player.closeInventory()
            return
        }
        mwm().worldSettingsGui.open(
            player,
            worldData,
            request.showBackButton,
            request.isPlayerWorldFlow,
            request.parentShowBackButton
        )
    }

    private fun currentRequest(player: Player, context: MenuExtensionContext): WorldSettingsMenuRequest {
        val session = mwm().settingsSessionManager.getSession(player)
        return WorldSettingsMenuRequest(
            context.get<Boolean>("showBackButton") ?: session?.showBackButton ?: false,
            context.get<Boolean?>("isPlayerWorldFlow") ?: session?.isPlayerWorldFlow,
            context.get<Boolean?>("parentShowBackButton") ?: session?.parentShowBackButton
        )
    }

    private fun executeConfirmedAction(player: Player, worldUuid: UUID, action: ConfirmationAction) {
        when (action) {
            ConfirmationAction.SET_PRODUCTION -> {
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
            ConfirmationAction.CANCEL_SUBMISSION -> {
                if (plugin.isSubmissionClosed()) {
                    player.sendMessage(i18n(player, "message.deadline_closed"))
                    return
                }
                if (!ensureSubmissionState(player, worldUuid, SubmissionState.SUBMITTED)) return
                storage.setSubmissionState(
                    worldUuid,
                    SubmissionState.UNSUBMITTED,
                    player.uniqueId,
                    player.name,
                    "cancel_submission"
                )
                MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let {
                    protectionService.sync(it)
                    toolPermissionController.syncWorld(it)
                }
                player.sendMessage(i18n(player, "message.cancelled"))
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

    private fun createCreationItem(enabled: Boolean): ItemStack {
        val item = ItemStack(if (enabled) Material.YELLOW_WOOL else Material.GRAY_WOOL)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(null, "creation.display"))
            meta.lore(lore(listOf(
                i18n(null, "creation.lore.description"),
                i18n(null, "creation.lore.delete_guard"),
                i18n(
                    null,
                    "creation.lore.state",
                    mapOf("state" to i18n(null, if (enabled) "common.on" else "common.off"))
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
            meta.lore(lore(i18nList(player, "production.lore")))
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
            meta.displayName(i18nComponent(player, "state.${state.i18nKey}"))
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
        return lore(i18nList(player, key))
    }

    private fun createSubmissionErrorItem(player: Player): ItemStack {
        val item = ItemStack(Material.BARRIER)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "error.display"))
            meta.lore(lore(i18nList(player, "error.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, getId())
        ItemTag.setString(item, "chanpon_action", ACTION_TOGGLE_SUBMISSION)
        return item
    }

    private fun createConfirmationCenterItem(player: Player, action: ConfirmationAction): ItemStack {
        val item = ItemStack(action.material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "confirm.${action.key}.display"))
            meta.lore(lore(i18nList(player, "confirm.${action.key}.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun createConfirmationButton(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        val key = if (confirm) "confirm.button.confirm" else "confirm.button.cancel"
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(null)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun applyConfirmationFrame(inventory: Inventory) {
        ChanponGuiElements.applyConfirmationFrame(inventory)
    }

    private fun createDecorationItem(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun lore(lines: List<String>): List<Component> {
        return ChanponLore.build(lines)
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
        private const val EXTENSION_ID = "chanpon.production_toggle"
        private const val EXTRA_KEY = "chanpon.production"
        private const val WORLD_MENU_SLOT = 51
        private const val CRITICAL_MENU_SLOT = 33
        private const val ACTION_TOGGLE_PRODUCTION = "toggle_production"
        private const val ACTION_SET_PRODUCTION = "set_production"
        private const val ACTION_TOGGLE_SUBMISSION = "toggle_submission"
        private const val ACTION_SUBMIT = "submit"
        private const val ACTION_UNSET_PRODUCTION = "unset_production"
    }

    private enum class ConfirmationAction(val key: String, val material: Material) {
        SET_PRODUCTION("set_production", Material.HEART_OF_THE_SEA),
        UNSET_PRODUCTION("unset_production", Material.REDSTONE),
        SUBMIT("submit", Material.BRICKS),
        CANCEL_SUBMISSION("cancel_submission", Material.FLOW_BANNER_PATTERN)
    }

    private class SubmissionConfirmationHolder(
        val worldUuid: UUID,
        val action: ConfirmationAction,
        val request: WorldSettingsMenuRequest
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        var resolved: Boolean = false

        override fun getInventory(): Inventory = inv
    }
}
