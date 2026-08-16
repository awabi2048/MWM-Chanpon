package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.LocalizationKey

import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiStructuredMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.service.BackupAutomationService
import me.awabi2048.mwmchanpon.service.ChanponReversibleContracts
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.mwmchanpon.service.ChanponReversibleStateProviders
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

class AutomationSettingsMenu(
    private val plugin: MWMChanponPlugin,
    private val backupService: BackupService,
    private val automationService: BackupAutomationService,
    private val onSettingsChanged: () -> Unit = {},
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        ChanponReversibleStateProviders.registerAutomation(
            CCSystem.getAPI().getMenuReversibleStateProviderRegistry(),
            plugin,
        ) {
            automationService.start()
            onSettingsChanged()
        }
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_MAIN,
                renderer = { context -> renderMain(context.player) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler(::back),
                    ACTION_AUTO_BACKUP to MenuActionHandler { context ->
                        showToggleConfirmation(context, AutomationToggle.AUTO_BACKUP)
                    },
                    ACTION_AUTO_ROLLBACK to MenuActionHandler { context ->
                        showToggleConfirmation(context, AutomationToggle.AUTO_ROLLBACK)
                    },
                    ACTION_RESTORE_POINT to MenuActionHandler {
                        MenuActionResult.Success(
                            MenuUpdate.Navigate(MenuRoute(OWNER, ROUTE_RESTORE_CONFIRM)),
                        )
                    },
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_TOGGLE_CONFIRM,
                renderer = { context -> renderToggleConfirmation(context.player, context.route) },
                actions = mapOf(
                    ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM_TOGGLE to MenuActionHandler(::confirmToggle),
                ),
            ),
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_RESTORE_CONFIRM,
                renderer = { context -> renderRestorePointConfirmation(context.player) },
                actions = mapOf(
                    ACTION_CANCEL to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CONFIRM_RESTORE_POINT to MenuActionHandler(::confirmRestorePoint),
                ),
            ),
        )
    }

    fun open(player: Player) {
        if (!player.hasPermission("mwmchanpon.admin")) return
        runtime.navigate(player, route())
    }

    internal fun route(): MenuRoute = MenuRoute(OWNER, ROUTE_MAIN)

    private fun renderMain(player: Player): InventoryMenuView {
        val elements = mutableListOf(
            toggleEntry(
                player, 20, Material.CLOCK, AutomationToggle.AUTO_BACKUP.menu,
                plugin.config.getBoolean("backup.auto_backup.enabled", false),
                ACTION_AUTO_BACKUP,
            ),
            toggleEntry(
                player, 24, Material.RECOVERY_COMPASS, AutomationToggle.AUTO_ROLLBACK.menu,
                plugin.config.getBoolean("backup.auto_rollback.enabled", false),
                ACTION_AUTO_ROLLBACK,
            ),
            simpleActionEntry(
                player,
                40,
                Material.REDSTONE,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_BACK),
                GuiElementRole.BACK,
                ACTION_BACK,
                i18n(player, ChanponBackupKeys.CHANPON_BACKUP_COMMON_BACK_ACTION),
            ),
        )
        if (plugin.config.getBoolean("backup.auto_rollback.enabled", false)) {
            elements += CCSystem.getAPI().getGuiElementService().menuEntry(
                player,
                GuiMenuEntrySpec(
                    slot = 36,
                    material = Material.END_CRYSTAL,
                    name = GuiNameSpec.FixedLabel(i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_RESTORE_POINT_DISPLAY)),
                    role = GuiElementRole.ACTION,
                    description = i18nList(player, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_RESTORE_POINT_LORE),
                    actions = listOf(chanponGestureAction(
                        ACTION_RESTORE_POINT,
                        MenuGesture.ANY,
                        i18n(player, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_RESTORE_POINT_ACTION),
                        safety = MenuActionSafety.CONFIRM_ENTRY,
                    )),
                ),
            )
        }
        return InventoryMenuView(45, i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_TITLE), elements)
    }

    private fun renderToggleConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val target = AutomationToggle.fromRoute(route.payload[TOGGLE_ID])
        val current = plugin.config.getBoolean(target.configPath, false)
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, target.confirmationTitle),
            listOf(
                centerEntry(
                    player,
                    layout.previewSlot,
                        player,
                        Material.LEVER,
                        target.confirmation,
                        mapOf("state" to i18n(player, if (!current) ChanponBackupKeys.CHANPON_BACKUP_COMMON_ON else ChanponBackupKeys.CHANPON_BACKUP_COMMON_OFF)),
                ),
                simpleActionEntry(
                    player,
                    layout.confirmSlot,
                    Material.LIME_CONCRETE,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CONFIRM),
                    GuiElementRole.CONFIRM,
                    ACTION_CONFIRM_TOGGLE,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CONFIRM_ACTION),
                    reversibleContract = ChanponReversibleContracts.automation(target.configPath),
                    classification = ChanponReversibleActionClassification.Operation.AUTOMATION_TOGGLE,
                ),
                simpleActionEntry(
                    player,
                    layout.cancelSlot,
                    Material.RED_CONCRETE,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CANCEL),
                    GuiElementRole.CANCEL,
                    ACTION_CANCEL,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CANCEL_ACTION),
                ),
            ),
        ).asConfirmation()
    }

    private fun renderRestorePointConfirmation(player: Player): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_RESTORE_POINT_TITLE),
            listOf(
                centerEntry(
                    player,
                    layout.previewSlot,
                    player,
                    Material.END_CRYSTAL,
                    LocalizedContent(
                        ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_RESTORE_POINT_DISPLAY,
                        ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_RESTORE_POINT_LORE,
                    ),
                ),
                simpleActionEntry(
                    player,
                    layout.confirmSlot,
                    Material.LIME_CONCRETE,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CONFIRM),
                    GuiElementRole.CONFIRM,
                    ACTION_CONFIRM_RESTORE_POINT,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CONFIRM_ACTION),
                ),
                simpleActionEntry(
                    player,
                    layout.cancelSlot,
                    Material.RED_CONCRETE,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CANCEL),
                    GuiElementRole.CANCEL,
                    ACTION_CANCEL,
                    i18n(player, ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_BUTTON_CANCEL_ACTION),
                ),
            ),
        ).asConfirmation()
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun showToggleConfirmation(
        context: MenuActionContext,
        target: AutomationToggle,
    ): MenuActionResult = MenuActionResult.Success(
        MenuUpdate.Navigate(
            MenuRoute(
                OWNER,
                ROUTE_TOGGLE_CONFIRM,
                mapOf(
                    TOGGLE_ID to target.routeId,
                ),
            ),
        ),
    )

    private fun confirmToggle(context: MenuActionContext): MenuActionResult {
        val target = runCatching { AutomationToggle.fromRoute(context.route.payload[TOGGLE_ID]) }
            .getOrElse { return MenuActionResult.Rejected() }
        if (!plugin.isConfigurationMigrationCurrent()) return MenuActionResult.Rejected()
        plugin.config.set(target.configPath, !plugin.config.getBoolean(target.configPath, false))
        plugin.saveConfigIfCurrent()
        automationService.start()
        onSettingsChanged()
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun confirmRestorePoint(context: MenuActionContext): MenuActionResult {
        val player = context.player
        backupService.createAllBackup(
            automationService.productionWorldUuids(),
            BackupKind.RESTORE_POINT,
            player.uniqueId,
            player.name,
            "restore_point",
            null,
        ).thenAccept { result ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.sendMessage(
                    i18nComponent(
                        player,
                        if (result.status == BackupOperationStatus.SUCCESS) {
                            ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_POINT_SUCCESS
                        } else {
                            ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_BACKUP_FAILED
                        },
                    ),
                )
                open(player)
            })
        }
        return MenuActionResult.Success(MenuUpdate.Close)
    }

    private fun toggleEntry(
        player: Player,
        slot: Int,
        material: Material,
        presentation: LocalizedContent,
        enabled: Boolean,
        actionId: String,
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.FixedLabel(i18nComponent(player, presentation.display)),
            role = GuiElementRole.ACTION,
            description = i18nList(
                player,
                presentation.lore,
                mapOf("state" to i18n(player, if (enabled) ChanponBackupKeys.CHANPON_BACKUP_COMMON_ON else ChanponBackupKeys.CHANPON_BACKUP_COMMON_OFF)),
            ),
            actions = listOf(chanponGestureAction(
                actionId,
                MenuGesture.ANY,
                i18n(player, requireNotNull(presentation.action)),
                safety = automationActionSafety(actionId),
            )),
            glint = enabled,
        ),
    )

    private fun centerEntry(
        viewer: Player,
        slot: Int,
        player: Player,
        material: Material,
        presentation: LocalizedContent,
        placeholders: Map<String, Any> = emptyMap(),
    ): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuDisplay(
            GuiMenuDisplaySpec(
                slot,
                GuiItemSpec(
                    material,
                    GuiNameSpec.FixedLabel(i18nComponent(player, presentation.display, placeholders)),
                    me.awabi2048.mwmchanpon.ui.framedLore(
                        i18nList(player, presentation.lore, placeholders).map(GuiLoreLine::Text),
                        GuiLoreFrame.BOTH,
                    ),
                    GuiElementRole.CONTENT,
                    1,
                ),
            ),
        )

    private fun simpleActionEntry(
        player: Player,
        slot: Int,
        material: Material,
        text: String,
        role: GuiElementRole,
        actionId: String,
        actionText: String,
        reversibleContract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract? = null,
        classification: ChanponReversibleActionClassification.Operation? = null,
    ): MenuElement {
        if (role == GuiElementRole.BACK) return CCSystem.getAPI().getGuiElementService().backEntry(player, slot)
        return CCSystem.getAPI().getGuiElementService().menuStructuredEntry(
            player,
            GuiStructuredMenuEntrySpec(
                slot,
                GuiItemSpec(
                    material,
                    me.awabi2048.mwmchanpon.ui.fixedName(text, com.awabi2048.ccsystem.api.gui.GuiNameStyle.DEFAULT),
                    GuiLoreSpec.None,
                    role,
                    1,
                ),
                listOf(chanponGestureAction(
                    actionId,
                    MenuGesture.ANY,
                    actionText,
                    safety = automationActionSafety(actionId),
                    reversibleContract = reversibleContract,
                    classification = classification,
                )),
            ),
        )
    }

    private fun i18n(player: Player?, key: LocalizationKey<String>) =
        CCSystem.getAPI().getLocalized(player, key)

    private fun i18nComponent(
        player: Player?,
        key: LocalizationKey<String>,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nComponent(player, key, placeholders)

    private fun i18nList(
        player: Player?,
        key: LocalizationKey<List<String>>,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getLocalized(player, key, placeholders)

    private fun automationActionSafety(actionId: String): MenuActionSafety = when (actionId) {
        ACTION_BACK, ACTION_CANCEL -> MenuActionSafety.NAVIGATION_ONLY
        ACTION_AUTO_BACKUP, ACTION_AUTO_ROLLBACK, ACTION_RESTORE_POINT -> MenuActionSafety.CONFIRM_ENTRY
        ACTION_CONFIRM_TOGGLE -> MenuActionSafety.REVERSIBLE
        ACTION_CONFIRM_RESTORE_POINT -> MenuActionSafety.IRREVERSIBLE
        else -> error("Automation action must declare a dedicated safety mapping: $actionId")
    }

    /** 画面遷移では有限なIDだけを渡し、言語キーは型付き定義から復元します。 */
    private enum class AutomationToggle(
        val routeId: String,
        val configPath: String,
        val menu: LocalizedContent,
        val confirmationTitle: LocalizationKey<String>,
        val confirmation: LocalizedContent,
    ) {
        AUTO_BACKUP(
            "auto-backup",
            "backup.auto_backup.enabled",
            LocalizedContent(
                ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_AUTO_BACKUP_DISPLAY,
                ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_AUTO_BACKUP_LORE,
                ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_AUTO_BACKUP_ACTION,
            ),
            ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_AUTO_BACKUP_TOGGLE_TITLE,
            LocalizedContent(
                ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_AUTO_BACKUP_TOGGLE_DISPLAY,
                ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_AUTO_BACKUP_TOGGLE_LORE,
            ),
        ),
        AUTO_ROLLBACK(
            "auto-rollback",
            "backup.auto_rollback.enabled",
            LocalizedContent(
                ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_AUTO_ROLLBACK_DISPLAY,
                ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_AUTO_ROLLBACK_LORE,
                ChanponBackupKeys.CHANPON_BACKUP_AUTOMATION_AUTO_ROLLBACK_ACTION,
            ),
            ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_AUTO_ROLLBACK_TOGGLE_TITLE,
            LocalizedContent(
                ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_AUTO_ROLLBACK_TOGGLE_DISPLAY,
                ChanponBackupKeys.CHANPON_BACKUP_CONFIRM_AUTO_ROLLBACK_TOGGLE_LORE,
            ),
        );

        companion object {
            fun fromRoute(raw: String?): AutomationToggle =
                entries.firstOrNull { it.routeId == raw }
                    ?: error("未知の自動化設定IDです: $raw")
        }
    }

    private data class LocalizedContent(
        val display: LocalizationKey<String>,
        val lore: LocalizationKey<List<String>>,
        val action: LocalizationKey<String>? = null,
    )

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE_MAIN = "automation-settings"
        private const val ROUTE_TOGGLE_CONFIRM = "automation-toggle-confirm"
        private const val ROUTE_RESTORE_CONFIRM = "automation-restore-point-confirm"
        private const val ACTION_BACK = "back"
        private const val ACTION_AUTO_BACKUP = "auto-backup"
        private const val ACTION_AUTO_ROLLBACK = "auto-rollback"
        private const val ACTION_RESTORE_POINT = "restore-point"
        private const val ACTION_CONFIRM_TOGGLE = "confirm-toggle"
        private const val ACTION_CONFIRM_RESTORE_POINT = "confirm-restore-point"
        private const val ACTION_CANCEL = "cancel"
        private const val TOGGLE_ID = "toggle-id"
    }
}
