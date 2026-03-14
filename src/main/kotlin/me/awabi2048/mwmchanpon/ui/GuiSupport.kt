package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.service.LocalizationService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

private val legacySerializer = LegacyComponentSerializer.legacySection()

data class LoreAction(val operation: String? = null, val action: String)

class GuiLoreBuilder(
    private val messages: LocalizationService,
    private val player: Player?,
) {
    private val blocks = mutableListOf<List<String>>()
    private var singleAction: String? = null
    private val warnings = mutableListOf<String>()

    fun block(lines: List<String>): GuiLoreBuilder {
        val normalized = lines.filter { it.isNotBlank() }.map { if (it.startsWith("§")) it else "§7$it" }
        if (normalized.isNotEmpty()) {
            blocks += normalized
        }
        return this
    }

    fun singleAction(action: String): GuiLoreBuilder {
        singleAction = messages.getString(player, "gui.common.action_single", mapOf("action" to action))
        return this
    }

    fun multiActions(actions: List<LoreAction>): GuiLoreBuilder {
        if (actions.isNotEmpty()) {
            blocks += actions.map {
                messages.getString(
                    player,
                    "gui.common.action_multi",
                    mapOf("operation" to (it.operation ?: ""), "action" to it.action),
                )
            }
        }
        return this
    }

    fun warning(text: String): GuiLoreBuilder {
        warnings += messages.getString(player, "gui.common.warning", mapOf("content" to text))
        return this
    }

    fun build(): List<Component> {
        val separator = messages.getString(player, "gui.common.separator")
        val lines = mutableListOf<Component>()
        if (blocks.isNotEmpty()) {
            lines += legacy(separator)
            blocks.forEachIndexed { index, block ->
                lines += block.map(::legacy)
                if (index != blocks.lastIndex) {
                    lines += legacy(separator)
                }
            }
            if (singleAction == null) {
                lines += legacy(separator)
            }
        }
        singleAction?.let { lines += legacy(it) }
        lines += warnings.map(::legacy)
        return lines
    }
}

object GuiComponents {
    fun title(text: String): Component = legacy(text)

    fun plainTitle(text: String): String = LegacyComponentSerializer.legacySection().serialize(legacy(text))

    fun background(material: Material): ItemStack {
        return ItemStack(material).apply {
            itemMeta = itemMeta.apply {
                displayName(legacy(" "))
                setHideTooltip(true)
            }
        }
    }

    fun button(material: Material, displayName: String, lore: List<Component>): ItemStack {
        return ItemStack(material).apply {
            itemMeta = itemMeta.apply {
                this.displayName(legacy(displayName))
                this.lore(lore)
            }
        }
    }

    fun backButton(messages: LocalizationService, player: Player?, cancel: Boolean, actionText: String): ItemStack {
        val key = if (cancel) "gui.common.cancel" else "gui.common.back"
        return button(
            Material.REDSTONE,
            messages.getString(player, key),
            GuiLoreBuilder(messages, player).singleAction(actionText).build(),
        )
    }

    fun decorate(inventory: Inventory) {
        val header = background(Material.BLACK_STAINED_GLASS_PANE)
        val body = background(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0 until inventory.size) {
            inventory.setItem(slot, body)
        }
        for (slot in 0..8) {
            inventory.setItem(slot, header)
        }
        for (slot in inventory.size - 9 until inventory.size) {
            inventory.setItem(slot, header)
        }
    }

    fun createInventory(holder: InventoryHolder, size: Int, title: String): Inventory {
        return org.bukkit.Bukkit.createInventory(holder, size, plainTitle(title))
    }

    private fun legacy(raw: String): Component {
        return legacySerializer.deserialize(raw)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, false)
    }
}

private fun legacy(raw: String): Component = legacySerializer.deserialize(raw)
    .decoration(TextDecoration.ITALIC, false)
    .decoration(TextDecoration.BOLD, false)
