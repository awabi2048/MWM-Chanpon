package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import com.awabi2048.ccsystem.api.gui.MenuTargetPolicy
import com.awabi2048.ccsystem.api.gui.PublicMenuDefinition
import com.awabi2048.ccsystem.api.gui.PublicMenuOpener
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmMemberAddSource
import me.awabi2048.myworldmanager.api.extension.WorldMenuAccessChallenge
import me.awabi2048.myworldmanager.api.extension.WorldMenuAccessContext
import me.awabi2048.myworldmanager.api.extension.WorldMenuAccessPolicy
import me.awabi2048.myworldmanager.api.extension.WorldSettingsNavigationRequest
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChanponWorldMenuAccessPolicy(
    private val storage: ChanponWorldStorage
) : WorldMenuAccessPolicy {
    private val pendingPasswordInputs = ConcurrentHashMap<UUID, PasswordRequest>()

    init {
        CCSystem.getAPI().getMenuCommandService().register(
            PublicMenuDefinition(
                owner = OWNER,
                id = PUBLIC_MENU_ID,
                permission = null,
                targetPolicy = MenuTargetPolicy.SELF_ONLY,
                argumentKeys = setOf(WORLD_UUID),
                opener = PublicMenuOpener(::openChallenge),
            ),
        )
    }

    override fun getId(): String = "mwmchanpon.world_menu_access"

    override fun challenge(context: WorldMenuAccessContext): WorldMenuAccessChallenge? {
        val worldData = context.worldData
        if (!storage.hasCurrentWorldData(worldData.uuid)) return null
        if (storage.getProtectionMode(worldData.uuid) != ChanponWorldProtectionMode.PASSWORD) {
            return null
        }
        return WorldMenuAccessChallenge(
            publicMenuRouteId = "$OWNER:$PUBLIC_MENU_ID",
            arguments = mapOf(WORLD_UUID to worldData.uuid.toString()),
        )
    }

    private fun openChallenge(player: Player, arguments: Map<String, String>): Boolean {
        val worldData = arguments[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }
            ?: return false
        if (!storage.hasCurrentWorldData(worldData.uuid)) return false
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
            return MenuActionResult.Success(MenuUpdate.Refresh)
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
        return openWorldSettings(player, worldData)
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
                        MenuActionResult.Success(MenuUpdate.Resume)
                    },
                ),
                ),
            )
            true
        }.getOrElse { error ->
            Bukkit.getLogger().warning(
                "Failed to show password dialog for ${worldData.uuid}: ${error.message}"
            )
            false
        }
    }

    private fun openWorldSettings(player: Player, worldData: WorldData): MenuActionResult {
        val route = MyWorldManagerApi.prepareWorldSettingsRoute(
            player,
            worldData.uuid,
            WorldSettingsNavigationRequest(
                showBackButton = true,
                isAdminFlow = false,
            ),
        ) ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Navigate(route))
    }

    private fun i18n(player: Player?, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.environment.$key")

    private fun i18nComponent(player: Player?, key: String): Component =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.environment.$key")

    private data class PasswordRequest(val worldUuid: UUID)

    private companion object {
        private const val OWNER = "mwm-chanpon"
        private const val PUBLIC_MENU_ID = "world-menu-password"
        private const val PASSWORD_DIALOG = "protection-password"
        private const val PASSWORD_INPUT = "protection_password"
        private const val WORLD_UUID = "world_uuid"
    }
}
