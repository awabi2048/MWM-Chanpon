package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.util.cancelWithDebug
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.bridge.CoreApiBridge
import me.awabi2048.mwmchanpon.service.WizardSession
import me.awabi2048.myworldmanager.model.TemplateData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap
import io.papermc.paper.event.player.AsyncChatEvent

class AdminWizardGui(
    private val plugin: MWMChanponPlugin,
    private val bridge: CoreApiBridge
) : Listener {

    private val pendingNameInput = ConcurrentHashMap<Player, WizardSession>()

    companion object {
        private const val TEMPLATE_INV_TITLE = "テンプレート選択"
        private const val CONFIRM_INV_TITLE = "作成確認"

    }

    fun openTemplateSelection(player: Player, session: WizardSession) {
        val templates = bridge.getTemplateRepository()?.findAll() ?: run {
            player.sendMessage(Component.text(plugin.getMessage(player, "wizard.no_templates")))
            plugin.wizardSessionService.removeSession(player.uniqueId)
            return
        }

        val size = if (templates.size % 9 == 0) templates.size + 9 else (templates.size / 9 + 1) * 9
        val holder = TemplateSelectionHolder()
        val inv = Bukkit.createInventory(
            holder,
            size.coerceIn(9, 54),
            Component.text(TEMPLATE_INV_TITLE)
        )
        holder.inv = inv

        for ((index, template) in templates.withIndex()) {
            val item = createTemplateItem(template)
            inv.setItem(index, item)
        }

        player.openInventory(inv)
    }

    private fun createTemplateItem(template: TemplateData): ItemStack {
        val item = ItemStack(template.icon)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name("§a${template.name}"))
            meta.lore(ChanponLore.build(
                template.description + plugin.getMessage(null, "wizard.template_select_lore")
            ))
        }
        return item
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val titleStr = event.view.title().let {
            PlainTextComponentSerializer.plainText().serialize(it)
        }
        if (titleStr != TEMPLATE_INV_TITLE && titleStr != CONFIRM_INV_TITLE) return

        event.cancelWithDebug("AdminWizardGui.onInventoryClick: template_gui_click")
        if (event.clickedInventory != event.view.topInventory) return

        val player = event.whoClicked as? Player ?: return
        val clickedItem = event.currentItem ?: return

        if (titleStr == TEMPLATE_INV_TITLE) {
            handleTemplateClick(player, clickedItem)
        } else {
            handleConfirmClick(player, clickedItem)
        }
    }

    private fun handleTemplateClick(player: Player, item: ItemStack) {
        val session = plugin.wizardSessionService.getSession(player.uniqueId) ?: return

        if (item.type == Material.GRAY_STAINED_GLASS_PANE) return
        if (!item.hasItemMeta()) return

        val displayName = PlainTextComponentSerializer.plainText()
            .serialize(item.itemMeta?.displayName() ?: Component.empty())
            .trim()
            .removePrefix("§a")

        val templates = bridge.getTemplateRepository()?.findAll() ?: return
        val template = templates.find { it.name == displayName } ?: return

        session.selectedTemplateId = template.id
        player.closeInventory()

        pendingNameInput[player] = session
        player.sendMessage(Component.text(plugin.getMessage(player, "wizard.name_prompt")))
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val session = pendingNameInput.remove(player) ?: return

        event.isCancelled = true
        val worldName = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (worldName.length < 1 || worldName.length > 32) {
            player.sendMessage(Component.text(plugin.getMessage(player, "wizard.name_invalid")))
            pendingNameInput[player] = session
            return
        }

        session.worldName = worldName

        Bukkit.getScheduler().runTask(plugin) { _ ->
            openConfirmScreen(player, session)
        }
    }

    private fun openConfirmScreen(player: Player, session: WizardSession) {
        val templateRepo = bridge.getTemplateRepository() ?: return
        val template = templateRepo.findById(session.selectedTemplateId ?: return) ?: return

        val holder = ConfirmationHolder()
        val inv = Bukkit.createInventory(
            holder,
            27,
            Component.text(CONFIRM_INV_TITLE)
        )
        holder.inv = inv

        ChanponGuiElements.fill(inv)

        val infoLore = CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(
                listOf(
                    GuiLoreLine.Data(plugin.getMessage(null, "wizard.confirm_player_label"), session.targetPlayerName, "§7"),
                    GuiLoreLine.Data(plugin.getMessage(null, "wizard.confirm_world_label"), session.worldName ?: "", "§7"),
                    GuiLoreLine.SubData(plugin.getMessage(null, "wizard.confirm_template_label"), template.name),
                    GuiLoreLine.Raw(plugin.getMessage(null, "wizard.confirm_hint_confirm")),
                    GuiLoreLine.Raw(plugin.getMessage(null, "wizard.confirm_hint_cancel"))
                ),
                GuiLoreFrame.TOP
            )
        )

        val infoItem = ItemStack(Material.PAPER)
        infoItem.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(plugin.getMessage(null, "wizard.confirm_title")))
            meta.lore(infoLore)
        }
        inv.setItem(13, infoItem)

        val confirmItem = ChanponGuiElements.confirm(plugin.getMessage(null, "wizard.confirm_btn"), true)
        inv.setItem(11, confirmItem)

        val cancelItem = ChanponGuiElements.confirm(plugin.getMessage(null, "wizard.cancel_btn"), false)
        inv.setItem(15, cancelItem)

        player.openInventory(inv)
    }

    private fun handleConfirmClick(player: Player, item: ItemStack) {
        val session = plugin.wizardSessionService.getSession(player.uniqueId) ?: return
        val displayName = PlainTextComponentSerializer.plainText()
            .serialize(item.itemMeta?.displayName() ?: Component.empty())

        when {
            displayName.contains("作成する") -> {
                val worldService = bridge.getWorldService()
                if (worldService == null) {
                    player.sendMessage(Component.text(plugin.getMessage(player, "wizard.api_unavailable")))
                    player.closeInventory()
                    plugin.wizardSessionService.removeSession(player.uniqueId)
                    return
                }

                val templateId = session.selectedTemplateId
                val worldName = session.worldName
                val targetUuid = session.targetPlayerUuid

                if (templateId == null || worldName == null || targetUuid == null) {
                    player.sendMessage(Component.text(plugin.getMessage(player, "wizard.invalid_session")))
                    player.closeInventory()
                    plugin.wizardSessionService.removeSession(player.uniqueId)
                    return
                }

                val target = Bukkit.getPlayer(targetUuid)
                if (target == null || !target.isOnline) {
                    player.sendMessage(Component.text(plugin.getMessage(player, "wizard.player_offline")))
                    player.closeInventory()
                    plugin.wizardSessionService.removeSession(player.uniqueId)
                    return
                }

                player.closeInventory()
                player.sendMessage(Component.text(plugin.getMessage(player, "wizard.creating")))

                worldService.createFromTemplate(templateId, targetUuid, worldName, 0)
                    .thenAccept { success ->
                        Bukkit.getScheduler().runTask(plugin) { _ ->
                            if (success) {
                                player.sendMessage(Component.text(plugin.getMessage(player, "wizard.success", "player" to target.name, "world" to worldName)))
                            } else {
                                player.sendMessage(Component.text(plugin.getMessage(player, "wizard.failed")))
                            }
                        }
                    }
                plugin.wizardSessionService.removeSession(player.uniqueId)
            }
            displayName.contains("キャンセル") -> {
                player.closeInventory()
                player.sendMessage(Component.text(plugin.getMessage(player, "wizard.cancelled")))
                plugin.wizardSessionService.removeSession(player.uniqueId)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        pendingNameInput.remove(player)
        plugin.wizardSessionService.removeSession(player.uniqueId)
    }

    private class TemplateSelectionHolder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class ConfirmationHolder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }
}
