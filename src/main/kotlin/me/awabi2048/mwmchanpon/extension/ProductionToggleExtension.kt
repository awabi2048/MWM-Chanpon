package me.awabi2048.mwmchanpon.extension
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiStructuredMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProvider
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderDefinition
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderRegistry
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateRestoreContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import com.awabi2048.ccsystem.api.gui.MenuGesture
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.WorldCreationAvailability
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.service.ChanponReversibleOpaqueState
import me.awabi2048.mwmchanpon.service.decodeChanponOpaqueState
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.ui.ChanponGuiElements
import me.awabi2048.mwmchanpon.ui.chanponCapabilityAction
import me.awabi2048.mwmchanpon.ui.chanponGestureAction
import me.awabi2048.mwmchanpon.ui.reasonedAvailability
import me.awabi2048.mwmchanpon.ui.asConfirmation
import me.awabi2048.mwmchanpon.service.ChanponReversibleContracts
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmWorldCreatedEvent
import me.awabi2048.myworldmanager.api.extension.CreationConfirmationCapabilityContract
import me.awabi2048.myworldmanager.api.extension.WorldCreationDraft
import me.awabi2048.myworldmanager.api.extension.WorldSettingsCapabilityPlacements
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

internal class CreationProductionSelectionState {
    private val pendingProduction = ConcurrentHashMap<Pair<UUID, String>, Boolean>()
    private val reversibleTargets = ConcurrentHashMap<UUID, ProductionToggleExtension.CreationProductionSelectionTarget>()

    fun takePending(playerUuid: UUID, worldName: String): Boolean? = pendingProduction.remove(playerUuid to worldName)

    fun setPending(playerUuid: UUID, worldName: String, selected: Boolean) {
        pendingProduction[playerUuid to worldName] = selected
    }

    fun capture(
        playerUuid: UUID,
        draft: WorldCreationDraft,
    ): Pair<UUID, ProductionToggleExtension.CreationProductionSelectionSnapshot> {
        val target = ProductionToggleExtension.CreationProductionSelectionTarget(playerUuid, draft)
        val operationId = UUID.randomUUID()
        reversibleTargets[operationId] = target
        val beforeSelected = target.selected()
        val worldName = target.worldName()
        return operationId to ProductionToggleExtension.CreationProductionSelectionSnapshot(
            playerUuid = playerUuid,
            beforeSelected = beforeSelected,
            expectedSelected = !beforeSelected,
            worldName = worldName,
            beforePending = worldName?.let { pendingProduction[playerUuid to it] },
            expectedPending = worldName?.let { !beforeSelected },
        )
    }

    fun restore(
        operationId: UUID,
        snapshot: ProductionToggleExtension.CreationProductionSelectionSnapshot,
    ): Boolean {
        val target = reversibleTargets.remove(operationId) ?: return false
        if (target.playerUuid != snapshot.playerUuid || target.worldName() != snapshot.worldName) return false
        val currentPending = snapshot.worldName?.let { pendingProduction[snapshot.playerUuid to it] }
        if (target.selected() != snapshot.expectedSelected || currentPending != snapshot.expectedPending) return false
        target.restoreSelected(snapshot.beforeSelected)
        snapshot.worldName?.let { worldName ->
            val key = snapshot.playerUuid to worldName
            if (snapshot.beforePending == null) pendingProduction.remove(key)
            else pendingProduction[key] = snapshot.beforePending
        }
        return true
    }
}

