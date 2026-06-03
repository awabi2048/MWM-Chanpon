package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.mwmchanpon.ui.ToolPermissionMenu
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MenuExtension
import me.awabi2048.myworldmanager.api.extension.MenuExtensionContext
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory

class ToolPermissionMenuExtension(
    private val menu: ToolPermissionMenu,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>
) : MenuExtension {
    override fun getId(): String = ToolPermissionMenu.EXTENSION_ID

    override fun onRender(inventory: Inventory, player: Player, context: MenuExtensionContext) {
        if (context.menuType != "member_management") return
        val worldData = context.get<WorldData>("worldData") ?: return
        if (worldData.owner != player.uniqueId) return

        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (ItemTag.getType(item) != ItemTag.TYPE_GUI_MEMBER_ITEM) continue

            val memberUuid = ItemTag.getWorldUuid(item) ?: continue
            val originalLore = item.itemMeta.lore() ?: emptyList()
            val states = controller.getMemberToolStates(worldData.uuid, memberUuid)
            val newLore = rebuildLore(player, worldData, memberUuid, originalLore, states)
            val meta = item.itemMeta
            meta.lore(newLore)
            item.itemMeta = meta
            inventory.setItem(slot, item)
        }
    }

    override fun onClick(event: InventoryClickEvent, player: Player, context: MenuExtensionContext): Boolean {
        if (context.menuType != "member_management") return false
        val item = event.currentItem ?: return false
        if (ItemTag.getType(item) != ItemTag.TYPE_GUI_MEMBER_ITEM || !event.isLeftClick || event.isShiftClick) return false

        if (player.uniqueId != (context.get<WorldData>("worldData")?.owner ?: return false)) return false

        val memberUuid = ItemTag.getWorldUuid(item) ?: return false
        val worldData = context.get<WorldData>("worldData") ?: return false

        menu.open(player, worldData, memberUuid) {
            MyWorldManagerApi.openMemberManagementMenu(player, worldData.uuid)
        }
        return true
    }

    private fun i18n(player: Player, key: String): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.member_lore.$key")
    }

    private fun rebuildLore(
        player: Player,
        worldData: WorldData,
        memberUuid: UUID,
        originalLore: List<Component>,
        states: Map<String, Boolean>
    ): List<Component> {
        val api = CCSystem.getAPI()
        val offlinePlayer = Bukkit.getOfflinePlayer(memberUuid)
        val allLines = mutableListOf<String>()

        val dateStr = if (offlinePlayer.lastSeen > 0) {
            Instant.ofEpochMilli(offlinePlayer.lastSeen)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        } else {
            i18n(player, "online")
        }

        allLines.add(api.createLoreDataLine(i18n(player, "last_online_label"), dateStr, "§f"))
        allLines.add("")

        val enabledTools = definitions.filter { states[it.id] == true }
        if (enabledTools.isEmpty()) {
            allLines.add("§f❙ §7${i18n(player, "tool_permission_title")} §f${i18n(player, "none")}")
        } else {
            allLines.add("§f❙ §7${i18n(player, "tool_permission_title")}")
            enabledTools.forEach { def ->
                allLines.add("§8・${def.color}${def.displayName}")
            }
        }

        allLines.add(api.createLoreSeparator(allLines))
        allLines.add(api.createLoreActionLine(i18n(player, "click_edit_operation"), i18n(player, "click_edit_action")))
        allLines.add("")

        val shiftLines = if (memberUuid != worldData.owner) {
            val plain = PlainTextComponentSerializer.plainText()
            originalLore.takeLast(2).map { plain.serialize(it) }
        } else {
            emptyList()
        }
        allLines.addAll(shiftLines)

        return api.buildLore(allLines, closingSeparator = true)
    }
}
