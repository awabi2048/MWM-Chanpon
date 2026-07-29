package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

class ToolPermissionMenuExtension(
    private val menu: ToolPermissionMenu,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>
) : MenuExtension {
    override fun getId(): String = ToolPermissionMenu.EXTENSION_ID

    override fun onRender(items: Map<Int, ItemStack>, player: Player, context: MenuExtensionContext): Map<Int, ItemStack> {
        if (context.menuType != "member_management") return items
        val worldData = context.get<WorldData>("worldData") ?: return items
        if (!canManage(player, worldData)) return items

        return items.mapValues { (_, sourceItem) ->
            val item = sourceItem.clone()
            if (ItemTag.getType(item) != ItemTag.TYPE_GUI_MEMBER_ITEM) return@mapValues sourceItem

            val memberUuid = ItemTag.getWorldUuid(item) ?: return@mapValues sourceItem
            val states = controller.getMemberToolStates(worldData, memberUuid)
            val newLore = rebuildLore(player, worldData, memberUuid, states)
            val meta = item.itemMeta
            meta.lore(newLore)
            item.itemMeta = meta
            item
        }
    }

    override fun onClick(click: ClickType, item: ItemStack, player: Player, context: MenuExtensionContext): Boolean {
        if (context.menuType != "member_management") return false
        if (ItemTag.getType(item) != ItemTag.TYPE_GUI_MEMBER_ITEM || !click.isLeftClick || click.isShiftClick) return false

        val worldData = context.get<WorldData>("worldData") ?: return false
        if (!canManage(player, worldData)) return false
        val memberUuid = ItemTag.getWorldUuid(item) ?: return false

        menu.open(player, worldData, memberUuid)
        return true
    }

    private fun canManage(player: Player, worldData: WorldData): Boolean =
        player.uniqueId == worldData.owner || player.hasPermission("myworldmanager.admin")

    private fun i18n(player: Player, key: String): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.member_lore.$key")
    }

    private fun rebuildLore(
        player: Player,
        worldData: WorldData,
        memberUuid: UUID,
        states: Map<String, Boolean>
    ): List<Component> {
        val api = CCSystem.getAPI()
        val offlinePlayer = Bukkit.getOfflinePlayer(memberUuid)
        val lines = mutableListOf<GuiLoreLine>()

        if (!offlinePlayer.isOnline) {
            val dateStr = if (offlinePlayer.lastSeen > 0) {
                Instant.ofEpochMilli(offlinePlayer.lastSeen)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
            } else {
                i18n(player, "unknown")
            }
            lines.add(GuiLoreLine.Data(i18n(player, "last_online_label"), dateStr, "§f"))
            lines.add(GuiLoreLine.Spacer)
        }

        lines.add(GuiLoreLine.Data(i18n(player, "tool_permission_title"), null, ""))
        definitions.forEach { definition ->
            lines.add(
                GuiLoreLine.Text(
                    if (states[definition.id] == true) {
                        "§6✦ ${definition.displayName}"
                    } else {
                        "§8・ §7${definition.displayName}"
                    },
                ),
            )
        }

        val actionLines = mutableListOf<GuiLoreLine>(
            GuiLoreLine.Action(i18n(player, "click_edit_operation"), i18n(player, "click_edit_action")),
            GuiLoreLine.Spacer
        )
        if (memberUuid != worldData.owner) {
            // MWM側の描画済みLoreから操作行を拾わず、Chanpon側で必要な操作を構造として明示する。
            actionLines.add(GuiLoreLine.Action(i18n(player, "transfer_owner_operation"), i18n(player, "transfer_owner_action")))
            actionLines.add(GuiLoreLine.Action(i18n(player, "remove_member_operation"), i18n(player, "remove_member_action")))
        }

        return api.getLoreService().render(GuiLoreSpec.Blocks(listOf(
            GuiLoreBlock(lines),
            GuiLoreBlock(actionLines)
        )))
    }
}
