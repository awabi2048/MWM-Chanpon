package me.awabi2048.mwmchanpon.ui

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
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.service.BackupAutomationService
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
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_MAIN,
                renderer = { context -> renderMain(context.player) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler(::back),
                    ACTION_AUTO_BACKUP to MenuActionHandler { context ->
                        showToggleConfirmation(
                            context,
                            "backup.auto_backup.enabled",
                            "confirm.auto_backup_toggle",
                        )
                    },
                    ACTION_AUTO_ROLLBACK to MenuActionHandler { context ->
                        showToggleConfirmation(
                            context,
                            "backup.auto_rollback.enabled",
                            "confirm.auto_rollback_toggle",
                        )
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
                player, 20, Material.CLOCK, "automation.auto_backup",
                plugin.config.getBoolean("backup.auto_backup.enabled", false),
                ACTION_AUTO_BACKUP,
            ),
            toggleEntry(
                player, 24, Material.RECOVERY_COMPASS, "automation.auto_rollback",
                plugin.config.getBoolean("backup.auto_rollback.enabled", false),
                ACTION_AUTO_ROLLBACK,
            ),
            simpleActionEntry(
                player,
                40,
                Material.REDSTONE,
                i18n(player, "common.back"),
                GuiElementRole.BACK,
                ACTION_BACK,
                i18n(player, "common.back_action"),
            ),
        )
        if (plugin.config.getBoolean("backup.auto_rollback.enabled", false)) {
            elements += CCSystem.getAPI().getGuiElementService().menuEntry(
                player,
                GuiMenuEntrySpec(
                    slot = 36,
                    material = Material.END_CRYSTAL,
                    name = GuiNameSpec.Component(i18nComponent(player, "automation.restore_point.display")),
                    role = GuiElementRole.ACTION,
                    description = i18nList(player, "automation.restore_point.lore"),
                    actions = listOf(GuiMenuActionIntent.GestureAction(
                        ACTION_RESTORE_POINT,
                        MenuGesture.ANY,
                        i18n(player, "automation.restore_point.action"),
                    )),
                ),
            )
        }
        return InventoryMenuView(45, i18nComponent(player, "automation.title"), elements)
    }

    private fun renderToggleConfirmation(player: Player, route: MenuRoute): InventoryMenuView {
        val configPath = route.payload[CONFIG_PATH]
            ?: error("切替対象の設定パスがありません")
        val translationKey = route.payload[TRANSLATION_KEY]
            ?: error("切替確認の言語キーがありません")
        val current = plugin.config.getBoolean(configPath, false)
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "$translationKey.title"),
            listOf(
                centerEntry(
                    player,
                    layout.previewSlot,
                        player,
                        Material.LEVER,
                        translationKey,
                        mapOf("state" to i18n(player, if (!current) "common.on" else "common.off")),
                ),
                simpleActionEntry(
                    player,
                    layout.confirmSlot,
                    Material.LIME_CONCRETE,
                    i18n(player, "confirm.button.confirm"),
                    GuiElementRole.CONFIRM,
                    ACTION_CONFIRM_TOGGLE,
                    i18n(player, "confirm.button.confirm_action"),
                ),
                simpleActionEntry(
                    player,
                    layout.cancelSlot,
                    Material.RED_CONCRETE,
                    i18n(player, "confirm.button.cancel"),
                    GuiElementRole.CANCEL,
                    ACTION_CANCEL,
                    i18n(player, "confirm.button.cancel_action"),
                ),
            ),
        )
    }

    private fun renderRestorePointConfirmation(player: Player): InventoryMenuView {
        val layout = ChanponGuiElements.confirmationLayout()
        return InventoryMenuView(
            layout.size,
            i18nComponent(player, "confirm.restore_point.title"),
            listOf(
                centerEntry(player, layout.previewSlot, player, Material.END_CRYSTAL, "confirm.restore_point"),
                simpleActionEntry(
                    player,
                    layout.confirmSlot,
                    Material.LIME_CONCRETE,
                    i18n(player, "confirm.button.confirm"),
                    GuiElementRole.CONFIRM,
                    ACTION_CONFIRM_RESTORE_POINT,
                    i18n(player, "confirm.button.confirm_action"),
                ),
                simpleActionEntry(
                    player,
                    layout.cancelSlot,
                    Material.RED_CONCRETE,
                    i18n(player, "confirm.button.cancel"),
                    GuiElementRole.CANCEL,
                    ACTION_CANCEL,
                    i18n(player, "confirm.button.cancel_action"),
                ),
            ),
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun showToggleConfirmation(
        context: MenuActionContext,
        configPath: String,
        translationKey: String,
    ): MenuActionResult = MenuActionResult.Success(
        MenuUpdate.Navigate(
            MenuRoute(
                OWNER,
                ROUTE_TOGGLE_CONFIRM,
                mapOf(
                    CONFIG_PATH to configPath,
                    TRANSLATION_KEY to translationKey,
                ),
            ),
        ),
    )

    private fun confirmToggle(context: MenuActionContext): MenuActionResult {
        val configPath = context.route.payload[CONFIG_PATH]
            ?: return MenuActionResult.Rejected()
        plugin.config.set(configPath, !plugin.config.getBoolean(configPath, false))
        plugin.saveConfig()
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
                            "message.restore_point_success"
                        } else {
                            "message.backup_failed"
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
        key: String,
        enabled: Boolean,
        actionId: String,
    ): MenuElement = CCSystem.getAPI().getGuiElementService().menuEntry(
        player,
        GuiMenuEntrySpec(
            slot = slot,
            material = material,
            name = GuiNameSpec.Component(i18nComponent(player, "$key.display")),
            role = GuiElementRole.ACTION,
            description = i18nList(
                player,
                "$key.lore",
                mapOf("state" to i18n(player, if (enabled) "common.on" else "common.off")),
            ),
            actions = listOf(GuiMenuActionIntent.GestureAction(
                actionId,
                MenuGesture.ANY,
                i18n(player, "$key.action"),
            )),
            glint = enabled,
        ),
    )

    private fun centerEntry(
        viewer: Player,
        slot: Int,
        player: Player,
        material: Material,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuDisplay(
            GuiMenuDisplaySpec(
                slot,
                GuiItemSpec(
                    material,
                    GuiNameSpec.Component(i18nComponent(player, "$key.display", placeholders)),
                    GuiLoreSpec.Rich(
                        i18nList(player, "$key.lore", placeholders).map(GuiLoreLine::Text),
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
    ): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuStructuredEntry(
            player,
            GuiStructuredMenuEntrySpec(
                slot,
                GuiItemSpec(
                    material,
                    GuiNameSpec.Text(text, com.awabi2048.ccsystem.api.gui.GuiNameStyle.DEFAULT),
                    GuiLoreSpec.None,
                    role,
                    1,
                ),
                listOf(GuiMenuActionIntent.GestureAction(actionId, MenuGesture.ANY, actionText)),
            ),
        )

    private fun i18n(player: Player?, key: String) =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")

    private fun i18nComponent(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(
        player: Player?,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

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
        private const val CONFIG_PATH = "config-path"
        private const val TRANSLATION_KEY = "translation-key"
    }
}