class ProductionToggleExtension(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController
) : Listener {
    private val creationSelectionState = CreationProductionSelectionState()
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        CreationProductionSelectionProvider.register(
            CCSystem.getAPI().getMenuReversibleStateProviderRegistry(),
            creationSelectionState,
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = CONFIRMATION_ROUTE,
                renderer = { context -> renderConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_CONFIRM to MenuActionHandler(::confirm),
                    ACTION_CANCEL to MenuActionHandler(::cancel),
                ),
            ),
        )
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = RUNTIME_OWNER,
                id = CAPABILITY_ID,
                placement = WorldSettingsCapabilityPlacements.FOOTER_ACTIONS,
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability(::isCapabilityInScope),
                presentationProvider =
                    MenuCapabilityPresentationProvider(::resolveCapabilityPresentation),
                actions = listOf(
                    chanponCapabilityAction(
                        id = "open-confirmation",
                        trigger = MenuCapabilityTrigger.ANY,
                        textProvider =
                            MenuCapabilityActionTextProvider(::capabilityActionText),
                        availability = reasonedAvailability(
                            reason = { context ->
                                i18nComponent(context.player, productionDecision(context).messageKey)
                            },
                            predicate = { context -> productionDecision(context).available },
                        ),
                        handler = MenuCapabilityActionHandler(::handleCapabilityAction),
                        safety = MenuActionSafety.CONFIRM_ENTRY,
                    ),
                ),
            ),
        )
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = RUNTIME_OWNER,
                id = CREATION_CAPABILITY_LOCAL_ID,
                placement = CreationConfirmationCapabilityContract.PLACEMENT,
                availability = me.awabi2048.mwmchanpon.ui.optionalAvailability { creationDraft(it) != null },
                presentationProvider = MenuCapabilityPresentationProvider {
                    creationPresentation(
                        creationDraft(it)?.getBoolean(EXTRA_KEY) ?: false,
                    )
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "toggle",
                        trigger = MenuCapabilityTrigger.ANY,
                        textProvider = MenuCapabilityActionTextProvider {
                            i18n(it.player, "production.lore.action")
                        },
                        handler = MenuCapabilityActionHandler {
                            val draft = creationDraft(it)
                                ?: return@MenuCapabilityActionHandler MenuActionResult.Ignored
                            toggleCreationSelection(it.player, draft)
                            MenuActionResult.Success(MenuUpdate.Refresh)
                        },
                        safety = MenuActionSafety.REVERSIBLE,
                        reversibleContract = ChanponReversibleContracts.creationProductionSelection(),
                        classification = ChanponReversibleActionClassification.Operation.CREATION_PRODUCTION_SELECTION,
                    ),
                ),
            ),
        )
    }

    @EventHandler
    fun onWorldCreated(event: MwmWorldCreatedEvent) {
        storage.initializeSubmissionState(event.worldUuid)
        val enabled = creationSelectionState.takePending(event.ownerUuid, event.worldName) ?: return
        if (enabled) {
            val owner = Bukkit.getPlayer(event.ownerUuid)
            if (owner == null || !canUseProduction(owner)) return
            if (ownerHasProductionWorld(event.ownerUuid, event.worldUuid)) return
            storage.setProductionWorld(event.worldUuid, true)
            applyForcedTags(event.worldUuid)
        }
    }

    fun syncForcedTagsForProductionWorlds() {
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        repository.findAll()
            .filter { storage.isProductionWorld(it.uuid) }
            .forEach { applyForcedTags(it.uuid) }
    }

    private fun toggleCreationSelection(player: Player, draft: WorldCreationDraft) {
        if (!canUseProduction(player)) {
            return
        }
        val next = !(draft.getBoolean(EXTRA_KEY) ?: false)
        if (next && ownerHasProductionWorld(player.uniqueId, null)) {
            player.sendMessage(i18n(player, "message.production_limit"))
            draft.setBoolean(EXTRA_KEY, false)
            return
        }
        draft.setBoolean(EXTRA_KEY, next)
        draft.worldName?.let { creationSelectionState.setPending(player.uniqueId, it, next) }
    }

    /**
     * 復元対象への到達手段です。可逆状態の値そのものは保持しません。
     *
     * [WorldCreationDraft] は可変のCapability属性なので、snapshotへ混在させず、
     * providerが復元する時だけ利用します。
     */
    internal class CreationProductionSelectionTarget(
        val playerUuid: UUID,
        private val draft: WorldCreationDraft,
    ) {
        fun worldName(): String? = draft.worldName

        fun selected(): Boolean = draft.getBoolean(EXTRA_KEY) ?: false

        fun restoreSelected(value: Boolean) {
            draft.setBoolean(EXTRA_KEY, value)
        }
    }

    /** 可逆操作の比較・復元値だけを持つ不変snapshotです。 */
    internal data class CreationProductionSelectionSnapshot(
        val playerUuid: UUID,
        val beforeSelected: Boolean,
        val expectedSelected: Boolean,
        val worldName: String?,
        val beforePending: Boolean?,
        val expectedPending: Boolean?,
    )

    private fun isCapabilityInScope(context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext): Boolean {
        // TODO: 本番設定を変更できない場合も項目を消さず、BARRIERと理由Loreを返す。
        val worldData = worldData(context.arguments) ?: return false
        return worldData.owner == context.player.uniqueId ||
            context.player.hasPermission("myworldmanager.admin")
    }

    private fun creationDraft(
        context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext,
    ): WorldCreationDraft? =
        context.attributes[CreationConfirmationCapabilityContract.DRAFT_ATTRIBUTE]
            as? WorldCreationDraft

    private fun creationDraft(
        context: MenuCapabilityActionContext,
    ): WorldCreationDraft? =
        context.attributes[CreationConfirmationCapabilityContract.DRAFT_ATTRIBUTE]
            as? WorldCreationDraft

    private fun resolveCapabilityPresentation(
        context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext,
    ): MenuCapabilityPresentation {
        // TODO: availability・presentation・actionを単一のdecisionから生成し、表示と実行可否の判定を一致させる。
        val worldData = requireNotNull(worldData(context.arguments))
        val decision = productionDecision(context)
        if (!decision.available) {
            return productionUnavailablePresentation(context.player, decision.messageKey)
        }
        return if (storage.isProductionWorld(worldData.uuid)) {
            submissionPresentation(context.player, worldData.uuid)
        } else {
            productionPresentation(context.player)
        }
    }

    private fun productionDecision(
        context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext,
    ): ProductionDecision {
        val worldData = worldData(context.arguments)
            ?: return ProductionDecision(false, "production.unavailable.world_missing")
        if (plugin.isSubmissionClosed()) {
            return ProductionDecision(false, "production.unavailable.submission_closed")
        }
        if (globalStorage.getWorldCreationAvailability() != WorldCreationAvailability.PRODUCTION) {
            return ProductionDecision(false, "production.unavailable.phase")
        }
        if (protectionService.isProtected(worldData)) {
            return ProductionDecision(false, "production.unavailable.protected")
        }
        if (worldData.owner != context.player.uniqueId && !context.player.hasPermission("myworldmanager.admin")) {
            return ProductionDecision(false, "production.unavailable.owner_only")
        }
        if (!storage.isProductionWorld(worldData.uuid) && ownerHasProductionWorld(worldData.owner, worldData.uuid)) {
            return ProductionDecision(false, "production.unavailable.limit")
        }
        if (resolveCapabilityRoute(context) == null) {
            return ProductionDecision(false, "production.unavailable.state")
        }
        return ProductionDecision(true, "production.unavailable.world_missing")
    }

    private fun productionUnavailablePresentation(player: Player, messageKey: String) =
        capabilityPresentation(
            Material.BARRIER,
            i18nComponent(player, "production.unavailable.display"),
            structuredLore(listOf(GuiLoreLine.Warning(i18n(player, messageKey)))),
            GuiElementRole.CONTENT,
        )

    private data class ProductionDecision(val available: Boolean, val messageKey: String)

    private fun resolveCapabilityRoute(
        context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext,
    ): MenuRoute? {
        val worldData = worldData(context.arguments) ?: return null
        if (plugin.isSubmissionClosed()) return null
        val action = if (storage.isProductionWorld(worldData.uuid)) {
            when (runCatching { storage.getSubmissionState(worldData.uuid) }.getOrNull()) {
                SubmissionState.UNSUBMITTED -> ConfirmationAction.SUBMIT
                SubmissionState.SUBMITTED,
                SubmissionState.COMPLETED,
                null -> return null
            }
        } else {
            ConfirmationAction.SET_PRODUCTION
        }
        return MenuRoute(
            RUNTIME_OWNER,
            CONFIRMATION_ROUTE,
            mapOf(
                WORLD_UUID_ARGUMENT to worldData.uuid.toString(),
                CONFIRMATION_ACTION_ARGUMENT to action.key,
            ),
        )
    }

    private fun handleCapabilityAction(
        context: MenuCapabilityActionContext,
    ): MenuActionResult {
        val route = resolveCapabilityRoute(
            com.awabi2048.ccsystem.api.gui.MenuCapabilityContext(
                context.player,
                context.arguments,
            ),
        ) ?: return MenuActionResult.Ignored
        return MenuActionResult.Success(MenuUpdate.Navigate(route))
    }

    private fun capabilityActionText(
        context: com.awabi2048.ccsystem.api.gui.MenuCapabilityContext,
    ): String {
        val worldData = requireNotNull(worldData(context.arguments))
        return if (storage.isProductionWorld(worldData.uuid)) {
            i18n(context.player, "submission.lore.unsubmitted.action")
        } else {
            i18n(context.player, "production.lore.action")
        }
    }

    private fun renderConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val action = confirmationAction(route)
        val layout = ChanponGuiElements.confirmationLayout()
        val title = i18nComponent(player, "confirm.${action.key}.title")
        val gui = CCSystem.getAPI().getGuiElementService()
        return InventoryMenuView(
            layout.size,
            title,
            listOf(
                gui.menuDisplay(
                    GuiMenuDisplaySpec(
                        layout.previewSlot,
                        GuiItemSpec(
                            Material.PAPER,
                            GuiNameSpec.FixedLabel(title),
                            me.awabi2048.mwmchanpon.ui.framedLore(
                                i18nList(player, "confirm.${action.key}.lore")
                                    .map(GuiLoreLine::Text),
                                GuiLoreFrame.NONE,
                            ),
                            GuiElementRole.CONTENT,
                            1,
                        ),
                    ),
                ),
                confirmationEntry(
                    player,
                    layout.confirmSlot,
                    Material.LIME_CONCRETE,
                    "confirm.button.confirm.display",
                    GuiElementRole.CONFIRM,
                    ACTION_CONFIRM,
                ),
                confirmationEntry(
                    player,
                    layout.cancelSlot,
                    Material.RED_CONCRETE,
                    "confirm.button.cancel.display",
                    GuiElementRole.CANCEL,
                    ACTION_CANCEL,
                ),
            ),
        ).asConfirmation()
    }

    private fun confirmationEntry(
        player: Player,
        slot: Int,
        material: Material,
        textKey: String,
        role: GuiElementRole,
        actionId: String,
    ): MenuElement {
        val text = i18n(player, textKey)
        return CCSystem.getAPI().getGuiElementService().menuStructuredEntry(
            player,
            GuiStructuredMenuEntrySpec(
                slot = slot,
                item = GuiItemSpec(
                    material,
                    GuiNameSpec.FixedLabel(i18nComponent(player, textKey)),
                    GuiLoreSpec.None,
                    role,
                    1,
                ),
                actions = listOf(
                    chanponGestureAction(
                        actionId,
                        MenuGesture.ANY,
                        text,
                        safety = productionConfirmationSafety(actionId),
                    ),
                ),
            ),
        )
    }

    private fun confirm(context: MenuActionContext): MenuActionResult {
        val worldUuid = worldUuid(context.route) ?: return MenuActionResult.Rejected()
        val action = confirmationAction(context.route)
        executeConfirmedAction(context.player, worldUuid, action)
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun cancel(context: MenuActionContext): MenuActionResult {
        context.player.sendMessage(i18n(context.player, "message.cancelled_confirmation"))
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun productionConfirmationSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_CONFIRM -> MenuActionSafety.IRREVERSIBLE
        ACTION_CANCEL -> MenuActionSafety.NAVIGATION_ONLY
        else -> error("Production confirmation action must declare a dedicated safety mapping: $actionId")
    }

    private fun worldData(arguments: Map<String, String>): WorldData? =
        arguments[WORLD_UUID_ARGUMENT]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }

    private fun worldUuid(route: MenuRoute): UUID? =
        route.payload[WORLD_UUID_ARGUMENT]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun confirmationAction(route: MenuRoute): ConfirmationAction =
        route.payload[CONFIRMATION_ACTION_ARGUMENT]
            ?.let { key -> ConfirmationAction.entries.firstOrNull { it.key == key } }
            ?: error("確認操作が見つかりません")

    private fun executeConfirmedAction(player: Player, worldUuid: UUID, action: ConfirmationAction) {
        when (action) {
            ConfirmationAction.SET_PRODUCTION -> {
                if (!canUseProduction(player)) return
                val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
                if (worldData != null && ownerHasProductionWorld(worldData.owner, worldUuid)) {
                    player.sendMessage(i18n(player, "message.production_limit"))
                    return
                }
                storage.setProductionWorld(worldUuid, true)
                applyForcedTags(worldUuid)
                player.sendMessage(i18n(player, "message.production_set"))
            }
            ConfirmationAction.UNSET_PRODUCTION -> {
                storage.setProductionWorld(worldUuid, false)
                MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let {
                    protectionService.sync(it)
                    toolPermissionController.syncWorld(it)
                }
                player.sendMessage(i18n(player, "message.production_unset"))
            }
            ConfirmationAction.SUBMIT -> {
                val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
                if (worldData == null || worldData.owner != player.uniqueId) {
                    player.sendMessage(i18n(player, "message.owner_only_submit"))
                    return
                }
                if (!ensureSubmissionState(player, worldUuid, SubmissionState.UNSUBMITTED)) return
                storage.setSubmissionState(
                    worldUuid,
                    SubmissionState.SUBMITTED,
                    player.uniqueId,
                    player.name,
                    "submit"
                )
                storage.setVisibility(worldUuid, ChanponWorldVisibility.PUBLIC)
                MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)?.let {
                    protectionService.sync(it)
                    toolPermissionController.syncWorld(it)
                }
                player.sendMessage(i18n(player, "message.submitted"))
            }
        }
    }

    private fun ensureSubmissionState(player: Player, worldUuid: UUID, expected: SubmissionState): Boolean {
        val current = try {
            storage.getSubmissionState(worldUuid)
        } catch (ex: IllegalStateException) {
            player.sendMessage(i18n(player, "message.state_error"))
            return false
        }
        if (current != expected) {
            player.sendMessage(i18n(player, "message.state_changed"))
            return false
        }
        return true
    }

    private fun applyForcedTags(worldUuid: UUID) {
        // TODO: タグ保存は維持し、Chanpon仕様のメインメニューでは表示しないpresentation policyをMWMへ提供する。
        val tags = plugin.config.getStringList("production.forced_tags")
            .mapNotNull { raw ->
                MyWorldManagerApi.getWorldTagService()?.normalizeTagId(raw)
                    ?: raw.trim().lowercase().takeIf { it.isNotBlank() }
            }
            .distinct()
        if (tags.isEmpty()) return

        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        val worldData = repository.findByUuid(worldUuid) ?: return
        val missingTags = tags.filterNot { worldData.tags.contains(it) }
        if (missingTags.isEmpty()) return

        worldData.tags.addAll(missingTags)
        repository.save(worldData)
    }

    private fun ownerHasProductionWorld(ownerUuid: UUID, excludingWorldUuid: UUID?): Boolean {
        val repository = MyWorldManagerApi.getWorldRepository() ?: return false
        return repository.findAll()
            .any { it.owner == ownerUuid && it.uuid != excludingWorldUuid && storage.isProductionWorld(it.uuid) }
    }

    private fun canUseProduction(player: Player): Boolean {
        val decision = MyWorldManagerApi.checkWorldCreation(
            me.awabi2048.myworldmanager.api.extension.WorldCreationRequest(
                actor = player,
                player = player,
                operation = me.awabi2048.myworldmanager.api.extension.WorldCreationOperation.PRODUCTION,
                type = null
            )
        )
        if (!decision.allowed) decision.denialMessage?.let(player::sendMessage)
        return decision.allowed
    }

    private fun creationPresentation(enabled: Boolean) = MenuCapabilityPresentation(
        item = GuiItemSpec(
            material = if (enabled) Material.YELLOW_WOOL else Material.GRAY_WOOL,
            name = GuiNameSpec.FixedLabel(i18nComponent(null, "creation.display")),
            lore = me.awabi2048.mwmchanpon.ui.framedLore(
                listOf(
                    GuiLoreLine.Text(i18n(null, "creation.lore.description")),
                    GuiLoreLine.Warning(i18n(null, "creation.lore.delete_guard")),
                    GuiLoreLine.Data(
                        i18n(null, "creation.lore.state"),
                        i18n(null, if (enabled) "common.on" else "common.off"),
                        if (enabled) "§e" else "§7",
                    ),
                ),
                GuiLoreFrame.BOTH,
            ),
            role = GuiElementRole.ACTION,
            amount = 1,
        ),
        glint = enabled,
    )

    private fun productionPresentation(player: Player): MenuCapabilityPresentation =
        capabilityPresentation(
            Material.HEART_OF_THE_SEA,
            i18nComponent(player, "production.display"),
            structuredLore(
                listOf(
                    GuiLoreLine.Warning(i18n(player, "production.lore.limit_warning")),
                ),
            ),
        )

    private fun submissionPresentation(
        player: Player,
        worldUuid: UUID,
    ): MenuCapabilityPresentation {
        val state = runCatching { storage.getSubmissionState(worldUuid) }
            .getOrElse {
                return capabilityPresentation(
                    Material.BARRIER,
                    i18nComponent(player, "error.display"),
                    me.awabi2048.mwmchanpon.ui.framedLore(
                        i18nList(player, "error.lore").map(GuiLoreLine::Text),
                        GuiLoreFrame.BOTH,
                    ),
                    GuiElementRole.CONTENT,
                )
            }
        val material = when (state) {
            SubmissionState.UNSUBMITTED -> Material.BRICKS
            SubmissionState.SUBMITTED -> Material.FLOW_BANNER_PATTERN
            SubmissionState.COMPLETED -> Material.BOOK
        }
        return capabilityPresentation(
            material,
            i18nComponent(player, "submission.display"),
            createSubmissionLore(player, state),
            if (state == SubmissionState.UNSUBMITTED) {
                GuiElementRole.ACTION
            } else {
                GuiElementRole.CONTENT
            },
        )
    }

    private fun capabilityPresentation(
        material: Material,
        name: Component,
        lore: GuiLoreSpec,
        role: GuiElementRole = GuiElementRole.ACTION,
    ) = MenuCapabilityPresentation(
        GuiItemSpec(
            material = material,
            name = GuiNameSpec.FixedLabel(name),
            lore = lore,
            role = role,
            amount = 1,
        ),
    )

    private fun createSubmissionLore(player: Player, state: SubmissionState): GuiLoreSpec {
        val key = when (state) {
            SubmissionState.UNSUBMITTED -> "submission.lore.unsubmitted"
            SubmissionState.SUBMITTED -> "submission.lore.submitted"
            SubmissionState.COMPLETED -> "submission.lore.completed"
        }
        val stateLine = GuiLoreLine.Data(
            i18n(player, "progress.label"),
            i18n(player, "state.${state.i18nKey}"),
            when (state) {
                SubmissionState.UNSUBMITTED -> "§6"
                SubmissionState.SUBMITTED -> "§a"
                SubmissionState.COMPLETED -> "§b"
            }
        )
        return when (state) {
            SubmissionState.UNSUBMITTED -> structuredLore(listOf(
                stateLine,
                GuiLoreLine.Warning(i18n(player, "$key.warning"))
            ))
            SubmissionState.SUBMITTED,
            SubmissionState.COMPLETED -> structuredLore(listOf(
                stateLine,
                GuiLoreLine.Text(i18n(player, "$key.description")),
                GuiLoreLine.Warning(i18n(player, "$key.warning"))
            ))
        }
    }

    private fun structuredLore(lines: List<GuiLoreLine>): GuiLoreSpec =
        me.awabi2048.mwmchanpon.ui.framedLore(lines, GuiLoreFrame.BOTH)

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.submission.$key", placeholders)
    }

    companion object {
        const val CREATION_CAPABILITY_ID = "mwm-chanpon:production-selection"
        const val MENU_CAPABILITY_ID = "mwm-chanpon:world-production-submission"
        const val EXTRA_KEY = "chanpon.production"
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val CREATION_CAPABILITY_LOCAL_ID = "production-selection"
        private const val CAPABILITY_ID = "world-production-submission"
        private const val CONFIRMATION_ROUTE = "world-production-submission-confirmation"
        private const val WORLD_UUID_ARGUMENT = "world_uuid"
        private const val CONFIRMATION_ACTION_ARGUMENT = "action"
        private const val ACTION_CONFIRM = "confirm"
        private const val ACTION_CANCEL = "cancel"
    }

    private enum class ConfirmationAction(val key: String) {
        SET_PRODUCTION("set_production"),
        UNSET_PRODUCTION("unset_production"),
        SUBMIT("submit")
    }

}

