package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.ui.ChanponLore
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class LinkedPortalMenuExtension : MenuExtension {
    override fun getId(): String = EXTENSION_ID

    override fun onRender(inventory: Inventory, player: Player, context: MenuExtensionContext) {
        if (context.menuType != "world_settings") return
        val worldData = context.get<WorldData>("worldData") ?: return
        val isOwner = context.get<Boolean>("isOwner") == true
        val isMember = context.get<Boolean>("isMember") == true
        if (!isOwner && !isMember) return

        val footerStart = inventory.size - 9
        val slot = if (isOwner) footerStart + 1 else footerStart + 2
        inventory.setItem(slot, portalItem(player, worldData))
    }

    override fun onClick(event: InventoryClickEvent, player: Player, context: MenuExtensionContext): Boolean {
        val item = event.currentItem ?: return false
        if (ItemTag.getExtensionId(item) != EXTENSION_ID) return false
        val worldUuid = ItemTag.getWorldUuid(item) ?: return true
        val portal = MyWorldManagerApi.getWorldService()?.createLinkedWorldPortalItem(player, worldUuid)
        if (portal == null) {
            player.sendMessage(i18n(player, "linked_portal.failed"))
            return true
        }
        val remaining = player.inventory.addItem(portal)
        remaining.values.forEach { player.world.dropItemNaturally(player.location, it) }
        player.sendMessage(i18n(player, "linked_portal.received"))
        return true
    }

    private fun portalItem(player: Player, worldData: WorldData): ItemStack {
        val item = ItemStack(Material.END_PORTAL_FRAME)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "linked_portal.display"))
            meta.lore(ChanponLore.build(i18nList(player, "linked_portal.lore", mapOf("world" to worldData.name))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, EXTENSION_ID)
        ItemTag.setWorldUuid(item, worldData.uuid)
        return item
    }

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key", placeholders)

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    companion object {
        private const val EXTENSION_ID = "mwmchanpon.linked_portal"
    }
}
