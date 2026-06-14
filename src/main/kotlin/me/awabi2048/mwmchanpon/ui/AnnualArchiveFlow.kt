package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.util.cancelWithDebug
import com.awabi2048.ccsystem.CCSystem
import io.papermc.paper.event.player.AsyncChatEvent
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.ArchiveService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AnnualArchiveFlow(
    private val plugin: MWMChanponPlugin,
    private val archiveService: ArchiveService
) : Listener {
    private val sessions = ConcurrentHashMap<UUID, ArchiveSession>()

    fun start(player: Player) {
        if (sessions.containsKey(player.uniqueId)) {
            player.sendMessage(i18n(player, "already_in_progress"))
            return
        }

        val shouldRegister = sessions.isEmpty()
        sessions[player.uniqueId] = ArchiveSession(ArchiveStep.NAME_INPUT)
        if (shouldRegister) {
            plugin.server.pluginManager.registerEvents(this, plugin)
        }

        player.sendMessage(Component.empty())
        player.sendMessage(i18nComponent(player, "prompt.name_input"))
        player.sendMessage(
            Component.text(i18n(player, "prompt.cancel_hint"), NamedTextColor.GRAY)
                .clickEvent(ClickEvent.callback { cancel(player) })
                .hoverEvent(HoverEvent.showText(Component.text(i18n(player, "prompt.cancel_hover"))))
        )
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val session = sessions[player.uniqueId] ?: return
        event.isCancelled = true

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
            when (session.step) {
                ArchiveStep.NAME_INPUT -> {
                    if (message.isBlank()) {
                        player.sendMessage(i18nComponent(player, "error.empty_name"))
                        return@Runnable
                    }
                    session.archiveName = message
                    session.step = ArchiveStep.CONFIRM_NAME
                    player.openInventory(
                        confirmationInventory(
                            player,
                            ArchiveConfirmationAction.NAME,
                            "confirm.name",
                            mapOf("name" to message),
                            "confirm.proceed"
                        )
                    )
                }
                ArchiveStep.CONFIRM_NAME -> {
                    player.sendMessage(i18nComponent(player, "error.use_button"))
                }
                ArchiveStep.PASSWORD_INPUT -> {
                    val expectedPassword = plugin.config.getString("archive.password", "たぬきスープ")
                    if (message != expectedPassword) {
                        player.sendMessage(i18nComponent(player, "error.wrong_password"))
                        cancel(player)
                        return@Runnable
                    }
                    session.step = ArchiveStep.CONFIRM_FINAL
                    player.openInventory(
                        confirmationInventory(
                            player,
                            ArchiveConfirmationAction.EXECUTE,
                            "confirm.final",
                            mapOf("name" to session.archiveName),
                            "confirm.execute"
                        )
                    )
                }
                ArchiveStep.CONFIRM_FINAL -> {
                    player.sendMessage(i18nComponent(player, "error.use_button"))
                }
            }
        })
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? ArchiveConfirmationHolder ?: return
        event.cancelWithDebug("AnnualArchiveFlow.onInventoryClick: confirm_click")

        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.playerId || event.clickedInventory != event.inventory) return

        when (event.rawSlot) {
            CONFIRM_SLOT -> {
                player.closeInventory()
                when (holder.action) {
                    ArchiveConfirmationAction.NAME -> proceedNameConfirm(player)
                    ArchiveConfirmationAction.EXECUTE -> executeArchive(player)
                }
            }
            CANCEL_SLOT -> {
                player.closeInventory()
                cancel(player)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
        if (sessions.isEmpty()) {
            HandlerList.unregisterAll(this)
        }
    }

    private fun proceedNameConfirm(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        session.step = ArchiveStep.PASSWORD_INPUT
        player.sendMessage(Component.empty())
        player.sendMessage(i18nComponent(player, "prompt.password_input"))
    }

    private fun executeArchive(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        sessions.remove(player.uniqueId)
        if (sessions.isEmpty()) HandlerList.unregisterAll(this)

        player.sendMessage(i18nComponent(player, "message.executing"))
        archiveService.createAnnualArchive(session.archiveName, player)
            .thenAccept { result ->
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (result.success) {
                        player.sendMessage(i18nComponent(player, "message.success", mapOf("name" to session.archiveName)))
                    } else {
                        player.sendMessage(i18nComponent(player, "message.failed", mapOf("reason" to result.message)))
                    }
                })
            }
    }

    private fun cancel(player: Player) {
        sessions.remove(player.uniqueId)
        if (sessions.isEmpty()) HandlerList.unregisterAll(this)
        player.sendMessage(i18nComponent(player, "message.cancelled"))
    }

    private fun i18n(player: Player, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.archive.$key", placeholders)
    }

    private fun i18nComponent(player: Player, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.archive.$key", placeholders)
    }

    private fun confirmationInventory(
        player: Player,
        action: ArchiveConfirmationAction,
        titleKey: String,
        titlePlaceholders: Map<String, Any>,
        confirmKey: String
    ): Inventory {
        val inventory = Bukkit.createInventory(
            ArchiveConfirmationHolder(player.uniqueId, action),
            27,
            i18nComponent(player, titleKey, titlePlaceholders)
        )
        inventory.setItem(CONFIRM_SLOT, menuItem(Material.LIME_CONCRETE, i18nComponent(player, confirmKey)))
        inventory.setItem(CANCEL_SLOT, menuItem(Material.BARRIER, i18nComponent(player, "message.cancelled")))
        return inventory
    }

    private fun menuItem(material: Material, name: Component): ItemStack {
        val item = ItemStack(material)
        item.itemMeta = item.itemMeta?.apply {
            displayName(name)
        }
        return item
    }

    private data class ArchiveSession(
        var step: ArchiveStep,
        var archiveName: String = ""
    )

    private data class ArchiveConfirmationHolder(
        val playerId: UUID,
        val action: ArchiveConfirmationAction
    ) : InventoryHolder {
        override fun getInventory(): Inventory {
            return Bukkit.createInventory(null, 27)
        }
    }

    private enum class ArchiveStep {
        NAME_INPUT,
        CONFIRM_NAME,
        PASSWORD_INPUT,
        CONFIRM_FINAL
    }

    private enum class ArchiveConfirmationAction {
        NAME,
        EXECUTE
    }

    private companion object {
        const val CONFIRM_SLOT = 11
        const val CANCEL_SLOT = 15
    }
}