internal data class CreationProductionSelectionProviderState(
    val operationId: UUID,
    val snapshot: ProductionToggleExtension.CreationProductionSelectionSnapshot,
) : com.awabi2048.ccsystem.api.gui.MenuReversibleOpaqueState {
    override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
        CREATION_PRODUCTION_PROVIDER_ID,
        "creation-production-selection",
        mapOf(
            "operationId" to operationId,
            "playerUuid" to snapshot.playerUuid,
            "beforeSelected" to snapshot.beforeSelected,
            "expectedSelected" to snapshot.expectedSelected,
            "worldName" to snapshot.worldName,
            "beforePending" to snapshot.beforePending,
            "expectedPending" to snapshot.expectedPending,
        ),
    ).snapshot()

    companion object {
        fun decode(value: Any?): CreationProductionSelectionProviderState? {
            val payload = decodeChanponOpaqueState(
                value,
                CREATION_PRODUCTION_PROVIDER_ID,
                "creation-production-selection",
                setOf("operationId", "playerUuid", "beforeSelected", "expectedSelected", "worldName", "beforePending", "expectedPending"),
            )?.payload ?: return null
            val worldName = when (val value = payload["worldName"]) {
                null -> null
                is String -> value
                else -> return null
            }
            fun nullableBoolean(key: String): Boolean? = when (val value = payload[key]) {
                null -> null
                is Boolean -> value
                else -> return null
            }
            return CreationProductionSelectionProviderState(
                payload["operationId"] as? UUID ?: return null,
                ProductionToggleExtension.CreationProductionSelectionSnapshot(
                    payload["playerUuid"] as? UUID ?: return null,
                    payload["beforeSelected"] as? Boolean ?: return null,
                    payload["expectedSelected"] as? Boolean ?: return null,
                    worldName,
                    nullableBoolean("beforePending"),
                    nullableBoolean("expectedPending"),
                ),
            )
        }
    }
}

