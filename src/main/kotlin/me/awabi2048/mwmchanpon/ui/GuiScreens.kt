package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.model.CreationMethod
import me.awabi2048.mwmchanpon.model.TemplateDefinition
import me.awabi2048.mwmchanpon.model.WizardSession
import me.awabi2048.mwmchanpon.service.LocalizationService
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

sealed class WizardInventoryHolder : InventoryHolder {
    protected lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}

class MethodSelectionHolder(title: String) : WizardInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 45, title)
    }
}

class TemplateSelectionHolder(title: String) : WizardInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 54, title)
    }
}

class ConfirmationHolder(title: String) : WizardInventoryHolder() {
    init {
        backingInventory = GuiComponents.createInventory(this, 45, title)
    }
}

object GuiScreens {
    fun createMethodSelection(player: Player, messages: LocalizationService, hasTemplates: Boolean): Inventory {
        val holder = MethodSelectionHolder(messages.getString(player, "gui.creation.method.title"))
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
        holder.inventory.setItem(40, GuiComponents.backButton(messages, player, true, messages.getString(player, "gui.creation.method.cancel_action")))
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
        holder.inventory.setItem(49, GuiComponents.backButton(messages, player, false, messages.getString(player, "gui.creation.template.back_action")))
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
        holder.inventory.setItem(40, GuiComponents.backButton(messages, player, false, messages.getString(player, "gui.creation.confirm.back_action")))
        return holder.inventory
    }
}
