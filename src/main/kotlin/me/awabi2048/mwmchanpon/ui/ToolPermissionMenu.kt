package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import java.util.UUID
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class ToolPermissionMenu(
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>
) {
    companion object {
        const val EXTENSION_ID = "chanpon.tool_permissions"
    }

    fun open(player: Player, worldData: WorldData, targetPlayerUuid: UUID, onBack: () -> Unit) {
        val holder = ToolPermissionMenuHolder(worldData.uuid, targetPlayerUuid, onBack)
        val inventory = Bukkit.createInventory(
            holder,
            45,
            LegacyComponentSerializer.legacySection().deserialize("§8ツール権限の編集")
        )
        holder.bind(inventory)

        ChanponGuiElements.applyStandardFrame(inventory)
        ChanponGuiElements.fill(inventory)

        val targetPlayer = Bukkit.getOfflinePlayer(targetPlayerUuid)
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta { meta ->
            if (meta is org.bukkit.inventory.meta.SkullMeta) {
                meta.owningPlayer = targetPlayer
            }
            meta.displayName(CCSystem.getAPI().getGuiElementService().name("§e${targetPlayer.name ?: "Unknown"}"))
        }
        inventory.setItem(4, head)

        val toolSlots = listOf(20, 22, 24)
        definitions.take(toolSlots.size).forEachIndexed { index, definition ->
            inventory.setItem(toolSlots[index], createToolItem(worldData, targetPlayerUuid, definition))
        }

        val back = ItemStack(Material.REDSTONE)
        back.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name("§7戻る"))
        }
        ItemTag.tagItem(back, ItemTag.TYPE_GUI_RETURN)
        inventory.setItem(40, back)

        player.openInventory(inventory)
    }

    private fun createToolItem(worldData: WorldData, targetPlayerUuid: UUID, definition: ToolPermissionDefinition): ItemStack {
        val enabled = controller.isEnabled(worldData, targetPlayerUuid, definition.id)
        val item = ItemStack(definition.material)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name("§f${definition.displayName}"))
            meta.lore(ChanponLore.build(listOf(
                GuiLoreLine.Data(
                    i18n("state_label"),
                    i18n(if (enabled) "enabled" else "disabled"),
                    if (enabled) "§a" else "§c"
                ),
                GuiLoreLine.SingleAction(
                    i18n("toggle_operation"),
                    i18n("toggle_action"),
                    i18n("toggle_resolved")
                )
            )))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        if (enabled) item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, EXTENSION_ID)
        ItemTag.setString(item, "chanpon_action", "toggle_tool")
        ItemTag.setString(item, "chanpon_tool", definition.id)
        return item
    }

    private fun i18n(key: String): String {
        return CCSystem.getAPI().getI18nString(null, "chanpon.member_lore.permission_menu.$key")
    }
}
