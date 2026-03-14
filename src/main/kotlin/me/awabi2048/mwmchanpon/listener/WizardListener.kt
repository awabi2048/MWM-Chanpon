package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.PlatformUiService
import me.awabi2048.mwmchanpon.service.WizardSessionService
import me.awabi2048.mwmchanpon.ui.ConfirmationHolder
import me.awabi2048.mwmchanpon.ui.MethodSelectionHolder
import me.awabi2048.mwmchanpon.ui.TemplateSelectionHolder
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent

class WizardListener(
    private val plugin: MWMChanponPlugin,
    private val sessionService: WizardSessionService,
    private val uiService: PlatformUiService,
) : Listener {
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.inventory.holder ?: return

        when (holder) {
            is MethodSelectionHolder -> {
                event.isCancelled = true
                uiService.handleMethodSelection(player, event.rawSlot)
            }

            is TemplateSelectionHolder -> {
                event.isCancelled = true
                uiService.handleTemplateSelection(player, event.rawSlot)
            }

            is ConfirmationHolder -> {
                event.isCancelled = true
                uiService.handleConfirmation(player, event.rawSlot)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        sessionService.clear(event.player.uniqueId)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        when (event.inventory.holder) {
            is MethodSelectionHolder, is TemplateSelectionHolder, is ConfirmationHolder -> uiService.handleInventoryClosed(player)
        }
    }
}
