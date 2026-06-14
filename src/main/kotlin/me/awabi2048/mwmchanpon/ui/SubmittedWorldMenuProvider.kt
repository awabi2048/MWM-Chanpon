package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.util.cancelWithDebug
import com.awabi2048.ccsystem.CCSystem
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.ProtectionReason
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuProvider
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

class SubmittedWorldMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController
) : WorldSettingsMenuProvider, Listener {
    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, worldData: WorldData, request: WorldSettingsMenuRequest): Boolean {
        if (!storage.isProductionWorld(worldData.uuid)) return false
        val reasons = protectionService.protectionReasons(worldData)
        if (reasons.isEmpty()) return false
        val state = try {
            storage.getSubmissionState(worldData.uuid)
        } catch (ex: IllegalStateException) {
            SubmissionState.UNSUBMITTED
        }

        val holder = SubmittedWorldMenuHolder(worldData.uuid, request)
        val inventory = Bukkit.createInventory(holder, 54, i18nComponent(player, "submitted_menu.title"))
        holder.inv = inventory
        applyFrame(inventory)

        if (request.showBackButton) {
            inventory.setItem(45, createReturnItem(player))
        }
        inventory.setItem(22, createWorldItem(player, worldData, state, reasons))

        if (ProtectionReason.SUBMITTED in reasons && state == SubmissionState.SUBMITTED && !plugin.isSubmissionClosed()) {
            inventory.setItem(49, createCancelSubmissionItem(player, worldData.uuid))
        }

        fillBody(inventory)
        player.openInventory(inventory)
        return true
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? SubmittedWorldMenuHolder ?: return
        event.cancelWithDebug("SubmittedWorldMenuProvider.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> handleReturn(player, item, holder.request)
            ItemTag.TYPE_GUI_EXTENSION -> {
                if (ItemTag.getExtensionId(item) != PROVIDER_ID) return
                if (ItemTag.getString(item, ACTION_KEY) != ACTION_CANCEL_SUBMISSION) return
                openCancelConfirmation(player, holder.worldUuid, holder.request)
            }
        }
    }

    private fun handleReturn(player: Player, item: ItemStack, request: WorldSettingsMenuRequest) {
        val mwm = mwm()
        val session = mwm.settingsSessionManager.getSession(player)
        when {
            session?.isAdminFlow == true -> {
                mwm.soundManager.playAdminClickSound(player)
                mwm.worldGui.open(player, null, true, false)
            }
            request.isPlayerWorldFlow == true -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                mwm.playerWorldGui.open(player, 0, request.parentShowBackButton ?: false)
            }
            else -> {
                mwm.soundManager.playClickSound(player, item, null)
                val command = mwm.config.getString("menu_command", "mwm")?.removePrefix("/") ?: "mwm"
                mwm.settingsSessionManager.endSession(player)
                player.closeInventory()
                player.performCommand(command)
            }
        }
    }

    @EventHandler
    fun onConfirmationClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? CancelSubmissionConfirmationHolder ?: return
        event.cancelWithDebug("SubmittedWorldMenuProvider.onConfirmationClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return

        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CONFIRM -> {
                player.closeInventory()
                cancelSubmission(player, holder.worldUuid, holder.request)
            }
            ItemTag.TYPE_GUI_CANCEL -> reopenProtectedMenu(player, holder.worldUuid, holder.request)
        }
    }

    private fun cancelSubmission(player: Player, worldUuid: UUID, request: WorldSettingsMenuRequest) {
        if (plugin.isSubmissionClosed()) {
            player.sendMessage(i18nComponent(player, "message.deadline_closed"))
            return
        }
        val state = try {
            storage.getSubmissionState(worldUuid)
        } catch (ex: IllegalStateException) {
            player.sendMessage(i18nComponent(player, "message.state_error"))
            return
        }
        if (state != SubmissionState.SUBMITTED) {
            player.sendMessage(i18nComponent(player, "message.state_changed"))
            return
        }

        storage.setSubmissionState(
            worldUuid,
            SubmissionState.UNSUBMITTED,
            player.uniqueId,
            player.name,
            "cancel_submission"
        )
        MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let { worldData ->
            protectionService.sync(worldData)
            toolPermissionController.syncWorld(worldData)
            if (protectionService.isProtected(worldData)) {
                open(player, worldData, request)
            }
        }
        player.sendMessage(i18nComponent(player, "message.cancelled"))
    }

    private fun openCancelConfirmation(player: Player, worldUuid: UUID, request: WorldSettingsMenuRequest) {
        val holder = CancelSubmissionConfirmationHolder(worldUuid, request)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "confirm.cancel_submission.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(22, createConfirmationCenterItem(player))
        inventory.setItem(20, createConfirmButton(player, true))
        inventory.setItem(24, createConfirmButton(player, false))
        player.openInventory(inventory)
    }

    private fun applyFrame(inventory: Inventory) {
        val blackPane = createDecorationItem(Material.BLACK_STAINED_GLASS_PANE)
        for (slot in 0..8) inventory.setItem(slot, blackPane)
        for (slot in inventory.size - 9 until inventory.size) inventory.setItem(slot, blackPane)
    }

    private fun fillBody(inventory: Inventory) {
        val grayPane = createDecorationItem(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 9 until inventory.size - 9) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, grayPane)
        }
    }

    private fun createWorldItem(
        player: Player,
        worldData: WorldData,
        state: SubmissionState,
        reasons: Set<ProtectionReason>
    ): ItemStack {
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§e${worldData.name}"))
            meta.lore(
                lore(
                    i18nList(
                        player,
                        "submitted_menu.world_lore",
                        mapOf(
                            "state" to i18n(player, "state.${state.i18nKey}"),
                            "reason" to protectionReasonText(player, reasons),
                            "description" to worldData.description.ifBlank { "-" }
                        )
                    )
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun protectionReasonText(player: Player, reasons: Set<ProtectionReason>): String {
        return reasons.joinToString("§7, ") { reason ->
            when (reason) {
                ProtectionReason.SUBMITTED -> i18n(player, "protection_reason.submitted")
                ProtectionReason.COMPLETED -> i18n(player, "protection_reason.completed")
                ProtectionReason.AUTO_ROLLBACK -> i18n(player, "protection_reason.auto_rollback")
            }
        }
    }

    private fun createCancelSubmissionItem(player: Player, worldUuid: UUID): ItemStack {
        val item = ItemStack(Material.FLOW_BANNER_PATTERN)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "submitted_menu.cancel.display"))
            meta.lore(lore(i18nList(player, "submitted_menu.cancel.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, PROVIDER_ID)
        ItemTag.setWorldUuid(item, worldUuid)
        ItemTag.setString(item, ACTION_KEY, ACTION_CANCEL_SUBMISSION)
        return item
    }

    private fun createReturnItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "submitted_menu.back.display"))
            meta.lore(lore(i18nList(player, "submitted_menu.back.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun createConfirmationCenterItem(player: Player): ItemStack {
        val item = ItemStack(Material.FLOW_BANNER_PATTERN)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "confirm.cancel_submission.display"))
            meta.lore(lore(i18nList(player, "confirm.cancel_submission.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun createConfirmButton(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        val key = if (confirm) "confirm.button.confirm" else "confirm.button.cancel"
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(lore(i18nList(player, "$key.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun createDecorationItem(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun lore(lines: List<String>): List<Component> {
        return CCSystem.getAPI().buildLore(lines, closingSeparator = true)
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

    private fun reopenProtectedMenu(player: Player, worldUuid: UUID, request: WorldSettingsMenuRequest) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
        if (worldData == null || !open(player, worldData, request)) {
            player.closeInventory()
        }
    }

    private class SubmittedWorldMenuHolder(
        val worldUuid: UUID,
        val request: WorldSettingsMenuRequest
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class CancelSubmissionConfirmationHolder(
        val worldUuid: UUID,
        val request: WorldSettingsMenuRequest
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.submitted_world_menu"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_CANCEL_SUBMISSION = "cancel_submission"
    }
}
