package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmMemberAddSource
import me.awabi2048.myworldmanager.api.extension.WorldMenuAccessProvider
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChanponWorldMenuAccessProvider(
    private val storage: ChanponWorldStorage
) : WorldMenuAccessProvider {
    private val pendingPasswordInputs = ConcurrentHashMap<UUID, PasswordRequest>()

    override fun getId(): String = "mwmchanpon.world_menu_access"

    override fun open(player: Player, worldData: WorldData, showBackButton: Boolean): Boolean {
        if (storage.getProtectionMode(worldData.uuid) != ChanponWorldProtectionMode.PASSWORD) {
            return false
        }
        if (!storage.hasProtectionPassword(worldData.uuid)) {
            player.sendMessage(i18n(player, "protection.password_not_configured"))
            return true
        }

        pendingPasswordInputs[player.uniqueId] = PasswordRequest(worldData.uuid)
        val opened = showPasswordDialog(player, worldData)
        if (!opened) pendingPasswordInputs.remove(player.uniqueId)
        return opened
    }

    private fun submitPassword(player: Player, password: String): MenuActionResult {
        val request = pendingPasswordInputs.remove(player.uniqueId)
            ?: return MenuActionResult.Rejected()
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(request.worldUuid)
            ?: return MenuActionResult.Rejected()

        if (!storage.matchesProtectionPassword(worldData.uuid, password)) {
            player.sendMessage(i18n(player, "protection.password_invalid"))
            pendingPasswordInputs[player.uniqueId] = request
            showPasswordDialog(player, worldData)
            return MenuActionResult.Success(MenuUpdate.None)
        }

        MyWorldManagerApi.getMemberManager()?.addMemberDirect(
            player = player,
            worldUuid = worldData.uuid,
            addedByUuid = worldData.owner,
            source = MwmMemberAddSource.EXTERNAL,
            providerId = "mwm-chanpon",
            detail = "password"
        )
        player.sendMessage(i18n(player, "protection.password_success"))
        openWorldSettings(player, worldData)
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun showPasswordDialog(player: Player, worldData: WorldData): Boolean {
        return runCatching {
            CCSystem.getAPI().getMenuDialogService().show(
                player,
                MenuDialogRequest(
                owner = OWNER,
                id = PASSWORD_DIALOG,
                title = i18nComponent(player, "protection.password_title"),
                body = listOf(i18nComponent(player, "protection.password_body")),
                inputs = listOf(
                    MenuDialogInput.Text(
                        PASSWORD_INPUT,
                        i18nComponent(player, "protection.password_label"),
                        maxLength = 64,
                    ),
                ),
                confirm = MenuDialogButton(
                    i18nComponent(player, "protection.password_submit"),
                    MenuDialogHandler { actor, response ->
                        submitPassword(actor, response.textValue(PASSWORD_INPUT))
                    },
                ),
                cancel = MenuDialogButton(
                    i18nComponent(player, "common.back"),
                    MenuDialogHandler { actor, _ ->
                        pendingPasswordInputs.remove(actor.uniqueId)
                        val runtime = CCSystem.getAPI().getMenuRuntimeService()
                        CCSystem.getAPI().getMenuNavigationService().currentRoute(actor)?.let { route ->
                            runtime.replace(actor, route)
                        }
                        MenuActionResult.Success(MenuUpdate.None)
                    },
                ),
                ),
            )
            true
        }.getOrElse { error ->
            JavaPlugin.getPlugin(MyWorldManager::class.java).logger.warning(
                "Failed to show password dialog for ${worldData.uuid}: ${error.message}"
            )
            false
        }
    }

    private fun openWorldSettings(player: Player, worldData: WorldData) {
        val mwm = JavaPlugin.getPlugin(MyWorldManager::class.java)
        mwm.settingsSessionManager.updateSessionAction(
            player,
            worldData.uuid,
            SettingsAction.VIEW_SETTINGS,
            isGui = true,
            isAdminFlow = false
        )
        mwm.worldSettingsGui.open(player, worldData)
    }

    private fun i18n(player: Player?, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.environment.$key")

    private fun i18nComponent(player: Player?, key: String): Component =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.environment.$key")

    private data class PasswordRequest(val worldUuid: UUID)

    private companion object {
        private const val OWNER = "mwm-chanpon"
        private const val PASSWORD_DIALOG = "protection-password"
        private const val PASSWORD_INPUT = "protection_password"
    }
}
