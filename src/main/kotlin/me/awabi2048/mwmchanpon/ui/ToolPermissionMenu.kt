package me.awabi2048.mwmchanpon.ui

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

        val frameTop = createFrame(Material.BLACK_STAINED_GLASS_PANE)
        val frameBg = createFrame(Material.GRAY_STAINED_GLASS_PANE)

        for (slot in 0..8) inventory.setItem(slot, frameTop)
        for (slot in 9..35) inventory.setItem(slot, frameBg)
        for (slot in 36..44) inventory.setItem(slot, frameTop)

        val targetPlayer = Bukkit.getOfflinePlayer(targetPlayerUuid)
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta { meta ->
            if (meta is org.bukkit.inventory.meta.SkullMeta) {
                meta.owningPlayer = targetPlayer
            }
            meta.displayName(
                Component.text("§e${targetPlayer.name ?: "Unknown"}")
                    .decoration(TextDecoration.ITALIC, false)
            )
        }
        inventory.setItem(13, head)

        val toolSlots = listOf(20, 22, 24)
        definitions.take(toolSlots.size).forEachIndexed { index, definition ->
            inventory.setItem(toolSlots[index], createToolItem(worldData, targetPlayerUuid, definition))
        }

        val back = ItemStack(Material.REDSTONE)
        back.editMeta { meta ->
            meta.displayName(Component.text("§7戻る").decoration(TextDecoration.ITALIC, false))
        }
        ItemTag.tagItem(back, ItemTag.TYPE_GUI_RETURN)
        inventory.setItem(31, back)

        player.openInventory(inventory)
    }

    private fun createFrame(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            meta.displayName(Component.text(" "))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun createToolItem(worldData: WorldData, targetPlayerUuid: UUID, definition: ToolPermissionDefinition): ItemStack {
        val enabled = controller.isEnabled(worldData, targetPlayerUuid, definition.id)
        val item = ItemStack(definition.material)
        item.editMeta { meta ->
            meta.displayName(
                Component.text("${if (enabled) "§a" else "§7"}${definition.displayName}")
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(
                listOf(
                    Component.text("§7状態: ${if (enabled) "§aオン" else "§cオフ"}").decoration(TextDecoration.ITALIC, false),
                    Component.text("§eクリックで切り替え").decoration(TextDecoration.ITALIC, false)
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        if (enabled) item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setExtensionId(item, EXTENSION_ID)
        ItemTag.setString(item, "chanpon_action", "toggle_tool")
        ItemTag.setString(item, "chanpon_tool", definition.id)
        return item
    }
}
