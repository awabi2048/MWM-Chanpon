package me.awabi2048.mwmchanpon.ui
import me.awabi2048.mwmchanpon.ui.ManagedMenuPresenter

import me.awabi2048.mwmchanpon.util.cancelWithDebug
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import java.util.UUID
import me.awabi2048.mwmchanpon.protection.ProtectionReason
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuProvider
import me.awabi2048.myworldmanager.api.extension.WorldSettingsMenuRequest
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
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
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService
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
        val layout = ChanponGuiElements.settingsLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "submitted_menu.title"))
        holder.inv = inventory
        applyFrame(inventory)

        inventory.setItem(22, createWorldItem(player, worldData, state, reasons))

        ChanponGuiElements.setSettingsFooter(
            inventory,
            if (request.showBackButton) createReturnItem(player) else null,
            null
        )

        fillBody(inventory)
        ManagedMenuPresenter.open(player, inventory)
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
        }
    }

    private fun handleReturn(player: Player, item: ItemStack, request: WorldSettingsMenuRequest) {
        val mwm = mwm()
        val session = mwm.settingsSessionManager.getSession(player)
        if (mwm.menuRouteHistory.openPrevious(player)) {
            mwm.soundManager.playClickSound(player, item, "world_settings")
            return
        }
        when {
            session?.isAdminFlow == true -> {
                mwm.soundManager.playAdminClickSound(player)
                mwm.worldGui.open(player, null, true, false)
            }
            request.isPlayerWorldFlow == true -> {
                mwm.soundManager.playClickSound(player, item, "world_settings")
                mwm.playerWorldGui.open(player, 0, request.parentShowBackButton ?: false, player.uniqueId, player.name)
            }
            else -> {
                mwm.soundManager.playClickSound(player, item, null)
                mwm.settingsSessionManager.endSession(player)
                ManagedMenuPresenter.close(player)
            }
        }
    }

    private fun applyFrame(inventory: Inventory) {
        ChanponGuiElements.applyStandardFrame(inventory)
    }

    private fun fillBody(inventory: Inventory) {
        ChanponGuiElements.fill(inventory)
    }

    private fun createWorldItem(
        player: Player,
        worldData: WorldData,
        state: SubmissionState,
        reasons: Set<ProtectionReason>
    ): ItemStack {
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name("§e${worldData.name}"))
            meta.lore(ChanponLore.build(listOf(
                GuiLoreLine.Data(
                    i18n(player, "submitted_menu.state_label"),
                    i18n(player, "state.${state.i18nKey}"),
                    when (state) {
                        SubmissionState.UNSUBMITTED -> "§6"
                        SubmissionState.SUBMITTED -> "§a"
                        SubmissionState.COMPLETED -> "§b"
                    }
                ),
                GuiLoreLine.Data(i18n(player, "submitted_menu.reason_label"), protectionReasonText(player, reasons), "§f"),
                GuiLoreLine.UserText(worldData.description.ifBlank { "-" })
            )))
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

    private fun createReturnItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "submitted_menu.back.display"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
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

    private class SubmittedWorldMenuHolder(
        val worldUuid: UUID,
        val request: WorldSettingsMenuRequest
    ) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.submitted_world_menu"
    }
}
