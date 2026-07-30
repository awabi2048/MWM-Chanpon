package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapability
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilityContext
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilityView
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class ToolPermissionCapability(
    private val menu: ToolPermissionMenu,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>,
) : MemberManagementCapability {
    override fun getId(): String = ToolPermissionMenu.EXTENSION_ID

    override fun resolve(
        context: MemberManagementCapabilityContext,
    ): MemberManagementCapabilityView? {
        if (!canManage(context.player, context.worldData)) return null
        val states = controller.getMemberToolStates(
            context.worldData,
            context.memberUuid,
        )
        return buildView(
            context.player,
            context.worldData,
            context.memberUuid,
            states,
        )
    }

    override fun handlePrimaryAction(
        context: MemberManagementCapabilityContext,
    ): MenuActionResult {
        if (resolve(context) == null) return MenuActionResult.Ignored
        return MenuActionResult.Success(
            MenuUpdate.Navigate(
                menu.route(context.worldData.uuid, context.memberUuid),
            ),
        )
    }

    private fun canManage(player: Player, worldData: WorldData): Boolean =
        player.uniqueId == worldData.owner ||
            player.hasPermission("myworldmanager.admin")

    private fun buildView(
        player: Player,
        worldData: WorldData,
        memberUuid: java.util.UUID,
        states: Map<String, Boolean>,
    ): MemberManagementCapabilityView {
        val offlinePlayer = Bukkit.getOfflinePlayer(memberUuid)
        val detailLines = mutableListOf<GuiLoreLine>()

        if (!offlinePlayer.isOnline) {
            val date = if (offlinePlayer.lastSeen > 0) {
                Instant.ofEpochMilli(offlinePlayer.lastSeen)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
            } else {
                i18n(player, "unknown")
            }
            detailLines += GuiLoreLine.Data(
                i18n(player, "last_online_label"),
                date,
                "§f",
            )
            detailLines += GuiLoreLine.Spacer
        }

        detailLines += GuiLoreLine.Data(
            i18n(player, "tool_permission_title"),
            null,
            "",
        )
        definitions.forEach { definition ->
            detailLines += GuiLoreLine.Text(
                if (states[definition.id] == true) {
                    "§6✦ ${definition.displayName}"
                } else {
                    "§8・ §7${definition.displayName}"
                },
            )
        }

        val actionLines = mutableListOf<GuiLoreLine>(
            GuiLoreLine.Action(
                i18n(player, "click_edit_operation"),
                i18n(player, "click_edit_action"),
            ),
            GuiLoreLine.Spacer,
        )
        if (memberUuid != worldData.owner) {
            actionLines += GuiLoreLine.Action(
                i18n(player, "transfer_owner_operation"),
                i18n(player, "transfer_owner_action"),
            )
            actionLines += GuiLoreLine.Action(
                i18n(player, "remove_member_operation"),
                i18n(player, "remove_member_action"),
            )
        }

        return MemberManagementCapabilityView(detailLines, actionLines)
    }

    private fun i18n(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.member_lore.$key")
}
