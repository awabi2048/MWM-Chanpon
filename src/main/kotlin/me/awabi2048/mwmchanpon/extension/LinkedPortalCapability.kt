package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.api.localization.LocalizationKey

import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionGuidance
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.mwmchanpon.ui.ChanponWorldSettingsPlacements
import me.awabi2048.mwmchanpon.ui.chanponCapabilityAction
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

class LinkedPortalCapability {
    init {
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = OWNER,
                id = CAPABILITY_ID,
                placement = ChanponWorldSettingsPlacements.FOOTER_LEFT_ACTIONS,
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability { context ->
                    val world = worldData(context.arguments) ?: return@optionalAvailability false
                    context.player.uniqueId == world.owner ||
                        world.members.contains(context.player.uniqueId) ||
                        context.player.hasPermission("mwmchanpon.admin")
                },
                presentationProvider = MenuCapabilityPresentationProvider { context ->
                    portalPresentation(context.player)
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "receive",
                        trigger = MenuCapabilityTrigger.ANY,
                        textProvider = MenuCapabilityActionTextProvider {
                            i18n(it.player, ChanponBackupKeys.CHANPON_BACKUP_LINKED_PORTAL_ACTION)
                        },
                        handler = MenuCapabilityActionHandler(::receivePortal),
                        safety = MenuActionSafety.EXTERNAL_SIDE_EFFECT,
                    ),
                ),
            ),
        )
    }

    private fun receivePortal(context: MenuCapabilityActionContext): MenuActionResult {
        val worldUuid = context.arguments[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Ignored
        val portal = MyWorldManagerApi.getWorldService()
            ?.createLinkedWorldPortalItem(context.player, worldUuid)
        if (portal == null) {
            context.player.sendMessage(i18n(context.player, ChanponBackupKeys.CHANPON_BACKUP_LINKED_PORTAL_FAILED))
            return MenuActionResult.Rejected()
        }
        val remaining = context.player.inventory.addItem(portal)
        remaining.values.forEach {
            context.player.world.dropItemNaturally(context.player.location, it)
        }
        context.player.sendMessage(i18n(context.player, ChanponBackupKeys.CHANPON_BACKUP_LINKED_PORTAL_RECEIVED))
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun portalPresentation(player: Player) = MenuCapabilityPresentation(
        GuiItemSpec(
            material = Material.END_PORTAL_FRAME,
            name = GuiNameSpec.FixedLabel(i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_LINKED_PORTAL_DISPLAY)),
            lore = GuiLoreSpec.None,
            role = GuiElementRole.ACTION,
            amount = 1,
        ),
    ).also {
        it.actionGuidance = MenuCapabilityActionGuidance.HIDDEN
    }

    private fun worldData(arguments: Map<String, String>): WorldData? =
        arguments[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }

    private fun i18n(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, key, placeholders)

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val CAPABILITY_ID = "linked-portal"
        private const val WORLD_UUID = "world_uuid"
    }
}
