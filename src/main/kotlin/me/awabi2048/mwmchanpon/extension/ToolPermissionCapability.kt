package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilityContract
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilitySubject
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class ToolPermissionCapability(
    private val menu: ToolPermissionMenu,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>,
) {
    init {
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = CAPABILITY_OWNER,
                id = CAPABILITY_ID,
                placement = MemberManagementCapabilityContract.PLACEMENT,
                availability = MenuCapabilityAvailability {
                    subject(it)?.let { subject ->
                        canManage(it.player, subject.worldData)
                    } == true
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    val subject = requireNotNull(subject(it))
                    buildPresentation(
                        it.player,
                        subject.worldData,
                        subject.memberUuid,
                        controller.getMemberToolStates(
                            subject.worldData,
                            subject.memberUuid,
                        ),
                    )
                },
                actions = listOf(
                    MenuCapabilityAction(
                        id = "edit",
                        // メンバー一覧ではShift操作を別枝として併設するため、通常左右クリックを使用する。
                        trigger = MenuCapabilityTrigger.LEFT_RIGHT,
                        textProvider = MenuCapabilityActionTextProvider {
                            i18n(it.player, "click_edit_action")
                        },
                        handler = MenuCapabilityActionHandler(::handlePrimaryAction),
                    ),
                ),
            ),
        )
    }

    private fun handlePrimaryAction(
        context: MenuCapabilityActionContext,
    ): MenuActionResult {
        val subject = subject(context) ?: return MenuActionResult.Ignored
        if (!canManage(context.player, subject.worldData)) return MenuActionResult.Ignored
        return MenuActionResult.Success(
            MenuUpdate.Navigate(
                menu.route(subject.worldData.uuid, subject.memberUuid),
            ),
        )
    }

    private fun canManage(player: Player, worldData: WorldData): Boolean =
        player.uniqueId == worldData.owner ||
            player.hasPermission("myworldmanager.admin")

    private fun buildPresentation(
        player: Player,
        worldData: WorldData,
        memberUuid: java.util.UUID,
        states: Map<String, Boolean>,
    ): MenuCapabilityPresentation {
        val offlinePlayer = Bukkit.getOfflinePlayer(memberUuid)
        val detailLines = mutableListOf<GuiLoreLine>()

        detailLines += GuiLoreLine.Data(
            i18n(player, "tool_permission_title"),
            null,
            "",
        )
        definitions.forEach { definition ->
            detailLines += GuiLoreLine.Text(
                if (states[definition.id] == true) {
                    "§6◆ ${definition.displayName}"
                } else {
                    "§8◆ §7${definition.displayName}"
                },
            )
        }

        val blocks = listOf(GuiLoreBlock(detailLines))
        return MenuCapabilityPresentation(
            item = GuiItemSpec(
                material = Material.PLAYER_HEAD,
                name = GuiNameSpec.Text("", GuiNameStyle.DEFAULT),
                lore = GuiLoreSpec.Blocks(blocks),
                role = GuiElementRole.ACTION,
                amount = 1,
            ),
            embeddedLoreBlocks = blocks,
        )
    }

    private fun subject(context: MenuCapabilityContext): MemberManagementCapabilitySubject? =
        context.attributes[MemberManagementCapabilityContract.SUBJECT_ATTRIBUTE]
            as? MemberManagementCapabilitySubject

    private fun subject(context: MenuCapabilityActionContext): MemberManagementCapabilitySubject? =
        context.attributes[MemberManagementCapabilityContract.SUBJECT_ATTRIBUTE]
            as? MemberManagementCapabilitySubject

    private fun i18n(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.member_lore.$key")

    private companion object {
        const val CAPABILITY_OWNER = "mwm-chanpon"
        const val CAPABILITY_ID = "member-tool-permissions"
    }
}
