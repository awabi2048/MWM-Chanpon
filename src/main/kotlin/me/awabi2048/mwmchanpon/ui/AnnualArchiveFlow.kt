package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponArchiveKeys
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import io.papermc.paper.event.player.AsyncChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import org.bukkit.event.player.PlayerQuitEvent

class AnnualArchiveFlow(
    private val plugin: MWMChanponPlugin,
    private val archiveService: ArchiveService,
) : Listener {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    private val sessions = ConcurrentHashMap<UUID, ArchiveSession>()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_NAME_CONFIRM,
                renderer = { context -> renderNameConfirmation(context.player) },
                actions = mapOf(
                    ACTION_CONFIRM_NAME to MenuActionHandler(::confirmName),
                    ACTION_CANCEL to MenuActionHandler(::cancelFromMenu),
                ),
                onClose = { context -> cancelClosedStep(context.player.uniqueId, ArchiveStep.CONFIRM_NAME) },
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_FINAL_CONFIRM,
                renderer = { context -> renderFinalConfirmation(context.player) },
                actions = mapOf(
                    ACTION_EXECUTE to MenuActionHandler(::executeFromMenu),
                    ACTION_CANCEL to MenuActionHandler(::cancelFromMenu),
                ),
                onClose = { context -> cancelClosedStep(context.player.uniqueId, ArchiveStep.CONFIRM_FINAL) },
            ),
        )
    }

    fun start(player: Player) {
        if (sessions.containsKey(player.uniqueId)) {
            player.sendMessage(i18n(player, ChanponArchiveKeys.CHANPON_ARCHIVE_ERROR_ALREADY_IN_PROGRESS))
            return
        }
        val shouldRegister = sessions.isEmpty()
        sessions[player.uniqueId] = ArchiveSession(ArchiveStep.NAME_INPUT)
        if (shouldRegister) {
            plugin.server.pluginManager.registerEvents(this, plugin)
        }
        player.sendMessage(Component.empty())
        player.sendMessage(i18nComponent(player, ChanponArchiveKeys.CHANPON_ARCHIVE_PROMPT_NAME_INPUT))
        player.sendMessage(
            Component.text(i18n(player, ChanponArchiveKeys.CHANPON_ARCHIVE_PROMPT_CANCEL_HINT), NamedTextColor.GRAY)
                .clickEvent(ClickEvent.callback { cancel(player, closeMenu = true) })
                .hoverEvent(HoverEvent.showText(Component.text(i18n(player, ChanponArchiveKeys.CHANPON_ARCHIVE_PROMPT_CANCEL_HOVER)))),
        )
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val session = sessions[player.uniqueId] ?: return
        event.isCancelled = true
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val message = PlainTextComponentSerializer.plainText()
                .serialize(event.message())
                .trim()
            when (session.step) {
                ArchiveStep.NAME_INPUT -> acceptArchiveName(player, session, message)
                ArchiveStep.CONFIRM_NAME, ArchiveStep.CONFIRM_FINAL ->
                    player.sendMessage(i18nComponent(player, ChanponArchiveKeys.CHANPON_ARCHIVE_ERROR_USE_BUTTON))
                ArchiveStep.PASSWORD_INPUT -> acceptPassword(player, session, message)
            }
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
        unregisterWhenIdle()
    }

    private fun acceptArchiveName(player: Player, session: ArchiveSession, message: String) {
        if (message.isBlank()) {
            player.sendMessage(i18nComponent(player, ChanponArchiveKeys.CHANPON_ARCHIVE_ERROR_EMPTY_NAME))
            return
        }
        session.archiveName = message
        session.step = ArchiveStep.CONFIRM_NAME
        runtime.open(player, MenuRoute(OWNER, ROUTE_NAME_CONFIRM))
    }

    private fun acceptPassword(player: Player, session: ArchiveSession, message: String) {
        val expectedPassword = plugin.config.getString("archive.password", "たぬきスープ")
        if (message != expectedPassword) {
            player.sendMessage(i18nComponent(player, ChanponArchiveKeys.CHANPON_ARCHIVE_ERROR_WRONG_PASSWORD))
            cancel(player, closeMenu = false)
            return
        }
        session.step = ArchiveStep.CONFIRM_FINAL
        runtime.open(player, MenuRoute(OWNER, ROUTE_FINAL_CONFIRM))
    }

    private fun renderNameConfirmation(player: Player): InventoryMenuView {
        val session = sessions[player.uniqueId]
            ?: error("アーカイブ作成セッションがありません")
        return confirmationView(
            player,
            ChanponArchiveKeys.CHANPON_ARCHIVE_CONFIRM_NAME,
            mapOf("name" to session.archiveName),
            ChanponArchiveKeys.CHANPON_ARCHIVE_CONFIRM_PROCEED,
            ACTION_CONFIRM_NAME,
        )
    }

    private fun renderFinalConfirmation(player: Player): InventoryMenuView {
        val session = sessions[player.uniqueId]
            ?: error("アーカイブ作成セッションがありません")
        return confirmationView(
            player,
            ChanponArchiveKeys.CHANPON_ARCHIVE_CONFIRM_FINAL,
            mapOf("name" to session.archiveName),
            ChanponArchiveKeys.CHANPON_ARCHIVE_CONFIRM_EXECUTE,
            ACTION_EXECUTE,
        )
    }

    private fun confirmationView(
        player: Player,
        titleKey: LocalizationKey<String>,
        titlePlaceholders: Map<String, Any>,
        confirmKey: LocalizationKey<String>,
        confirmAction: String,
    ): InventoryMenuView = InventoryMenuView(
        27,
        i18nComponent(player, titleKey, titlePlaceholders),
        listOf(
            confirmationEntry(
                player,
                CONFIRM_SLOT,
                Material.LIME_CONCRETE,
                confirmKey,
                GuiElementRole.CONFIRM,
                confirmAction,
            ),
            confirmationEntry(
                player,
                CANCEL_SLOT,
                Material.BARRIER,
                ChanponArchiveKeys.CHANPON_ARCHIVE_MESSAGE_CANCELLED,
                GuiElementRole.CANCEL,
                ACTION_CANCEL,
            ),
        ),
    ).asConfirmation()

    private fun confirmationEntry(
        player: Player,
        slot: Int,
        material: Material,
        textKey: LocalizationKey<String>,
        role: GuiElementRole,
        actionId: String,
    ): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = material,
                name = GuiNameSpec.FixedLabel(i18nComponent(player, textKey)),
                role = role,
                actions = listOf(
                    chanponGestureAction(
                        actionId,
                        MenuGesture.ANY,
                        i18n(player, textKey),
                        safety = archiveActionSafety(actionId),
                    ),
                ),
            ),
        )

    private fun confirmName(context: MenuActionContext): MenuActionResult {
        val session = sessions[context.player.uniqueId]
            ?: return MenuActionResult.Rejected()
        session.step = ArchiveStep.PASSWORD_INPUT
        context.player.sendMessage(Component.empty())
        context.player.sendMessage(i18nComponent(context.player, ChanponArchiveKeys.CHANPON_ARCHIVE_PROMPT_PASSWORD_INPUT))
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun executeFromMenu(context: MenuActionContext): MenuActionResult {
        executeArchive(context.player)
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun cancelFromMenu(context: MenuActionContext): MenuActionResult {
        cancel(context.player, closeMenu = false)
        // 最終確認からのキャンセルは、確認画面群だけを履歴から取り除きます。
        // 開始前メニューがあればRuntimeがそこへ戻し、チャット起点でなければ画面を閉じます。
        return MenuActionResult.Success(MenuUpdate.Cancel)
    }

    private fun executeArchive(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        unregisterWhenIdle()
        player.sendMessage(i18nComponent(player, ChanponArchiveKeys.CHANPON_ARCHIVE_MESSAGE_EXECUTING))
        archiveService.createAnnualArchive(session.archiveName, player)
            .thenAccept { result ->
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (result.success) {
                        player.sendMessage(
                            i18nComponent(
                                player,
                                ChanponArchiveKeys.CHANPON_ARCHIVE_MESSAGE_SUCCESS,
                                mapOf("name" to session.archiveName),
                            ),
                        )
                    } else {
                        player.sendMessage(
                            i18nComponent(
                                player,
                                ChanponArchiveKeys.CHANPON_ARCHIVE_MESSAGE_FAILED,
                                mapOf("reason" to result.message),
                            ),
                        )
                    }
                })
            }
    }

    private fun archiveActionSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_CONFIRM_NAME -> MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE
        ACTION_EXECUTE -> MenuActionSafety.IRREVERSIBLE
        ACTION_CANCEL -> MenuActionSafety.NAVIGATION_ONLY
        else -> error("Annual archive action must declare a dedicated safety mapping: $actionId")
    }

    private fun cancel(player: Player, closeMenu: Boolean) {
        sessions.remove(player.uniqueId)
        unregisterWhenIdle()
        if (closeMenu) {
            runtime.close(player)
        }
        player.sendMessage(i18nComponent(player, ChanponArchiveKeys.CHANPON_ARCHIVE_MESSAGE_CANCELLED))
    }

    private fun unregisterWhenIdle() {
        if (sessions.isEmpty()) {
            HandlerList.unregisterAll(this)
        }
    }

    private fun cancelClosedStep(playerUuid: UUID, expectedStep: ArchiveStep) {
        if (sessions[playerUuid]?.step == expectedStep) {
            sessions.remove(playerUuid)
            unregisterWhenIdle()
        }
    }

    private fun i18n(
        player: Player,
        key: LocalizationKey<String>,
        placeholders: Map<String, Any> = emptyMap(),
    ): String = CCSystem.getAPI().getLocalized(
        player,
        key,
        placeholders,
    )

    private fun i18nComponent(
        player: Player,
        key: LocalizationKey<String>,
        placeholders: Map<String, Any> = emptyMap(),
    ): Component = CCSystem.getAPI().getI18nComponent(
        player,
        key,
        placeholders,
    )

    private data class ArchiveSession(
        var step: ArchiveStep,
        var archiveName: String = "",
    )

    private enum class ArchiveStep {
        NAME_INPUT,
        CONFIRM_NAME,
        PASSWORD_INPUT,
        CONFIRM_FINAL,
    }

    private companion object {
        const val OWNER = "mwm-chanpon"
        const val ROUTE_NAME_CONFIRM = "annual-archive-name-confirm"
        const val ROUTE_FINAL_CONFIRM = "annual-archive-final-confirm"
        const val ACTION_CONFIRM_NAME = "confirm-name"
        const val ACTION_EXECUTE = "execute"
        const val ACTION_CANCEL = "cancel"
        const val CONFIRM_SLOT = 11
        const val CANCEL_SLOT = 15
    }
}
