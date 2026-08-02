package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.mwmchanpon.ui.chanponCapabilityAction
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilityContract
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilitySubject
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.entity.Player

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
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability {
                    subject(it)?.let { subject -> canManage(it.player, subject.worldData) } == true
                },
                presentationProvider = MenuCapabilityPresentationProvider {
                    val subject = requireNotNull(subject(it))
                    MenuCapabilityPresentation.hostAugmentation(
                        listOf(
                            toolPermissionLoreBlock(
                                i18n(it.player, "tool_permission_title"),
                                definitions,
                                controller.getMemberToolStates(subject.worldData, subject.memberUuid),
                            ),
                        ),
                    )
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "edit",
                        trigger = MenuCapabilityTrigger.LEFT_RIGHT,
                        textProvider = MenuCapabilityActionTextProvider {
                            i18n(it.player, "tool_permission_operation")
                        },
                        handler = MenuCapabilityActionHandler(::handlePrimaryAction),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                ),
            ),
        )
    }

    private fun handlePrimaryAction(context: MenuCapabilityActionContext): MenuActionResult {
        val subject = subject(context) ?: return MenuActionResult.Ignored
        if (!canManage(context.player, subject.worldData)) return MenuActionResult.Ignored
        return MenuActionResult.Success(MenuUpdate.Navigate(menu.route(subject.worldData.uuid, subject.memberUuid)))
    }

    private fun canManage(player: Player, worldData: WorldData): Boolean =
        player.uniqueId == worldData.owner || player.hasPermission("myworldmanager.admin")

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

internal fun toolPermissionLoreBlock(
    title: String,
    definitions: List<ToolPermissionDefinition>,
    states: Map<String, Boolean>,
): GuiLoreBlock = GuiLoreBlock(buildList {
    add(GuiLoreLine.Data(title, null, ""))
    definitions.forEach { definition ->
        add(
            GuiLoreLine.Text(
                if (states[definition.id] == true) {
                    "§6● ${definition.displayName}"
                } else {
                    "§8● §7${definition.displayName}"
                },
            ),
        )
    }
})
