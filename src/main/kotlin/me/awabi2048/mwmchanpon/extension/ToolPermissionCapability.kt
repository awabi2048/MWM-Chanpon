package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.api.localization.LocalizationKey

import com.awabi2048.ccsystem.api.localization.generated.ChanponMemberLoreKeys

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
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilityContract
import me.awabi2048.myworldmanager.model.WorldData
import java.util.UUID
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
                                i18n(it.player, ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_TOOL_PERMISSION_TITLE),
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
                            i18n(it.player, ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_TOOL_PERMISSION_OPERATION)
                        },
                        handler = MenuCapabilityActionHandler(::handlePrimaryAction),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                ),
            ),
        )
    }

    private fun handlePrimaryAction(context: MenuCapabilityActionContext): MenuActionResult {
        // ここで公開するのは、メンバーが画面から手動で切り替える権限だけです。
        // レッドストーンや自動実行の経路は、このcapabilityの責務外として扱います。
        val subject = subject(context) ?: return MenuActionResult.Ignored
        if (!canManage(context.player, subject.worldData)) return MenuActionResult.Ignored
        return MenuActionResult.Success(MenuUpdate.Navigate(menu.route(subject.worldData.uuid, subject.memberUuid)))
    }

    private fun canManage(player: Player, worldData: WorldData): Boolean =
        player.uniqueId == worldData.owner || player.hasPermission("myworldmanager.admin")

    private fun subject(context: MenuCapabilityContext): ToolPermissionSubject? =
        subject(context.arguments)

    private fun subject(context: MenuCapabilityActionContext): ToolPermissionSubject? =
        subject(context.arguments)

    private fun subject(arguments: Map<String, String>): ToolPermissionSubject? {
        val target = MemberManagementCapabilityContract.resolveTarget(arguments) ?: return null
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(target.worldUuid) ?: return null
        return ToolPermissionSubject(worldData, target.targetPlayerUuid)
    }

    private fun i18n(player: Player, key: LocalizationKey<String>): String =
        CCSystem.getAPI().getLocalized(player, key)

    private companion object {
        const val CAPABILITY_OWNER = "mwm-chanpon"
        const val CAPABILITY_ID = "member-tool-permissions"
    }
}

private data class ToolPermissionSubject(
    val worldData: WorldData,
    val memberUuid: UUID,
)

internal fun toolPermissionLoreBlock(
    title: String,
    definitions: List<ToolPermissionDefinition>,
    states: Map<String, Boolean>,
): GuiLoreBlock = GuiLoreBlock(buildList {
    add(GuiLoreLine.Data(title, null, ""))
    definitions.forEach { definition ->
        add(
            GuiLoreLine.Checkbox(
                label = definition.displayName,
                checked = states[definition.id] == true,
                checkedColor = "§6",
                uncheckedColor = "§8",
            ),
        )
    }
})
