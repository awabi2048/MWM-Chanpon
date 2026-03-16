package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.model.MyWorldData
import me.awabi2048.mwmchanpon.model.WorldPublishLevel
import me.awabi2048.mwmchanpon.service.LocalizationService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

private val legacySerializer = LegacyComponentSerializer.legacySection()

data class LoreAction(val operation: String? = null, val action: String)

private data class LoreSection(
    val lines: List<Component>,
    val showClosingSeparator: Boolean = true,
)

class GuiLoreBuilder(
    private val messages: LocalizationService,
    private val player: Player?,
) {
    private val sections = mutableListOf<LoreSection>()

    private fun addSection(lines: List<Component>, showClosingSeparator: Boolean = true) {
        val normalized = lines.filter { it != Component.empty() }
        if (normalized.isNotEmpty()) {
            sections += LoreSection(normalized, showClosingSeparator)
        }
    }

    fun data(name: String, value: String): GuiLoreBuilder {
        addSection(listOf(legacy("§f§l| §7$name $value")))
        return this
    }

    fun data(entries: List<Pair<String, String>>): GuiLoreBuilder {
        if (entries.isNotEmpty()) {
            addSection(entries.map { (name, value) -> legacy("§f§l| §7$name $value") })
        }
        return this
    }

    fun block(lines: List<String>): GuiLoreBuilder {
        val normalized = lines
            .flatMap { it.split('\n') }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("§")) legacy(it) else legacy("§7$it") }
        addSection(normalized)
        return this
    }

    fun componentBlock(lines: List<Component>): GuiLoreBuilder {
        addSection(lines)
        return this
    }

    fun singleAction(action: String): GuiLoreBuilder {
        addSection(listOf(legacy(messages.getString(player, "gui.common.action_single", mapOf("action" to action)))), showClosingSeparator = false)
        return this
    }

    fun multiActions(actions: List<LoreAction>): GuiLoreBuilder {
        if (actions.isNotEmpty()) {
            addSection(
                actions.map {
                    legacy(
                        messages.getString(
                            player,
                            "gui.common.action_multi",
                            mapOf("operation" to (it.operation ?: ""), "action" to it.action),
                        ),
                    )
                },
            )
        }
        return this
    }

    fun warning(text: String): GuiLoreBuilder {
        addSection(listOf(legacy(messages.getString(player, "gui.common.warning", mapOf("content" to text)))))
        return this
    }

    fun build(): List<Component> {
        if (sections.isEmpty()) {
            return emptyList()
        }

        val separator = legacy(messages.getString(player, "gui.common.separator"))
        return buildList {
            sections.forEach { section ->
                add(separator)
                addAll(section.lines)
            }
            if (sections.last().showClosingSeparator) {
                add(separator)
            }
        }
    }
}

object GuiComponents {
    fun title(text: String): Component = legacy(text)

    fun legacyText(text: String): Component = legacy(text)

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
        return backButton(messages.getString(player, key))
    }

    fun backButton(displayName: String): ItemStack {
        return ItemStack(Material.REDSTONE).apply {
            itemMeta = itemMeta.apply {
                this.displayName(legacy(displayName))
                lore(emptyList())
            }
        }
    }

    fun centeredBodySlots(bodyRows: Int): List<Int> {
        return (1..bodyRows).flatMap { row -> (1..7).map { column -> row * 9 + column } }
    }

    fun inventorySizeForBodyRows(bodyRows: Int): Int = (bodyRows + 2) * 9

    fun footerBase(bodyRows: Int): Int = (bodyRows + 1) * 9

    fun worldIconButton(messages: LocalizationService, player: Player?, worldData: MyWorldData, showActions: Boolean = true): ItemStack {
        val ownerName = Bukkit.getOfflinePlayer(worldData.owner).name ?: worldData.owner.toString()
        val loreBuilder = GuiLoreBuilder(messages, player)
            .block(listOf("§a(${formatSourceWorld(messages, player, worldData.sourceWorld)})", "§f${worldData.name}"))
            .data(
                listOf(
                    messages.getString(player, "gui.myworld.world_icon.data.owner") to "§f$ownerName",
                    messages.getString(player, "gui.myworld.world_icon.data.publish") to publishLevelText(messages, player, worldData.publishLevel),
                ),
            )
        if (showActions) {
            loreBuilder.multiActions(
                listOf(
                    LoreAction(messages.getString(player, "gui.myworld.world_icon.actions.left_click"), messages.getString(player, "gui.myworld.world_icon.actions.teleport")),
                    LoreAction(messages.getString(player, "gui.myworld.world_icon.actions.right_click"), messages.getString(player, "gui.myworld.world_icon.actions.settings")),
                ),
            )
        }
        return button(
            worldData.icon,
            "§f${worldData.name}",
            loreBuilder.build(),
        )
    }

    fun materialNameComponent(material: Material): Component {
        return Component.translatable(material.translationKey()).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
    }

    fun materialNameText(material: Material): String {
        return LegacyComponentSerializer.legacySection().serialize(materialNameComponent(material))
    }

    fun playerHeadButton(player: OfflinePlayer, displayName: String, lore: List<Component>): ItemStack {
        return ItemStack(Material.PLAYER_HEAD).apply {
            itemMeta = (itemMeta as SkullMeta).apply {
                owningPlayer = player
                this.displayName(legacy(displayName))
                this.lore(lore)
            }
        }
    }

    private fun formatSourceWorld(messages: LocalizationService, player: Player?, sourceWorld: String): String {
        return when {
            sourceWorld == "random" -> messages.getString(player, "gui.myworld.world_icon.source.random")
            sourceWorld.startsWith("template:") -> messages.getString(
                player,
                "gui.myworld.world_icon.source.template",
                mapOf("name" to sourceWorld.removePrefix("template:")),
            )
            sourceWorld.startsWith("seed:") -> messages.getString(player, "gui.myworld.world_icon.source.seed")
            else -> sourceWorld
        }
    }

    fun publishLevelText(messages: LocalizationService, player: Player?, level: WorldPublishLevel): String {
        return when (level) {
            WorldPublishLevel.PUBLIC -> messages.getString(player, "gui.myworld.world_icon.publish.public")
            WorldPublishLevel.WIP -> messages.getString(player, "gui.myworld.world_icon.publish.wip")
            WorldPublishLevel.CLOSED -> messages.getString(player, "gui.myworld.world_icon.publish.closed")
        }
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
