package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmMemberAddSource
import me.awabi2048.myworldmanager.api.extension.WorldMenuAccessProvider
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChanponWorldMenuAccessProvider(
    private val storage: ChanponWorldStorage
) : WorldMenuAccessProvider, Listener {
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

        pendingPasswordInputs[player.uniqueId] = PasswordRequest(worldData.uuid, showBackButton)
        showPasswordDialog(player, worldData)
        return true
    }

    @EventHandler
    fun onPasswordInput(event: PlayerCustomClickEvent) {
        val identifier = event.identifier
        if (identifier != PASSWORD_SUBMIT && identifier != PASSWORD_CANCEL) return

        val conn = event.commonConnection as? PlayerGameConnection ?: return
        val player = conn.player
        val request = pendingPasswordInputs.remove(player.uniqueId) ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(request.worldUuid) ?: return
        if (identifier == PASSWORD_CANCEL) return

        val password = event.getDialogResponseView()?.getText("protection_password").orEmpty()
        if (!storage.matchesProtectionPassword(worldData.uuid, password)) {
            player.sendMessage(i18n(player, "protection.password_invalid"))
            pendingPasswordInputs[player.uniqueId] = request
            showPasswordDialog(player, worldData)
            return
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
        openWorldSettings(player, worldData, request.showBackButton)
    }

    private fun showPasswordDialog(player: Player, worldData: WorldData) {
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(i18nComponent(player, "protection.password_title"))
                        .body(listOf(DialogBody.plainMessage(i18nComponent(player, "protection.password_body"))))
                        .inputs(
                            listOf(
                                DialogInput.text("protection_password", i18nComponent(player, "protection.password_label"))
                                    .maxLength(64)
                                    .build()
                            )
                        )
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        ActionButton.create(
                            i18nComponent(player, "protection.password_submit"),
                            null,
                            100,
                            DialogAction.customClick(PASSWORD_SUBMIT, null)
                        ),
                        ActionButton.create(
                            i18nComponent(player, "common.back"),
                            null,
                            200,
                            DialogAction.customClick(PASSWORD_CANCEL, null)
                        )
                    )
                )
        }
        player.showDialog(dialog)
    }

    private fun openWorldSettings(player: Player, worldData: WorldData, showBackButton: Boolean) {
        val mwm = JavaPlugin.getPlugin(MyWorldManager::class.java)
        mwm.settingsSessionManager.updateSessionAction(
            player,
            worldData.uuid,
            SettingsAction.VIEW_SETTINGS,
            isGui = true,
            isAdminFlow = false
        )
        mwm.menuEntryRouter.openWorldSettings(player, worldData, showBackButton)
    }

    private fun i18n(player: Player?, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.environment.$key")

    private fun i18nComponent(player: Player?, key: String): Component =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.environment.$key")

    private data class PasswordRequest(
        val worldUuid: UUID,
        val showBackButton: Boolean
    )

    private companion object {
        private val PASSWORD_SUBMIT = Key.key("mwmchanpon:protection/password_submit")
        private val PASSWORD_CANCEL = Key.key("mwmchanpon:protection/password_cancel")
    }
}
