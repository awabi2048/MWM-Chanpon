package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityItemRenderer
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.ui.ChanponLore
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsCapabilityPlacements
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class LinkedPortalCapability {
    init {
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = OWNER,
                id = CAPABILITY_ID,
                placement = WorldSettingsCapabilityPlacements.FOOTER_LEFT_ACTIONS,
                availability = MenuCapabilityAvailability { context ->
                    val world = worldData(context.arguments) ?: return@MenuCapabilityAvailability false
                    context.player.uniqueId == world.owner ||
                        world.members.contains(context.player.uniqueId) ||
                        context.player.hasPermission("myworldmanager.admin")
                },
                itemRenderer = MenuCapabilityItemRenderer { context ->
                    portalItem(context.player, requireNotNull(worldData(context.arguments)))
                },
                actionHandler = MenuCapabilityActionHandler(::receivePortal),
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
            context.player.sendMessage(i18n(context.player, "linked_portal.failed"))
            return MenuActionResult.Rejected()
        }
        val remaining = context.player.inventory.addItem(portal)
        remaining.values.forEach {
            context.player.world.dropItemNaturally(context.player.location, it)
        }
        context.player.sendMessage(i18n(context.player, "linked_portal.received"))
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun portalItem(player: Player, worldData: WorldData): ItemStack {
        val item = ItemStack(Material.END_PORTAL_FRAME)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "linked_portal.display"))
            meta.lore(null)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        return item
    }

    private fun worldData(arguments: Map<String, String>): WorldData? =
        arguments[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val CAPABILITY_ID = "linked-portal"
        private const val WORLD_UUID = "world_uuid"
    }
}