internal class CreationProductionSelectionProvider(
    private val stateStore: CreationProductionSelectionState,
) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val draft = context.interaction.attributes[CreationConfirmationCapabilityContract.DRAFT_ATTRIBUTE]
            as? WorldCreationDraft
            ?: return MenuReversibleProviderCaptureResult.Rejected("creation draft is missing")
        val (operationId, snapshot) = stateStore.capture(context.player.uniqueId, draft)
        return MenuReversibleProviderCaptureResult.Captured(CreationProductionSelectionProviderState(operationId, snapshot))
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = CreationProductionSelectionProviderState.decode(context.stateSnapshot.value)
            ?: return MenuReversibleProviderRestoreResult.Rejected("invalid creation production selection state type")
        return if (stateStore.restore(state.operationId, state.snapshot)) {
            MenuReversibleProviderRestoreResult.Restored
        } else {
            MenuReversibleProviderRestoreResult.Rejected("creation production selection changed concurrently")
        }
    }

    companion object {
        fun register(registry: MenuReversibleStateProviderRegistry, stateStore: CreationProductionSelectionState) {
            registry.register(
                MenuReversibleStateProviderDefinition(
                    "mwm-chanpon",
                    "creation-production-selection",
                    CreationProductionSelectionProvider(stateStore),
                ),
            )
        }
    }
}

private const val CREATION_PRODUCTION_PROVIDER_ID = "mwm-chanpon:creation-production-selection"
