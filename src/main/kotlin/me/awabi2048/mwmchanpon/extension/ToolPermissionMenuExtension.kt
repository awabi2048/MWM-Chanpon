package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory

class ToolPermissionMenuExtension(
    private val menu: ToolPermissionMenu,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>
) : MenuExtension {
    private val legacy = LegacyComponentSerializer.legacySection()
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
            val states = controller.getMemberToolStates(worldData, memberUuid)
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
        val lines = mutableListOf<GuiLoreLine>()

        val dateStr = if (offlinePlayer.lastSeen > 0) {
            Instant.ofEpochMilli(offlinePlayer.lastSeen)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        } else {
            i18n(player, "online")
        }

        lines.add(GuiLoreLine.Data(i18n(player, "last_online_label"), dateStr, "§f"))
        lines.add(GuiLoreLine.Spacer)

        val enabledTools = definitions.filter { states[it.id] == true }
        if (enabledTools.isEmpty()) {
            lines.add(GuiLoreLine.Data(i18n(player, "tool_permission_title"), i18n(player, "none"), "§f"))
        } else {
            lines.add(GuiLoreLine.Data(i18n(player, "tool_permission_title"), null, ""))
            enabledTools.forEach { def ->
                lines.add(GuiLoreLine.Raw("§8・${def.color}${def.displayName}"))
            }
        }

        val actionLines = mutableListOf<GuiLoreLine>(
            GuiLoreLine.Action(i18n(player, "click_edit_operation"), i18n(player, "click_edit_action")),
            GuiLoreLine.Spacer
        )
        if (memberUuid != worldData.owner) {
            // MWM 側の lore は既にフレーム用セパレータを含むため、Chanpon 側の GuiLoreBlock へ
            // そのまま取り込むと CC-System の不変条件に抵触する。末尾アクションブロックのみ抽出する。
            extractTrailingActionLines(originalLore).forEach { actionLines.add(GuiLoreLine.Raw(it)) }
        }

        return api.getLoreService().render(GuiLoreSpec.Blocks(listOf(
            GuiLoreBlock(lines),
            GuiLoreBlock(actionLines)
        )))
    }

    private fun extractTrailingActionLines(originalLore: List<Component>): List<String> {
        val serialized = originalLore.map { legacy.serialize(it) }
        val lastSeparator = serialized.indexOfLast(::isSeparatorLine)
        return serialized
            .drop(lastSeparator + 1)
            .filter { it.isNotBlank() && !isSeparatorLine(it) }
    }

    private fun isSeparatorLine(line: String): Boolean {
        val stripped = line.replace(Regex("[§&][0-9A-FK-ORa-fk-or]"), "").trim()
        return stripped.isNotBlank() && stripped.all { it == '─' || it == '━' || it == '-' }
    }
}
