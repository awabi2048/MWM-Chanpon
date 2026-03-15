package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.model.CreationMethod
import me.awabi2048.mwmchanpon.model.MyWorldData
import me.awabi2048.mwmchanpon.model.TemplateDefinition
import me.awabi2048.mwmchanpon.model.WizardSession
import me.awabi2048.mwmchanpon.service.LocalizationService
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

sealed class GuiInventoryHolder : InventoryHolder {
    protected lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}

class MethodSelectionHolder(title: String, size: Int) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, size, title)
    }
}

class TemplateSelectionHolder(title: String) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 54, title)
    }
}

class ConfirmationHolder(title: String) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 45, title)
    }
}

class MyWorldListHolder(title: String, size: Int, val page: Int, val worldIds: List<String>, val fromMenuCommand: Boolean) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, size, title)
    }
}

class PersonalSettingsHolder(title: String, size: Int, val fromMenuCommand: Boolean) : GuiInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, size, title)
    }
}

data class PersonalSettingsMenuItem(
    val material: Material,
    val displayName: String,
    val lore: List<String>,
    val actionText: String,
)

object GuiScreens {
    fun createMethodSelection(player: Player, messages: LocalizationService, hasTemplates: Boolean, returnToMyWorld: Boolean): Inventory {
        val holder = MethodSelectionHolder(messages.getString(player, "gui.creation.method.title"), 45)
        GuiComponents.decorate(holder.inventory)
        holder.inventory.setItem(
            20,
            GuiComponents.button(
                Material.CHEST,
                messages.getString(player, "gui.creation.method.template.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.creation.method.template.description"))
                    .apply {
                        if (hasTemplates) {
                            singleAction(messages.getString(player, "gui.creation.method.template.action"))
                        } else {
                            warning(messages.getString(player, "gui.creation.method.template.unavailable"))
                        }
                    }
                    .build(),
            ),
        )
        holder.inventory.setItem(
            22,
            GuiComponents.button(
                Material.GRASS_BLOCK,
                messages.getString(player, "gui.creation.method.random.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.creation.method.random.description"))
                    .singleAction(messages.getString(player, "gui.creation.method.random.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(
            24,
            GuiComponents.button(
                Material.MANGROVE_SIGN,
                messages.getString(player, "gui.creation.method.seed.display"),
                GuiLoreBuilder(messages, player)
                    .block(messages.getStringList(player, "gui.creation.method.seed.description"))
                    .singleAction(messages.getString(player, "gui.creation.method.seed.action"))
                    .build(),
            ),
        )
        val footerSlot = 40
        val displayName = if (returnToMyWorld) messages.getString(player, "gui.common.back") else messages.getString(player, "gui.common.cancel")
        holder.inventory.setItem(footerSlot, GuiComponents.backButton(displayName))
        return holder.inventory
    }

    fun createTemplateSelection(player: Player, messages: LocalizationService, templates: List<TemplateDefinition>): Inventory {
        val holder = TemplateSelectionHolder(messages.getString(player, "gui.creation.template.title"))
        GuiComponents.decorate(holder.inventory)
        templates.take(28).forEachIndexed { index, template ->
            val row = index / 7
            val column = index % 7
            val slot = 10 + column + row * 9
            holder.inventory.setItem(
                slot,
                GuiComponents.button(
                    template.icon,
                    template.name,
                    GuiLoreBuilder(messages, player)
                        .block(template.description.ifEmpty { listOf(template.path) })
                        .singleAction(messages.getString(player, "gui.creation.template.action"))
                        .build(),
                ),
            )
        }
        holder.inventory.setItem(49, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }

    fun createConfirmation(player: Player, messages: LocalizationService, session: WizardSession, templateName: String?): Inventory {
        val holder = ConfirmationHolder(messages.getString(player, "gui.creation.confirm.title"))
        GuiComponents.decorate(holder.inventory)
        val methodText = when (session.creationMethod) {
            CreationMethod.TEMPLATE -> messages.getString(player, "gui.creation.confirm.description_template", mapOf("template" to (templateName ?: session.templateKey.orEmpty())))
            CreationMethod.RANDOM -> messages.getString(player, "gui.creation.confirm.description_random")
            CreationMethod.SEED -> messages.getString(player, "gui.creation.confirm.description_seed", mapOf("seed" to session.seedText.orEmpty()))
            null -> ""
        }
        holder.inventory.setItem(
            22,
            GuiComponents.button(
                Material.BOOK,
                messages.getString(player, "gui.creation.confirm.display"),
                GuiLoreBuilder(messages, player)
                    .block(listOf(methodText, messages.getString(player, "gui.creation.confirm.name_line", mapOf("name" to session.worldName.orEmpty()))))
                    .singleAction(messages.getString(player, "gui.creation.confirm.action"))
                    .build(),
            ),
        )
        holder.inventory.setItem(40, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }

    fun createMyWorldList(
        player: Player,
        messages: LocalizationService,
        worlds: List<MyWorldData>,
        page: Int,
        canCreateNewWorld: Boolean,
        ownedWorldCount: Int,
        fromMenuCommand: Boolean,
    ): Inventory {
        val pageSize = 28
        val pageCount = ((worlds.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val normalizedPage = page.coerceIn(0, pageCount - 1)
        val pageWorlds = worlds.drop(normalizedPage * pageSize).take(pageSize)
        val bodyRows = maxOf(1, (pageWorlds.size + 6) / 7)
        val holder = MyWorldListHolder(
            title = messages.getString(player, "gui.myworld.list.title"),
            size = GuiComponents.inventorySizeForBodyRows(bodyRows),
            page = normalizedPage,
            worldIds = pageWorlds.map { it.uuid.toString() },
            fromMenuCommand = fromMenuCommand,
        )
        GuiComponents.decorate(holder.inventory)

        GuiComponents.centeredBodySlots(bodyRows).zip(pageWorlds).forEach { (slot, worldData) ->
            holder.inventory.setItem(slot, GuiComponents.worldIconButton(messages, player, worldData))
        }

        val footerBase = GuiComponents.footerBase(bodyRows)
        if (fromMenuCommand) {
            holder.inventory.setItem(footerBase, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        }
        if (canCreateNewWorld) {
            holder.inventory.setItem(
                footerBase + 2,
                GuiComponents.button(
                    Material.NETHER_STAR,
                    messages.getString(player, "gui.myworld.list.create.display"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.myworld.list.create.action"))
                        .build(),
                ),
            )
        }
        holder.inventory.setItem(
            footerBase + 4,
            GuiComponents.playerHeadButton(
                player,
                messages.getString(player, "gui.myworld.list.profile.display", mapOf("player" to player.name)),
                GuiLoreBuilder(messages, player)
                    .data(messages.getString(player, "gui.myworld.list.profile.owned"), "§f$ownedWorldCount")
                    .build(),
            ),
        )
        holder.inventory.setItem(
            footerBase + 6,
            GuiComponents.button(
                Material.WRITABLE_BOOK,
                messages.getString(player, "gui.personal_settings.title"),
                GuiLoreBuilder(messages, player)
                    .singleAction(messages.getString(player, "gui.myworld.list.personal_settings.action"))
                    .build(),
            ),
        )
        if (pageCount > 1 && normalizedPage > 0) {
            holder.inventory.setItem(
                footerBase + 7,
                GuiComponents.button(
                    Material.ARROW,
                    messages.getString(player, "gui.myworld.list.page.previous"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.myworld.list.page.previous_action"))
                        .build(),
                ),
            )
        }
        if (pageCount > 1 && normalizedPage < pageCount - 1) {
            holder.inventory.setItem(
                footerBase + 8,
                GuiComponents.button(
                    Material.SPECTRAL_ARROW,
                    messages.getString(player, "gui.myworld.list.page.next"),
                    GuiLoreBuilder(messages, player)
                        .singleAction(messages.getString(player, "gui.myworld.list.page.next_action"))
                        .build(),
                ),
            )
        }
        return holder.inventory
    }

    fun createPersonalSettings(
        player: Player,
        messages: LocalizationService,
        items: List<PersonalSettingsMenuItem>,
        fromMenuCommand: Boolean,
    ): Inventory {
        val bodyRows = maxOf(1, (items.size + 6) / 7)
        val holder = PersonalSettingsHolder(
            title = messages.getString(player, "gui.personal_settings.title"),
            size = GuiComponents.inventorySizeForBodyRows(bodyRows),
            fromMenuCommand = fromMenuCommand,
        )
        GuiComponents.decorate(holder.inventory)
        GuiComponents.centeredBodySlots(bodyRows).zip(items).forEach { (slot, item) ->
            holder.inventory.setItem(
                slot,
                GuiComponents.button(
                    item.material,
                    item.displayName,
                    GuiLoreBuilder(messages, player)
                        .block(item.lore)
                        .singleAction(item.actionText)
                        .build(),
                ),
            )
        }
        holder.inventory.setItem(GuiComponents.footerBase(bodyRows) + 4, GuiComponents.backButton(messages.getString(player, "gui.common.back")))
        return holder.inventory
    }
}
