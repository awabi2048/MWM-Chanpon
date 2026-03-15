package me.awabi2048.mwmchanpon.service

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.model.CreationMethod
import me.awabi2048.mwmchanpon.model.MyWorldData
import me.awabi2048.mwmchanpon.model.WizardSession
import me.awabi2048.mwmchanpon.ui.GuiComponents
import me.awabi2048.mwmchanpon.ui.GuiScreens
import me.awabi2048.mwmchanpon.ui.PersonalSettingsMenuItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.response.result.InvalidFormResponseResult
import org.geysermc.floodgate.api.FloodgateApi
import java.lang.Runnable
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class PlatformUiService(
    private val plugin: MWMChanponPlugin,
    private val messages: LocalizationService,
    private val playerSettingsService: PlayerSettingsService,
    private val sessionService: WizardSessionService,
    private val templateService: TemplateService,
    private val worldService: WorldService,
) {
    private val suppressedInventoryClose = ConcurrentHashMap.newKeySet<UUID>()

    fun openMethodSelection(player: Player) {
        val session = session(player) ?: return
        openInventory(player, GuiScreens.createMethodSelection(player, messages, templateService.getAll().isNotEmpty(), session.returnToMyWorldMenu))
    }

    fun openTemplateSelection(player: Player) {
        openInventory(player, GuiScreens.createTemplateSelection(player, messages, templateService.getAll()))
    }

    fun openConfirmation(player: Player) {
        val session = session(player) ?: return
        val templateName = session.templateKey?.let(templateService::get)?.name
        openInventory(player, GuiScreens.createConfirmation(player, messages, session, templateName))
    }

    fun openMyWorldList(player: Player, page: Int = 0, fromMenuCommand: Boolean = false) {
        openMenuInventory(
            player,
            GuiScreens.createMyWorldList(
                player = player,
                messages = messages,
                worlds = worldService.getAccessibleWorlds(player),
                page = page,
                canCreateNewWorld = worldService.canCreateMore(player),
                ownedWorldCount = worldService.getOwnedWorldCount(player.uniqueId),
                fromMenuCommand = fromMenuCommand,
            ),
        )
    }

    fun openPersonalSettings(player: Player, fromMenuCommand: Boolean) {
        val currentLanguage = playerSettingsService.getLanguage(player.uniqueId) ?: defaultLanguage(player)
        val items = listOf(
            PersonalSettingsMenuItem(
                material = Material.BOOK,
                displayName = messages.getString(player, "gui.personal_settings.language.display"),
                lore = messages.getStringList(player, "gui.personal_settings.language.description", mapOf("language" to currentLanguage)),
                actionText = messages.getString(player, "gui.personal_settings.language.action"),
            ),
        )
        openMenuInventory(player, GuiScreens.createPersonalSettings(player, messages, items, fromMenuCommand))
    }

    fun requestWorldName(player: Player) {
        val session = session(player) ?: return
        if (isBedrock(player)) {
            openBedrockTextForm(
                player = player,
                session = session,
                titleKey = "gui.creation.input.world_name.title",
                messageKey = "gui.creation.input.world_name.message",
                defaultValue = session.worldName.orEmpty(),
                onSubmit = { value ->
                    val error = worldService.validateWorldName(value)
                    if (error != null) {
                        player.sendMessage(error)
                        requestWorldName(player)
                    } else {
                        session.worldName = value
                        openConfirmation(player)
                    }
                },
                onBack = { backFromWorldName(player, session) },
            )
        } else {
            openJavaTextDialog(
                player = player,
                session = session,
                titleKey = "gui.creation.input.world_name.title",
                messageKey = "gui.creation.input.world_name.message",
                inputKey = "world_name",
                initialValue = session.worldName.orEmpty(),
                onSubmit = { value ->
                    val error = worldService.validateWorldName(value)
                    if (error != null) {
                        player.sendMessage(error)
                        requestWorldName(player)
                    } else {
                        session.worldName = value
                        openConfirmation(player)
                    }
                },
                onBack = { backFromWorldName(player, session) },
            )
        }
    }

    fun requestSeed(player: Player) {
        val session = session(player) ?: return
        if (isBedrock(player)) {
            openBedrockTextForm(
                player = player,
                session = session,
                titleKey = "gui.creation.input.seed.title",
                messageKey = "gui.creation.input.seed.message",
                defaultValue = session.seedText.orEmpty(),
                onSubmit = { value ->
                    session.seedText = value
                    requestWorldName(player)
                },
                onBack = { openMethodSelection(player) },
            )
        } else {
            openJavaTextDialog(
                player = player,
                session = session,
                titleKey = "gui.creation.input.seed.title",
                messageKey = "gui.creation.input.seed.message",
                inputKey = "world_seed",
                initialValue = session.seedText.orEmpty(),
                onSubmit = { value ->
                    session.seedText = value
                    requestWorldName(player)
                },
                onBack = { openMethodSelection(player) },
            )
        }
    }

    fun confirmCreation(player: Player) {
        val session = session(player) ?: return
        suppressNextInventoryClose(player)
        player.closeInventory()
        player.sendMessage(messages.getString(player, "messages.creation_in_progress"))
        worldService.createWorld(session) { result ->
            result.onSuccess {
                sessionService.clear(player.uniqueId)
                playWorldCreateSound(player)
                player.sendMessage(messages.getString(player, "messages.creation_success", mapOf("name" to it.name)))
            }.onFailure { error ->
                plugin.logger.warning("ワールド作成に失敗しました: ${error.message}")
                player.sendMessage(messages.getString(player, "messages.creation_failed", mapOf("reason" to (error.message ?: "unknown"))))
            }
        }
    }

    fun cancel(player: Player) {
        val currentSession = sessionService.getSession(player.uniqueId)
        sessionService.clear(player.uniqueId)
        if (currentSession?.returnToMyWorldMenu == true) {
            openMyWorldList(player, fromMenuCommand = currentSession.returnToMenuCommand)
            return
        }
        suppressNextInventoryClose(player)
        player.closeInventory()
        player.sendMessage(messages.getString(player, "messages.cancelled"))
    }

    fun handleInventoryClosed(player: Player) {
        if (suppressedInventoryClose.remove(player.uniqueId)) {
            return
        }
        if (sessionService.getSession(player.uniqueId) != null) {
            sessionService.clear(player.uniqueId)
            player.sendMessage(messages.getString(player, "gui.common.close_message"))
        }
    }

    fun handleMethodSelection(player: Player, slot: Int) {
        val session = session(player) ?: return
        when (slot) {
            20 -> {
                if (templateService.getAll().isEmpty()) {
                    return
                }
                playClickSound(player)
                session.creationMethod = CreationMethod.TEMPLATE
                openTemplateSelection(player)
            }

            22 -> {
                playClickSound(player)
                session.creationMethod = CreationMethod.RANDOM
                session.seedText = null
                requestWorldName(player)
            }

            24 -> {
                playClickSound(player)
                session.creationMethod = CreationMethod.SEED
                requestSeed(player)
            }

            40 -> {
                playClickSound(player)
                cancel(player)
            }
        }
    }

    fun handleTemplateSelection(player: Player, slot: Int) {
        val session = session(player) ?: return
        if (slot == 49) {
            playClickSound(player)
            openMethodSelection(player)
            return
        }

        val template = templateService.getAll().take(28).mapIndexed { index, template ->
            val row = index / 7
            val column = index % 7
            (10 + column + row * 9) to template
        }.toMap()[slot] ?: return

        playClickSound(player)
        session.creationMethod = CreationMethod.TEMPLATE
        session.templateKey = template.key
        requestWorldName(player)
    }

    fun handleConfirmation(player: Player, slot: Int) {
        when (slot) {
            22 -> {
                playClickSound(player)
                confirmCreation(player)
            }

            40 -> {
                playClickSound(player)
                requestWorldName(player)
            }
        }
    }

    fun handleMyWorldListClick(player: Player, slot: Int, click: ClickType, worldIds: List<String>, page: Int, fromMenuCommand: Boolean) {
        val bodyRows = maxOf(1, (worldIds.size + 6) / 7)
        val footerBase = GuiComponents.footerBase(bodyRows)
        when (slot) {
            footerBase -> {
                if (!fromMenuCommand) {
                    return
                }
                playClickSound(player)
                executeConfiguredMenuCommand(player)
                return
            }

            footerBase + 2 -> {
                if (!worldService.canCreateMore(player)) {
                    return
                }
                playClickSound(player)
                sessionService.startSession(player.uniqueId, player.uniqueId).apply {
                    returnToMyWorldMenu = true
                    returnToMenuCommand = fromMenuCommand
                }
                openMethodSelection(player)
                return
            }

            footerBase + 6 -> {
                playClickSound(player)
                openPersonalSettings(player, fromMenuCommand)
                return
            }

            footerBase + 7 -> {
                playClickSound(player)
                openMyWorldList(player, page - 1, fromMenuCommand)
                return
            }

            footerBase + 8 -> {
                playClickSound(player)
                openMyWorldList(player, page + 1, fromMenuCommand)
                return
            }
        }

        val slotIndex = GuiComponents.centeredBodySlots(bodyRows).indexOf(slot)
        if (slotIndex == -1 || slotIndex >= worldIds.size) {
            return
        }

        val worldData = worldService.getAccessibleWorlds(player).firstOrNull { it.uuid.toString() == worldIds[slotIndex] } ?: return
        when {
            click.isLeftClick -> teleportToMyWorld(player, worldData)
            click.isRightClick -> {
                playClickSound(player)
                player.sendMessage(messages.getString(player, "messages.world_settings_mock", mapOf("name" to worldData.name)))
            }
        }
    }

    fun handlePersonalSettingsClick(player: Player, slot: Int, fromMenuCommand: Boolean) {
        val bodyRows = 1
        when (slot) {
            GuiComponents.centeredBodySlots(bodyRows).first() -> {
                rotateLanguage(player)
                openPersonalSettings(player, fromMenuCommand)
            }

            GuiComponents.footerBase(bodyRows) + 4 -> {
                playClickSound(player)
                openMyWorldList(player, fromMenuCommand = fromMenuCommand)
            }
        }
    }

    private fun session(player: Player): WizardSession? {
        return sessionService.getSession(player.uniqueId) ?: run {
            player.sendMessage(messages.getString(player, "gui.common.expired_message"))
            null
        }
    }

    private fun backFromWorldName(player: Player, session: WizardSession) {
        when (session.creationMethod) {
            CreationMethod.TEMPLATE -> openTemplateSelection(player)
            CreationMethod.RANDOM -> openMethodSelection(player)
            CreationMethod.SEED -> requestSeed(player)
            null -> openMethodSelection(player)
        }
    }

    private fun executeConfiguredMenuCommand(player: Player) {
        val command = plugin.config.getString("menu_command", "mwm") ?: "mwm"
        player.performCommand(command)
    }

    private fun rotateLanguage(player: Player) {
        playClickSound(player)
        val languages = playerSettingsService.getSupportedLanguages()
        val current = playerSettingsService.getLanguage(player.uniqueId) ?: defaultLanguage(player)
        val next = languages[(languages.indexOf(current).takeIf { it >= 0 } ?: -1).let { (it + 1) % languages.size }]
        playerSettingsService.setLanguage(player.uniqueId, next)
        player.sendMessage(messages.getString(player, "messages.language_updated", mapOf("language" to next)))
    }

    private fun defaultLanguage(player: Player): String = player.locale().toLanguageTag().replace('-', '_').lowercase()

    private fun isBedrock(player: Player): Boolean {
        val floodgatePlugin = Bukkit.getPluginManager().getPlugin("floodgate") ?: return false
        if (!floodgatePlugin.isEnabled) {
            return false
        }
        return runCatching { FloodgateApi.getInstance().isFloodgatePlayer(player.uniqueId) }.getOrDefault(false)
    }

    private fun openInventory(player: Player, inventory: org.bukkit.inventory.Inventory) {
        suppressNextInventoryClose(player)
        player.openInventory(inventory)
    }

    private fun openMenuInventory(player: Player, inventory: org.bukkit.inventory.Inventory) {
        player.openInventory(inventory)
    }

    private fun suppressNextInventoryClose(player: Player) {
        suppressedInventoryClose += player.uniqueId
    }

    private fun openBedrockTextForm(
        player: Player,
        session: WizardSession,
        titleKey: String,
        messageKey: String,
        defaultValue: String,
        onSubmit: (String) -> Unit,
        onBack: () -> Unit,
    ) {
        val floodgateApi = runCatching { FloodgateApi.getInstance() }.getOrNull()
        if (floodgateApi == null) {
            player.sendMessage(messages.getString(player, "gui.common.offline_target"))
            return
        }

        suppressNextInventoryClose(player)
        player.closeInventory()

        val form = CustomForm.builder()
            .title(messages.getString(player, titleKey))
            .label(messages.getString(player, messageKey))
            .input(messages.getString(player, titleKey.replace(".title", ".label")), "", defaultValue)
            .validResultHandler { response ->
                val input = response.asInput(0)?.trim().orEmpty()
                if (input.isBlank()) {
                    player.sendMessage(messages.getString(player, "messages.value_required"))
                    requestSameInput(player, titleKey)
                    return@validResultHandler
                }
                onSubmit(input)
            }
            .closedResultHandler(Runnable { onBack() })
            .invalidResultHandler(Consumer<InvalidFormResponseResult<org.geysermc.cumulus.response.CustomFormResponse>> { onBack() })
            .build()

        floodgateApi.sendForm(player.uniqueId, form)
    }

    private fun requestSameInput(player: Player, titleKey: String) {
        when (titleKey) {
            "gui.creation.input.world_name.title" -> requestWorldName(player)
            "gui.creation.input.seed.title" -> requestSeed(player)
        }
    }

    private fun openJavaTextDialog(
        player: Player,
        session: WizardSession,
        titleKey: String,
        messageKey: String,
        inputKey: String,
        initialValue: String,
        onSubmit: (String) -> Unit,
        onBack: () -> Unit,
    ) {
        suppressNextInventoryClose(player)
        player.closeInventory()

        val options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()
        val submitButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.submit")))
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val value = response.getText(inputKey)?.trim().orEmpty()
                val target = audience as? Player ?: return@customClick
                if (value.isBlank()) {
                    target.sendMessage(messages.getString(target, "messages.value_required"))
                    requestSameInput(target, titleKey)
                    return@customClick
                }
                onSubmit(value)
            }, options))
            .build()
        val backButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.back")))
            .width(120)
            .action(DialogAction.customClick({ _, audience ->
                val target = audience as? Player ?: return@customClick
                onBack()
            }, options))
            .build()

        val dialog = Dialog.create { factory ->
            factory.empty().base(
                DialogBase.builder(Component.text(messages.getString(player, titleKey)))
                    .canCloseWithEscape(false)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(listOf(DialogBody.plainMessage(Component.text(messages.getString(player, messageKey)), 240)))
                    .inputs(listOf(DialogInput.text(inputKey, 240, Component.text(messages.getString(player, titleKey.replace(".title", ".label"))), true, initialValue, 64, null)))
                    .build(),
            ).type(DialogType.multiAction(listOf(submitButton, backButton), null, 2))
        }

        player.showDialog(dialog)
    }

    private fun playClickSound(player: Player) {
        val sound = Sound.valueOf(plugin.config.getString("sounds.global_click.sound", "UI_BUTTON_CLICK") ?: "UI_BUTTON_CLICK")
        val pitch = plugin.config.getDouble("sounds.global_click.pitch", 2.0).toFloat()
        player.playSound(player.location, sound, 1.0f, pitch)
    }

    private fun playWorldCreateSound(player: Player) {
        val sound = Sound.valueOf(plugin.config.getString("sounds.world_create.sound", "BLOCK_PORTAL_TRIGGER") ?: "BLOCK_PORTAL_TRIGGER")
        val pitch = plugin.config.getDouble("sounds.world_create.pitch", 1.0).toFloat()
        player.playSound(player.location, sound, 1.0f, pitch)
    }

    private fun teleportToMyWorld(player: Player, worldData: MyWorldData) {
        playClickSound(player)
        worldService.teleportToWorld(player, worldData)
            .onSuccess {
                player.sendMessage(messages.getString(player, "messages.teleport_success", mapOf("name" to worldData.name)))
            }
            .onFailure {
                player.sendMessage(messages.getString(player, "messages.teleport_failed", mapOf("reason" to (it.message ?: "unknown"))))
            }
    }
}
