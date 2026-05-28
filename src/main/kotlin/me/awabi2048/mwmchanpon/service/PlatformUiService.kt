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
import me.awabi2048.mwmchanpon.model.SerializableLocation
import me.awabi2048.mwmchanpon.model.WizardSession
import me.awabi2048.mwmchanpon.ui.GuiComponents
import me.awabi2048.mwmchanpon.ui.GuiScreens
import me.awabi2048.mwmchanpon.ui.MemberManagementEntry
import me.awabi2048.mwmchanpon.ui.MemberManagementEntryType
import me.awabi2048.mwmchanpon.ui.MemberInviteCancelConfirmHolder
import me.awabi2048.mwmchanpon.ui.MemberManagementHolder
import me.awabi2048.mwmchanpon.ui.MemberRemoveConfirmHolder
import me.awabi2048.mwmchanpon.ui.PersonalSettingsMenuItem
import me.awabi2048.mwmchanpon.ui.WorldEnvironmentHolder
import me.awabi2048.mwmchanpon.ui.WorldSettingsBackTarget
import me.awabi2048.mwmchanpon.ui.WorldSettingsHolder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitTask
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.response.result.InvalidFormResponseResult
import org.geysermc.floodgate.api.FloodgateApi
import java.lang.Runnable
import java.time.Duration
import java.util.Locale
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
    private val memberManagementService: MemberManagementService,
) {
    private val suppressedInventoryClose = ConcurrentHashMap.newKeySet<UUID>()
    private val iconSelectionWorlds = ConcurrentHashMap<UUID, UUID>()
    private val spawnSelectionStates = ConcurrentHashMap<UUID, SpawnSelectionState>()
    private val spawnPreviewTasks = ConcurrentHashMap<UUID, BukkitTask>()

    private data class SpawnSelectionState(
        val worldId: UUID,
        val guest: Boolean,
        val setBoth: Boolean,
        val backTarget: WorldSettingsBackTarget,
        val myWorldPage: Int,
    )

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

    fun openWorldSettings(
        player: Player,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget = WorldSettingsBackTarget.NONE,
        myWorldPage: Int = 0,
    ) {
        clearWorldSettingsState(player)
        openMenuInventory(player, GuiScreens.createWorldSettings(player, messages, worldData, backTarget, myWorldPage, isBedrock(player)))
    }

    fun openMemberManagement(
        player: Player,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget = WorldSettingsBackTarget.NONE,
        myWorldPage: Int = 0,
        page: Int = 0,
    ) {
        if (!memberManagementService.canView(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.worldmenu_no_access"))
            return
        }
        openMenuInventory(
            player,
            GuiScreens.createMemberManagement(
                player = player,
                messages = messages,
                worldData = worldData,
                memberManagementService = memberManagementService,
                backTarget = backTarget,
                myWorldPage = myWorldPage,
                page = page,
                isBedrock = isBedrock(player),
            ),
        )
    }

    private fun openWorldEnvironmentSettings(
        player: Player,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
    ) {
        if (!worldService.isPlayerInWorld(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.world_setting_requires_current_world"))
            return
        }
        openMenuInventory(player, GuiScreens.createWorldEnvironmentSettings(player, messages, worldData, backTarget, myWorldPage))
    }

    private fun openMemberRemoveConfirm(
        player: Player,
        worldData: MyWorldData,
        targetUuid: UUID,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
    ) {
        openMenuInventory(
            player,
            GuiScreens.createMemberRemoveConfirm(
                player = player,
                messages = messages,
                worldData = worldData,
                targetUuid = targetUuid,
                backTarget = backTarget,
                myWorldPage = myWorldPage,
                page = page,
            ),
        )
    }

    private fun openMemberInviteCancelConfirm(
        player: Player,
        worldData: MyWorldData,
        targetUuid: UUID,
        inviteId: UUID,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
    ) {
        openMenuInventory(
            player,
            GuiScreens.createMemberInviteCancelConfirm(
                player = player,
                messages = messages,
                worldData = worldData,
                targetUuid = targetUuid,
                inviteId = inviteId,
                backTarget = backTarget,
                myWorldPage = myWorldPage,
                page = page,
            ),
        )
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

    fun handleMemberManagementClick(player: Player, event: InventoryClickEvent, holder: MemberManagementHolder) {
        val worldData = worldService.getWorldByUuid(UUID.fromString(holder.worldId)) ?: run {
            player.sendMessage(messages.getString(player, "messages.world_not_found"))
            player.closeInventory()
            return
        }
        if (!memberManagementService.canView(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.worldmenu_no_access"))
            player.closeInventory()
            return
        }

        event.isCancelled = true
        if (event.clickedInventory == player.inventory) {
            return
        }

        val footerBase = GuiComponents.footerBase(holder.bodyRows)
        when (event.rawSlot) {
            footerBase -> {
                playClickSound(player)
                openWorldSettings(player, worldData, holder.backTarget, holder.myWorldPage)
            }

            footerBase + 1 -> {
                if (holder.page > 0) {
                    playClickSound(player)
                    openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage, holder.page - 1)
                }
            }

            footerBase + 8 -> {
                val totalPages = ((memberManagementService.buildEntries(worldData).size + 27) / 28).coerceAtLeast(1)
                if (holder.page < totalPages - 1) {
                    playClickSound(player)
                    openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage, holder.page + 1)
                }
            }

            footerBase + 4 -> {
                if (!memberManagementService.canManage(player, worldData)) {
                    player.sendMessage(messages.getString(player, "messages.worldmenu_no_access"))
                    return
                }
                playClickSound(player)
                if (event.click.isShiftClick && memberManagementService.canForceAdd(player)) {
                    requestMemberInviteTarget(player, worldData, holder.backTarget, holder.myWorldPage, holder.page, forceAdd = true)
                } else {
                    requestMemberInviteTarget(player, worldData, holder.backTarget, holder.myWorldPage, holder.page, forceAdd = false)
                }
            }

            else -> {
                val entry = holder.slotEntries[event.rawSlot] ?: return
                if (!memberManagementService.canManage(player, worldData)) {
                    return
                }
                when (entry.type) {
                    MemberManagementEntryType.MEMBER -> openMemberRemoveConfirm(player, worldData, entry.playerUuid, holder.backTarget, holder.myWorldPage, holder.page)
                    MemberManagementEntryType.INVITE -> openMemberInviteCancelConfirm(player, worldData, entry.playerUuid, entry.inviteId ?: return, holder.backTarget, holder.myWorldPage, holder.page)
                    MemberManagementEntryType.OWNER -> Unit
                }
            }
        }
    }

    fun handleMemberRemoveConfirmClick(player: Player, event: InventoryClickEvent, holder: MemberRemoveConfirmHolder) {
        val worldData = worldService.getWorldByUuid(UUID.fromString(holder.worldId)) ?: run {
            player.sendMessage(messages.getString(player, "messages.world_not_found"))
            player.closeInventory()
            return
        }
        event.isCancelled = true
        if (event.clickedInventory == player.inventory) {
            return
        }
        when (event.rawSlot) {
            11 -> {
                playClickSound(player)
                openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage, holder.page)
            }

            15 -> {
                playClickSound(player)
                val targetUuid = UUID.fromString(holder.targetUuid)
                val result = memberManagementService.removeMember(player, worldData, targetUuid)
                result.onSuccess { updated ->
                    openMemberManagement(player, updated, holder.backTarget, holder.myWorldPage, holder.page)
                }.onFailure { error ->
                    player.sendMessage(error.message ?: messages.getString(player, "messages.worldmenu_no_access"))
                    openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage, holder.page)
                }
            }
        }
    }

    fun handleMemberInviteCancelConfirmClick(player: Player, event: InventoryClickEvent, holder: MemberInviteCancelConfirmHolder) {
        val worldData = worldService.getWorldByUuid(UUID.fromString(holder.worldId)) ?: run {
            player.sendMessage(messages.getString(player, "messages.world_not_found"))
            player.closeInventory()
            return
        }
        event.isCancelled = true
        if (event.clickedInventory == player.inventory) {
            return
        }
        when (event.rawSlot) {
            11 -> {
                playClickSound(player)
                openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage, holder.page)
            }

            15 -> {
                playClickSound(player)
                val inviteId = UUID.fromString(holder.inviteId)
                val result = memberManagementService.cancelInvite(player, worldData, inviteId)
                result.onSuccess {
                    openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage, holder.page)
                }.onFailure { error ->
                    player.sendMessage(error.message ?: messages.getString(player, "messages.worldmenu_no_access"))
                    openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage, holder.page)
                }
            }
        }
    }

    private fun requestMemberInviteTarget(
        player: Player,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
        forceAdd: Boolean,
    ) {
        if (isBedrock(player)) {
            openBedrockMemberInviteForm(player, worldData, backTarget, myWorldPage, page, forceAdd)
        } else {
            openJavaMemberInviteDialog(player, worldData, backTarget, myWorldPage, page, forceAdd)
        }
    }

    private fun openJavaMemberInviteDialog(
        player: Player,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
        forceAdd: Boolean,
    ) {
        suppressNextInventoryClose(player)
        player.closeInventory()

        val titleKey = if (forceAdd) "gui.member_management.force_add_input.title" else "gui.member_management.invite_input.title"
        val messageKey = if (forceAdd) "gui.member_management.force_add_input.message" else "gui.member_management.invite_input.message"
        val inputKey = if (forceAdd) "member_force_add_target" else "member_invite_target"
        val options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()
        val submitButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.submit")))
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val target = audience as? Player ?: return@customClick
                val value = response.getText(inputKey)?.trim().orEmpty()
                if (value.isBlank()) {
                    target.sendMessage(messages.getString(target, "messages.value_required"))
                    requestMemberInviteTarget(target, worldData, backTarget, myWorldPage, page, forceAdd)
                    return@customClick
                }
                memberManagementService.invitePlayer(target, worldData, value, forceAdd)
                    .onSuccess {
                        val latest = worldService.getWorldByUuid(worldData.uuid) ?: worldData
                        openMemberManagement(target, latest, backTarget, myWorldPage, page)
                    }
                    .onFailure { error ->
                        target.sendMessage(error.message ?: messages.getString(target, "messages.worldmenu_no_access"))
                        openMemberManagement(target, worldData, backTarget, myWorldPage, page)
                    }
            }, options))
            .build()
        val backButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.back")))
            .width(120)
            .action(DialogAction.customClick({ _, audience ->
                val target = audience as? Player ?: return@customClick
                openMemberManagement(target, worldData, backTarget, myWorldPage, page)
            }, options))
            .build()

        val dialog = Dialog.create { factory ->
            factory.empty().base(
                DialogBase.builder(Component.text(messages.getString(player, titleKey)))
                    .canCloseWithEscape(false)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(listOf(DialogBody.plainMessage(Component.text(messages.getString(player, messageKey)), 240)))
                    .inputs(listOf(DialogInput.text(inputKey, 240, Component.text(messages.getString(player, titleKey.replace(".title", ".label"))), true, "", 64, null)))
                    .build(),
            ).type(DialogType.multiAction(listOf(submitButton, backButton), null, 2))
        }

        player.showDialog(dialog)
    }

    private fun openBedrockMemberInviteForm(
        player: Player,
        worldData: MyWorldData,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        page: Int,
        forceAdd: Boolean,
    ) {
        val floodgateApi = runCatching { FloodgateApi.getInstance() }.getOrNull()
        if (floodgateApi == null) {
            player.sendMessage(messages.getString(player, "gui.common.offline_target"))
            return
        }

        suppressNextInventoryClose(player)
        player.closeInventory()

        val titleKey = if (forceAdd) "gui.member_management.force_add_input.title" else "gui.member_management.invite_input.title"
        val messageKey = if (forceAdd) "gui.member_management.force_add_input.message" else "gui.member_management.invite_input.message"
        val inputKey = if (forceAdd) "member_force_add_target" else "member_invite_target"
        val form = CustomForm.builder()
            .title(messages.getString(player, titleKey))
            .label(messages.getString(player, messageKey))
            .input(messages.getString(player, titleKey.replace(".title", ".label")), "", "")
            .validResultHandler { response ->
                val value = response.asInput(0)?.trim().orEmpty()
                if (value.isBlank()) {
                    player.sendMessage(messages.getString(player, "messages.value_required"))
                    requestMemberInviteTarget(player, worldData, backTarget, myWorldPage, page, forceAdd)
                    return@validResultHandler
                }
                memberManagementService.invitePlayer(player, worldData, value, forceAdd)
                    .onSuccess {
                        val latest = worldService.getWorldByUuid(worldData.uuid) ?: worldData
                        openMemberManagement(player, latest, backTarget, myWorldPage, page)
                    }
                    .onFailure { error ->
                        player.sendMessage(error.message ?: messages.getString(player, "messages.worldmenu_no_access"))
                        openMemberManagement(player, worldData, backTarget, myWorldPage, page)
                    }
            }
            .closedResultHandler(Runnable { openMemberManagement(player, worldData, backTarget, myWorldPage, page) })
            .invalidResultHandler(Consumer<InvalidFormResponseResult<org.geysermc.cumulus.response.CustomFormResponse>> { openMemberManagement(player, worldData, backTarget, myWorldPage, page) })
            .build()

        floodgateApi.sendForm(player.uniqueId, form)
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

    fun handleMenuInventoryClosed(player: Player, holder: WorldSettingsHolder) {
        if (suppressedInventoryClose.remove(player.uniqueId)) {
            return
        }
        handleWorldSettingsClosed(player, holder)
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
                openWorldSettings(player, worldData, WorldSettingsBackTarget.MYWORLD_LIST, page)
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

    fun handleWorldSettingsClick(player: Player, event: InventoryClickEvent, holder: WorldSettingsHolder) {
        val worldData = worldService.getWorldByUuid(UUID.fromString(holder.worldId)) ?: run {
            player.sendMessage(messages.getString(player, "messages.world_not_found"))
            player.closeInventory()
            return
        }
        if (!worldService.canOpenSettings(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.worldmenu_no_access"))
            player.closeInventory()
            return
        }

        if (iconSelectionWorlds[player.uniqueId] == worldData.uuid && event.clickedInventory == player.inventory) {
            event.isCancelled = true
            val clickedItem = event.currentItem ?: return
            val material = clickedItem.type
            if (material == Material.AIR) {
                player.sendMessage(messages.getString(player, "messages.world_icon_invalid"))
                return
            }
            playClickSound(player)
            iconSelectionWorlds.remove(player.uniqueId)
            val updated = worldService.updateWorldIcon(worldData, material)
            player.sendMessage(
                Component.text(messages.getString(player, "messages.world_icon_updated_prefix"))
                    .append(GuiComponents.materialNameComponent(material))
                    .append(Component.text(messages.getString(player, "messages.world_icon_updated_suffix"))),
            )
            openWorldSettings(player, updated, holder.backTarget, holder.myWorldPage)
            return
        }

        event.isCancelled = true
        if (event.clickedInventory == player.inventory) {
            return
        }

        when (event.rawSlot) {
            19 -> {
                playClickSound(player)
                requestWorldInfo(player, worldData, holder.backTarget, holder.myWorldPage)
            }

            20 -> {
                playClickSound(player)
                iconSelectionWorlds[player.uniqueId] = worldData.uuid
                player.sendMessage(messages.getString(player, "messages.world_icon_select_start"))
            }

            21 -> handleSpawnSettingClick(player, worldData, holder, event.click)
            23 -> handleWorldExpand(player, worldData, holder)

            24 -> {
                playClickSound(player)
                val updated = worldService.rotatePublishLevel(worldData, forward = event.click.isLeftClick || isBedrock(player))
                player.sendMessage(
                    messages.getString(
                        player,
                        "messages.world_publish_updated",
                        mapOf("level" to messages.getString(player, publishLevelKey(updated))),
                    ),
                )
                openWorldSettings(player, updated, holder.backTarget, holder.myWorldPage)
            }

            25 -> {
                playClickSound(player)
                openMemberManagement(player, worldData, holder.backTarget, holder.myWorldPage)
            }
            28 -> requestWorldTags(player, worldData, holder.backTarget, holder.myWorldPage)
            29 -> requestWorldAnnouncement(player, worldData, holder.backTarget, holder.myWorldPage)

            30 -> {
                playClickSound(player)
                val updated = worldService.toggleVisitNotification(worldData)
                player.sendMessage(
                    messages.getString(
                        player,
                        "messages.world_notification_updated",
                        mapOf("status" to messages.getString(player, if (updated.notificationEnabled) "gui.common.state.on" else "gui.common.state.off")),
                    ),
                )
                openWorldSettings(player, updated, holder.backTarget, holder.myWorldPage)
            }

            32 -> {
                playClickSound(player)
                openWorldEnvironmentSettings(player, worldData, holder.backTarget, holder.myWorldPage)
            }

            45 -> {
                when (holder.backTarget) {
                    WorldSettingsBackTarget.NONE -> return
                    WorldSettingsBackTarget.MENU_COMMAND -> {
                        playClickSound(player)
                        executeConfiguredMenuCommand(player)
                    }
                    WorldSettingsBackTarget.MYWORLD_LIST -> {
                        playClickSound(player)
                        openMyWorldList(player, holder.myWorldPage, false)
                    }
                }
            }
        }
    }

    fun handleWorldEnvironmentClick(player: Player, event: InventoryClickEvent, holder: WorldEnvironmentHolder) {
        val worldData = worldService.getWorldByUuid(UUID.fromString(holder.worldId)) ?: run {
            event.isCancelled = true
            player.sendMessage(messages.getString(player, "messages.world_not_found"))
            player.closeInventory()
            return
        }

        event.isCancelled = true
        if (event.clickedInventory == player.inventory) {
            return
        }

        when (event.rawSlot) {
            20 -> toggleWorldWeather(player, worldData, holder)
            24 -> toggleWorldTime(player, worldData, holder)
            40 -> {
                playClickSound(player)
                openWorldSettings(player, worldData, holder.backTarget, holder.myWorldPage)
            }
        }
    }

    fun handleWorldSettingsClosed(player: Player, holder: WorldSettingsHolder) {
        if (iconSelectionWorlds.remove(player.uniqueId, UUID.fromString(holder.worldId))) {
            player.sendMessage(messages.getString(player, "messages.world_icon_selection_cancelled"))
        }
    }

    fun handleWorldSpawnInteract(player: Player, event: PlayerInteractEvent) {
        val state = spawnSelectionStates[player.uniqueId] ?: return
        if (event.hand != null && event.hand != EquipmentSlot.HAND) {
            return
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) {
            return
        }

        event.isCancelled = true
        val clickedBlock = event.clickedBlock ?: return
        val worldData = worldService.getWorldByUuid(state.worldId) ?: run {
            clearSpawnSelection(player)
            player.sendMessage(messages.getString(player, "messages.world_not_found"))
            return
        }

        if (!worldService.isPlayerInWorld(player, worldData)) {
            clearSpawnSelection(player)
            player.sendMessage(messages.getString(player, "messages.world_setting_requires_current_world"))
            return
        }

        val loc = clickedBlock.location.clone().add(0.5, 1.0, 0.5)
        loc.yaw = normalizeToCardinalYaw(player.location.yaw)
        loc.pitch = 0.0f

        if (!isSpawnAreaPlaceable(loc)) {
            player.sendMessage(messages.getString(player, "messages.spawn_set_blocked"))
            return
        }

        playClickSound(player)
        val serializable = loc.toSerializableLocation()
        val updated = if (state.setBoth) {
            player.sendMessage(messages.getString(player, "messages.spawn_both_set"))
            worldService.updateWorldSpawn(worldData, serializable, serializable)
        } else if (state.guest) {
            player.sendMessage(messages.getString(player, "messages.spawn_guest_set"))
            worldService.updateWorldSpawn(worldData, serializable, worldData.spawnPosMember)
        } else {
            player.sendMessage(messages.getString(player, "messages.spawn_member_set"))
            worldService.updateWorldSpawn(worldData, worldData.spawnPosGuest, serializable)
        }

        clearSpawnSelection(player)
        openWorldSettings(player, updated, state.backTarget, state.myWorldPage)
    }

    fun handlePlayerQuit(player: Player) {
        clearSpawnSelection(player)
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

    private fun requestWorldInfo(player: Player, worldData: MyWorldData, backTarget: WorldSettingsBackTarget, myWorldPage: Int) {
        if (isBedrock(player)) {
            openBedrockWorldInfoForm(player, worldData, backTarget, myWorldPage)
        } else {
            openJavaWorldInfoDialog(player, worldData, backTarget, myWorldPage)
        }
    }

    private fun validateWorldInfo(name: String, description: String): String? {
        return worldService.validateWorldName(name)
            ?: worldService.validateWorldDescription(description)
    }

    private fun handleSpawnSettingClick(player: Player, worldData: MyWorldData, holder: WorldSettingsHolder, click: ClickType) {
        if (!worldService.isPlayerInWorld(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.world_setting_requires_current_world"))
            return
        }

        playClickSound(player)
        val bedrock = isBedrock(player)
        val guest = bedrock || click.isLeftClick
        val typeKey = when {
            bedrock -> "gui.world_settings.spawn.type.both"
            guest -> "gui.world_settings.spawn.type.guest"
            else -> "gui.world_settings.spawn.type.member"
        }
        spawnSelectionStates[player.uniqueId] = SpawnSelectionState(
            worldId = worldData.uuid,
            guest = guest,
            setBoth = bedrock,
            backTarget = holder.backTarget,
            myWorldPage = holder.myWorldPage,
        )
        player.sendMessage(
            messages.getString(
                player,
                if (bedrock) "messages.spawn_set_start_bedrock" else "messages.spawn_set_start",
                mapOf("type" to messages.getString(player, typeKey)),
            ),
        )
        startSpawnPreview(player)
        suppressNextInventoryClose(player)
        player.closeInventory()
    }

    private fun handleWorldExpand(player: Player, worldData: MyWorldData, holder: WorldSettingsHolder) {
        if (!worldService.isPlayerInWorld(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.world_setting_requires_current_world"))
            return
        }

        val world = player.world
        runCatching {
            worldService.expandWorldBorder(worldData, world)
        }.onSuccess {
            playClickSound(player)
            player.sendMessage(
                messages.getString(
                    player,
                    "messages.world_expand_updated",
                    mapOf("size" to formatWorldSize(it.borderSize)),
                ),
            )
            openWorldSettings(player, it, holder.backTarget, holder.myWorldPage)
        }.onFailure {
            player.sendMessage(it.message ?: messages.getString(player, "messages.world_expand_failed"))
        }
    }

    private fun requestWorldTags(player: Player, worldData: MyWorldData, backTarget: WorldSettingsBackTarget, myWorldPage: Int) {
        if (isBedrock(player)) {
            openBedrockSingleLineForm(
                player = player,
                worldData = worldData,
                titleKey = "gui.world_settings.tags.form.title",
                messageKey = "gui.world_settings.tags.form.message",
                labelKey = "gui.world_settings.tags.form.tags",
                defaultValue = worldData.tags.joinToString(", "),
                backTarget = backTarget,
                myWorldPage = myWorldPage,
                allowBlank = true,
                maxLength = 240,
                onSubmit = { target, latest, value ->
                    val tags = parseTags(value)
                    worldService.updateWorldTags(latest, tags)
                },
                successMessageKey = "messages.world_tags_updated",
            )
        } else {
            openJavaSingleLineDialog(
                player = player,
                worldData = worldData,
                titleKey = "gui.world_settings.tags.form.title",
                messageKey = "gui.world_settings.tags.form.message",
                labelKey = "gui.world_settings.tags.form.tags",
                inputKey = "world_tags",
                initialValue = worldData.tags.joinToString(", "),
                backTarget = backTarget,
                myWorldPage = myWorldPage,
                allowBlank = true,
                maxLength = 240,
                onSubmit = { target, latest, value ->
                    val tags = parseTags(value)
                    worldService.updateWorldTags(latest, tags)
                },
                successMessageKey = "messages.world_tags_updated",
            )
        }
    }

    private fun requestWorldAnnouncement(player: Player, worldData: MyWorldData, backTarget: WorldSettingsBackTarget, myWorldPage: Int) {
        if (isBedrock(player)) {
            openBedrockSingleLineForm(
                player = player,
                worldData = worldData,
                titleKey = "gui.world_settings.announcement.form.title",
                messageKey = "gui.world_settings.announcement.form.message",
                labelKey = "gui.world_settings.announcement.form.announcement",
                defaultValue = worldData.announcementMessage,
                backTarget = backTarget,
                myWorldPage = myWorldPage,
                allowBlank = true,
                maxLength = plugin.config.getInt("world_announcement.max_length", 100),
                onSubmit = { _, latest, value -> worldService.updateAnnouncement(latest, value) },
                successMessageKey = "messages.world_announcement_updated",
            )
        } else {
            openJavaSingleLineDialog(
                player = player,
                worldData = worldData,
                titleKey = "gui.world_settings.announcement.form.title",
                messageKey = "gui.world_settings.announcement.form.message",
                labelKey = "gui.world_settings.announcement.form.announcement",
                inputKey = "world_announcement",
                initialValue = worldData.announcementMessage,
                backTarget = backTarget,
                myWorldPage = myWorldPage,
                allowBlank = true,
                maxLength = plugin.config.getInt("world_announcement.max_length", 100),
                onSubmit = { _, latest, value -> worldService.updateAnnouncement(latest, value) },
                successMessageKey = "messages.world_announcement_updated",
            )
        }
    }

    private fun toggleWorldWeather(player: Player, worldData: MyWorldData, holder: WorldEnvironmentHolder) {
        if (!worldService.isPlayerInWorld(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.world_setting_requires_current_world"))
            openWorldSettings(player, worldData, holder.backTarget, holder.myWorldPage)
            return
        }

        val world = player.world
        val clear = world.hasStorm() || world.isThundering
        world.setStorm(!clear)
        world.isThundering = false
        world.weatherDuration = 20 * 60 * 10
        playClickSound(player)
        player.sendMessage(
            messages.getString(
                player,
                "messages.world_weather_updated",
                mapOf("weather" to messages.getString(player, if (clear) "gui.world_environment.weather.clear" else "gui.world_environment.weather.rain")),
            ),
        )
        openWorldEnvironmentSettings(player, worldData, holder.backTarget, holder.myWorldPage)
    }

    private fun toggleWorldTime(player: Player, worldData: MyWorldData, holder: WorldEnvironmentHolder) {
        if (!worldService.isPlayerInWorld(player, worldData)) {
            player.sendMessage(messages.getString(player, "messages.world_setting_requires_current_world"))
            openWorldSettings(player, worldData, holder.backTarget, holder.myWorldPage)
            return
        }

        val world = player.world
        val nextDay = world.time in 13000L..23999L
        world.time = if (nextDay) 1000L else 13000L
        playClickSound(player)
        player.sendMessage(
            messages.getString(
                player,
                "messages.world_time_updated",
                mapOf("time" to messages.getString(player, if (nextDay) "gui.world_environment.time.day" else "gui.world_environment.time.night")),
            ),
        )
        openWorldEnvironmentSettings(player, worldData, holder.backTarget, holder.myWorldPage)
    }

    private fun publishLevelKey(worldData: MyWorldData): String {
        return when (worldData.publishLevel) {
            me.awabi2048.mwmchanpon.model.WorldPublishLevel.PUBLIC -> "gui.myworld.world_icon.publish.public"
            me.awabi2048.mwmchanpon.model.WorldPublishLevel.WIP -> "gui.myworld.world_icon.publish.wip"
            me.awabi2048.mwmchanpon.model.WorldPublishLevel.CLOSED -> "gui.myworld.world_icon.publish.closed"
        }
    }

    private fun parseTags(value: String): List<String> {
        return value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun formatWorldSize(size: Double): String {
        return if (size % 1.0 == 0.0) {
            size.toInt().toString()
        } else {
            "%.1f".format(Locale.US, size)
        }
    }

    private fun startSpawnPreview(player: Player) {
        stopSpawnPreview(player)
        spawnPreviewTasks[player.uniqueId] = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable {
                val state = spawnSelectionStates[player.uniqueId]
                if (!player.isOnline || state == null) {
                    clearSpawnSelection(player)
                    return@Runnable
                }
                val worldData = worldService.getWorldByUuid(state.worldId)
                if (worldData == null || !worldService.isPlayerInWorld(player, worldData)) {
                    clearSpawnSelection(player)
                    return@Runnable
                }
                val targetBlock = player.getTargetBlockExact(6) ?: return@Runnable
                val spawnLoc = targetBlock.location.clone().add(0.5, 1.0, 0.5)
                val yaw = normalizeToCardinalYaw(player.location.yaw)
                spawnSpawnPreview(player, spawnLoc, yaw, isSpawnAreaPlaceable(spawnLoc))
            },
            0L,
            1L,
        )
    }

    private fun stopSpawnPreview(player: Player) {
        spawnPreviewTasks.remove(player.uniqueId)?.cancel()
    }

    private fun clearSpawnSelection(player: Player) {
        spawnSelectionStates.remove(player.uniqueId)
        stopSpawnPreview(player)
    }

    private fun normalizeToCardinalYaw(yawRaw: Float): Float {
        var yaw = yawRaw
        while (yaw < 0) yaw += 360f
        while (yaw >= 360f) yaw -= 360f
        return when {
            yaw >= 45f && yaw < 135f -> 90f
            yaw >= 135f && yaw < 225f -> 180f
            yaw >= 225f && yaw < 315f -> 270f
            else -> 0f
        }
    }

    private fun isSpawnAreaPlaceable(spawnLoc: Location): Boolean {
        val feetBlock = spawnLoc.block
        val headBlock = spawnLoc.clone().add(0.0, 1.0, 0.0).block
        return isAirOrWater(feetBlock.type) && isAirOrWater(headBlock.type)
    }

    private fun isAirOrWater(material: Material): Boolean {
        return material.isAir || material == Material.WATER
    }

    private fun spawnSpawnPreview(player: Player, spawnLoc: Location, yaw: Float, placeable: Boolean) {
        val world = spawnLoc.world ?: return
        val frameColor = if (placeable) Color.fromRGB(64, 255, 120) else Color.fromRGB(255, 80, 80)
        val frameDust = Particle.DustOptions(frameColor, 0.5f)
        val arrowDust = Particle.DustOptions(Color.fromRGB(80, 160, 255), 0.5f)
        val feetBlockY = spawnLoc.blockY

        spawnSpawnBlockOutline(player, spawnLoc.blockX, feetBlockY, spawnLoc.blockZ, frameDust, drawBottomFace = true, drawTopFace = false)
        spawnSpawnBlockOutline(player, spawnLoc.blockX, feetBlockY + 1, spawnLoc.blockZ, frameDust, drawBottomFace = false, drawTopFace = true)

        val rad = Math.toRadians(yaw.toDouble())
        val forwardX = -kotlin.math.sin(rad)
        val forwardZ = kotlin.math.cos(rad)
        val arrowStart = Location(world, spawnLoc.x - forwardX * 0.5, spawnLoc.y + 0.15, spawnLoc.z - forwardZ * 0.5)
        spawnDirectionArrow(player, arrowStart, yaw, arrowDust)
    }

    private fun spawnSpawnBlockOutline(
        player: Player,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        dust: Particle.DustOptions,
        drawBottomFace: Boolean,
        drawTopFace: Boolean,
    ) {
        val minX = blockX.toDouble()
        val minY = blockY.toDouble()
        val minZ = blockZ.toDouble()
        val maxX = blockX + 1.0
        val maxY = blockY + 1.0
        val maxZ = blockZ + 1.0

        if (drawBottomFace) {
            spawnLineWithParticles(player, minX, minY, minZ, maxX, minY, minZ, 10, dust)
            spawnLineWithParticles(player, minX, minY, maxZ, maxX, minY, maxZ, 10, dust)
            spawnLineWithParticles(player, minX, minY, minZ, minX, minY, maxZ, 10, dust)
            spawnLineWithParticles(player, maxX, minY, minZ, maxX, minY, maxZ, 10, dust)
        }
        if (drawTopFace) {
            spawnLineWithParticles(player, minX, maxY, minZ, maxX, maxY, minZ, 10, dust)
            spawnLineWithParticles(player, minX, maxY, maxZ, maxX, maxY, maxZ, 10, dust)
            spawnLineWithParticles(player, minX, maxY, minZ, minX, maxY, maxZ, 10, dust)
            spawnLineWithParticles(player, maxX, maxY, minZ, maxX, maxY, maxZ, 10, dust)
        }

        spawnLineWithParticles(player, minX, minY, minZ, minX, maxY, minZ, 10, dust)
        spawnLineWithParticles(player, maxX, minY, minZ, maxX, maxY, minZ, 10, dust)
        spawnLineWithParticles(player, minX, minY, maxZ, minX, maxY, maxZ, 10, dust)
        spawnLineWithParticles(player, maxX, minY, maxZ, maxX, maxY, maxZ, 10, dust)
    }

    private fun spawnDirectionArrow(player: Player, start: Location, yaw: Float, dust: Particle.DustOptions) {
        val rad = Math.toRadians(yaw.toDouble())
        val forwardX = -kotlin.math.sin(rad)
        val forwardZ = kotlin.math.cos(rad)
        val tipX = start.x + forwardX * 1.0
        val tipZ = start.z + forwardZ * 1.0
        spawnLineWithParticles(player, start.x, start.y, start.z, tipX, start.y, tipZ, 10, dust)

        val baseX = tipX - forwardX * 0.4
        val baseZ = tipZ - forwardZ * 0.4
        val sideX = -forwardZ * 0.2
        val sideZ = forwardX * 0.2
        spawnLineWithParticles(player, tipX, start.y, tipZ, baseX + sideX, start.y, baseZ + sideZ, 5, dust)
        spawnLineWithParticles(player, tipX, start.y, tipZ, baseX - sideX, start.y, baseZ - sideZ, 5, dust)
    }

    private fun spawnLineWithParticles(
        player: Player,
        startX: Double,
        startY: Double,
        startZ: Double,
        endX: Double,
        endY: Double,
        endZ: Double,
        points: Int,
        dust: Particle.DustOptions,
    ) {
        val lastIndex = (points - 1).coerceAtLeast(1)
        for (i in 0 until points) {
            val t = i.toDouble() / lastIndex.toDouble()
            val x = startX + (endX - startX) * t
            val y = startY + (endY - startY) * t
            val z = startZ + (endZ - startZ) * t
            player.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, dust)
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

    private fun clearWorldSettingsState(player: Player) {
        iconSelectionWorlds.remove(player.uniqueId)
        clearSpawnSelection(player)
    }

    private fun Location.toSerializableLocation(): SerializableLocation {
        return SerializableLocation(
            world = world?.name.orEmpty(),
            x = x,
            y = y,
            z = z,
            yaw = yaw,
            pitch = pitch,
        )
    }

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

    private fun openBedrockSingleLineForm(
        player: Player,
        worldData: MyWorldData,
        titleKey: String,
        messageKey: String,
        labelKey: String,
        defaultValue: String,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        allowBlank: Boolean,
        maxLength: Int,
        onSubmit: (Player, MyWorldData, String) -> Result<MyWorldData>,
        successMessageKey: String,
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
            .input(messages.getString(player, labelKey), "", defaultValue)
            .validResultHandler { response ->
                val value = response.asInput(0)?.trim().orEmpty()
                if (!allowBlank && value.isBlank()) {
                    player.sendMessage(messages.getString(player, "messages.value_required"))
                    openBedrockSingleLineForm(player, worldData, titleKey, messageKey, labelKey, defaultValue, backTarget, myWorldPage, allowBlank, maxLength, onSubmit, successMessageKey)
                    return@validResultHandler
                }
                if (value.length > maxLength) {
                    player.sendMessage(messages.getString(player, "messages.value_too_long", mapOf("max" to maxLength.toString())))
                    openBedrockSingleLineForm(player, worldData, titleKey, messageKey, labelKey, defaultValue, backTarget, myWorldPage, allowBlank, maxLength, onSubmit, successMessageKey)
                    return@validResultHandler
                }

                val latest = worldService.getWorldByUuid(worldData.uuid) ?: worldData
                onSubmit(player, latest, value)
                    .onSuccess {
                        playClickSound(player)
                        player.sendMessage(messages.getString(player, successMessageKey))
                        openWorldSettings(player, it, backTarget, myWorldPage)
                    }
                    .onFailure {
                        player.sendMessage(it.message ?: messages.getString(player, "messages.world_not_found"))
                        openWorldSettings(player, latest, backTarget, myWorldPage)
                    }
            }
            .closedResultHandler(Runnable { openWorldSettings(player, worldData, backTarget, myWorldPage) })
            .invalidResultHandler(Consumer<InvalidFormResponseResult<org.geysermc.cumulus.response.CustomFormResponse>> { openWorldSettings(player, worldData, backTarget, myWorldPage) })
            .build()

        floodgateApi.sendForm(player.uniqueId, form)
    }

    private fun openJavaSingleLineDialog(
        player: Player,
        worldData: MyWorldData,
        titleKey: String,
        messageKey: String,
        labelKey: String,
        inputKey: String,
        initialValue: String,
        backTarget: WorldSettingsBackTarget,
        myWorldPage: Int,
        allowBlank: Boolean,
        maxLength: Int,
        onSubmit: (Player, MyWorldData, String) -> Result<MyWorldData>,
        successMessageKey: String,
    ) {
        suppressNextInventoryClose(player)
        player.closeInventory()

        val options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()
        val submitButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.submit")))
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val target = audience as? Player ?: return@customClick
                val value = response.getText(inputKey)?.trim().orEmpty()
                if (!allowBlank && value.isBlank()) {
                    target.sendMessage(messages.getString(target, "messages.value_required"))
                    openJavaSingleLineDialog(target, worldData, titleKey, messageKey, labelKey, inputKey, initialValue, backTarget, myWorldPage, allowBlank, maxLength, onSubmit, successMessageKey)
                    return@customClick
                }
                if (value.length > maxLength) {
                    target.sendMessage(messages.getString(target, "messages.value_too_long", mapOf("max" to maxLength.toString())))
                    openJavaSingleLineDialog(target, worldData, titleKey, messageKey, labelKey, inputKey, initialValue, backTarget, myWorldPage, allowBlank, maxLength, onSubmit, successMessageKey)
                    return@customClick
                }

                val latest = worldService.getWorldByUuid(worldData.uuid) ?: worldData
                onSubmit(target, latest, value)
                    .onSuccess {
                        playClickSound(target)
                        target.sendMessage(messages.getString(target, successMessageKey))
                        openWorldSettings(target, it, backTarget, myWorldPage)
                    }
                    .onFailure {
                        target.sendMessage(it.message ?: messages.getString(target, "messages.world_not_found"))
                        openWorldSettings(target, latest, backTarget, myWorldPage)
                    }
            }, options))
            .build()
        val backButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.back")))
            .width(120)
            .action(DialogAction.customClick({ _, audience ->
                val target = audience as? Player ?: return@customClick
                openWorldSettings(target, worldData, backTarget, myWorldPage)
            }, options))
            .build()

        val dialog = Dialog.create { factory ->
            factory.empty().base(
                DialogBase.builder(Component.text(messages.getString(player, titleKey)))
                    .canCloseWithEscape(false)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(listOf(DialogBody.plainMessage(Component.text(messages.getString(player, messageKey)), 240)))
                    .inputs(listOf(DialogInput.text(inputKey, 240, Component.text(messages.getString(player, labelKey)), allowBlank.not(), initialValue, maxLength, null)))
                    .build(),
            ).type(DialogType.multiAction(listOf(submitButton, backButton), null, 2))
        }

        player.showDialog(dialog)
    }

    private fun openBedrockWorldInfoForm(player: Player, worldData: MyWorldData, backTarget: WorldSettingsBackTarget, myWorldPage: Int) {
        val floodgateApi = runCatching { FloodgateApi.getInstance() }.getOrNull()
        if (floodgateApi == null) {
            player.sendMessage(messages.getString(player, "gui.common.offline_target"))
            return
        }

        suppressNextInventoryClose(player)
        player.closeInventory()

        val form = CustomForm.builder()
            .title(messages.getString(player, "gui.world_settings.info.form.title"))
            .label(messages.getString(player, "gui.world_settings.info.form.message"))
            .input(messages.getString(player, "gui.world_settings.info.form.name"), "", worldData.name)
            .input(messages.getString(player, "gui.world_settings.info.form.description"), "", worldData.description)
            .validResultHandler { response ->
                val name = response.asInput(0)?.trim().orEmpty()
                val description = response.asInput(1)?.trim().orEmpty()
                val error = validateWorldInfo(name, description)
                if (error != null) {
                    player.sendMessage(error)
                    requestWorldInfo(player, worldData, backTarget, myWorldPage)
                    return@validResultHandler
                }

                val latest = worldService.getWorldByUuid(worldData.uuid) ?: worldData
                val result = worldService.updateWorldInfo(latest, name, description)
                result.onSuccess {
                    playClickSound(player)
                    player.sendMessage(messages.getString(player, "messages.world_info_updated"))
                    openWorldSettings(player, it, backTarget, myWorldPage)
                }.onFailure {
                    player.sendMessage(it.message ?: messages.getString(player, "messages.world_not_found"))
                }
            }
            .closedResultHandler(Runnable { openWorldSettings(player, worldData, backTarget, myWorldPage) })
            .invalidResultHandler(Consumer<InvalidFormResponseResult<org.geysermc.cumulus.response.CustomFormResponse>> { openWorldSettings(player, worldData, backTarget, myWorldPage) })
            .build()

        floodgateApi.sendForm(player.uniqueId, form)
    }

    private fun openJavaWorldInfoDialog(player: Player, worldData: MyWorldData, backTarget: WorldSettingsBackTarget, myWorldPage: Int) {
        suppressNextInventoryClose(player)
        player.closeInventory()

        val options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()
        val submitButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.submit")))
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val target = audience as? Player ?: return@customClick
                val name = response.getText("world_settings_name")?.trim().orEmpty()
                val description = response.getText("world_settings_description")?.trim().orEmpty()
                val error = validateWorldInfo(name, description)
                if (error != null) {
                    target.sendMessage(error)
                    requestWorldInfo(target, worldData, backTarget, myWorldPage)
                    return@customClick
                }

                val latest = worldService.getWorldByUuid(worldData.uuid) ?: worldData
                val result = worldService.updateWorldInfo(latest, name, description)
                result.onSuccess {
                    playClickSound(target)
                    target.sendMessage(messages.getString(target, "messages.world_info_updated"))
                    openWorldSettings(target, it, backTarget, myWorldPage)
                }.onFailure {
                    target.sendMessage(it.message ?: messages.getString(target, "messages.world_not_found"))
                }
            }, options))
            .build()
        val backButton = ActionButton.builder(Component.text(messages.getString(player, "gui.creation.input.back")))
            .width(120)
            .action(DialogAction.customClick({ _, audience ->
                val target = audience as? Player ?: return@customClick
                openWorldSettings(target, worldData, backTarget, myWorldPage)
            }, options))
            .build()

        val dialog = Dialog.create { factory ->
            factory.empty().base(
                DialogBase.builder(Component.text(messages.getString(player, "gui.world_settings.info.form.title")))
                    .canCloseWithEscape(false)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(listOf(DialogBody.plainMessage(Component.text(messages.getString(player, "gui.world_settings.info.form.message")), 240)))
                    .inputs(
                        listOf(
                            DialogInput.text("world_settings_name", 240, Component.text(messages.getString(player, "gui.world_settings.info.form.name")), true, worldData.name, 64, null),
                            DialogInput.text("world_settings_description", 240, Component.text(messages.getString(player, "gui.world_settings.info.form.description")), false, worldData.description, plugin.config.getInt("world_description.max_length", 100), null),
                        ),
                    )
                    .build(),
            ).type(DialogType.multiAction(listOf(submitButton, backButton), null, 2))
        }

        player.showDialog(dialog)
    }

    private fun playClickSound(player: Player) {
        val sound = resolveConfiguredSound("sounds.global_click.sound", Sound.UI_BUTTON_CLICK)
        val pitch = plugin.config.getDouble("sounds.global_click.pitch", 2.0).toFloat()
        player.playSound(player.location, sound, 1.0f, pitch)
    }

    private fun playWorldCreateSound(player: Player) {
        val sound = resolveConfiguredSound("sounds.world_create.sound", Sound.BLOCK_PORTAL_TRIGGER)
        val pitch = plugin.config.getDouble("sounds.world_create.pitch", 1.0).toFloat()
        player.playSound(player.location, sound, 1.0f, pitch)
    }

    private fun resolveConfiguredSound(path: String, fallback: Sound): Sound {
        val raw = plugin.config.getString(path).orEmpty()
        val key = raw.lowercase(Locale.ROOT).replace('_', '.')
        return NamespacedKey.fromString(key)?.let { Registry.SOUND_EVENT.get(it) } ?: fallback
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
