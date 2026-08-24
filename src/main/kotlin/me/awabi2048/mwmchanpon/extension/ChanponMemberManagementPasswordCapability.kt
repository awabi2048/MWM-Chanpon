package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponEnvironmentKeys
import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.ui.chanponCapabilityAction
import me.awabi2048.mwmchanpon.ui.optionalAvailability
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.MemberManagementCapabilityContract
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * メンバー参加パスワードの設定ボタンを、メンバー管理メニューのフッター3マス目へ提供するcapability。
 *
 * 環境メニューからパスワード設定をメンバー管理へ完全に移した後の設定入口です。
 * 建築許可（保護モード）とは完全に切り離し、モードに関係なく常に表示・設定できます。
 * 設定済みパスワードは伏字（＊）で表示し、平文を画面へ出しません。
 * 非メンバーが /worldmenu でパスワードを入力するとメンバーへ自動追加される旨も説明に含めます。
 */
class ChanponMemberManagementPasswordCapability(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val buildPermissionService: WorldBuildPermissionService,
) {
    init {
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = CAPABILITY_OWNER,
                id = CAPABILITY_ID,
                placement = MemberManagementCapabilityContract.FOOTER_PLACEMENT,
                availability = optionalAvailability { context ->
                    val world = worldData(context)
                    val allowed = world != null &&
                        (context.player.uniqueId == world.owner || context.player.hasPermission("myworldmanager.admin"))
                    // TODO: クリック反応なしの原因特定後、このデバッグログを除去する。
                    plugin.logger.info(
                        "[MWMChanpon-password] availability world=${world?.uuid} owner=${world?.owner} " +
                            "player=${context.player.uniqueId} admin=${context.player.hasPermission("myworldmanager.admin")} allowed=$allowed",
                    )
                    allowed
                },
                presentationProvider = MenuCapabilityPresentationProvider { context ->
                    val world = requireNotNull(worldData(context))
                    val password = storage.getProtectionPassword(world.uuid)
                    // 設定済みパスワードは伏字で表示し、平文を画面へ出しません。
                    val masked = password?.let { "*".repeat(it.length) }
                    MenuCapabilityPresentation(
                        item = GuiItemSpec(
                            material = Material.OAK_SIGN,
                            name = GuiNameSpec.FixedLabel(
                                Component.text(i18n(context.player, ChanponEnvironmentKeys.CHANPON_MEMBER_MANAGEMENT_PASSWORD_DISPLAY)),
                            ),
                            lore = GuiLoreSpec.Blocks(
                                listOf(
                                    GuiLoreBlock(
                                        CCSystem.getAPI()
                                            .getLocalized(context.player, ChanponEnvironmentKeys.CHANPON_MEMBER_MANAGEMENT_PASSWORD_LORE)
                                            .map(GuiLoreLine::Text),
                                    ),
                                    GuiLoreBlock(
                                        listOf(
                                            GuiLoreLine.Data(
                                                i18n(context.player, ChanponEnvironmentKeys.CHANPON_MEMBER_MANAGEMENT_PASSWORD_CURRENT_LABEL),
                                                masked ?: i18n(context.player, ChanponEnvironmentKeys.CHANPON_MEMBER_MANAGEMENT_PASSWORD_VALUE_NOT_SET),
                                                if (masked != null) "§e" else "§8",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            role = GuiElementRole.ACTION,
                            amount = 1,
                        ),
                    )
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = "change",
                        // クリック受付は左右クリックに限定する。ANY（STANDARD）はSHIFT・中クリックも含むため、
                        // メンバー管理画面の操作体系と干渉し、クリックが受け付けられない場合がある。
                        trigger = MenuCapabilityTrigger.LEFT_RIGHT,
                        textProvider = MenuCapabilityActionTextProvider {
                            i18n(it.player, ChanponEnvironmentKeys.CHANPON_MEMBER_MANAGEMENT_PASSWORD_ACTION_CHANGE)
                        },
                        handler = MenuCapabilityActionHandler(::handleChange),
                        safety = MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE,
                    ),
                ),
            ),
        )
    }

    private fun handleChange(context: MenuCapabilityActionContext): MenuActionResult {
        val world = worldData(context)
        // TODO: クリック反応なしの原因特定後、このデバッグログを除去する。
        plugin.logger.info("[MWMChanpon-password] handleChange world=${world?.uuid} args=${context.arguments} click=${context.click}")
        if (world == null) return MenuActionResult.Ignored
        if (context.player.uniqueId != world.owner && !context.player.hasPermission("myworldmanager.admin")) {
            plugin.logger.info("[MWMChanpon-password] rejected: not owner/admin")
            return MenuActionResult.Ignored
        }
        plugin.logger.info("[MWMChanpon-password] opening dialog")
        showPasswordDialog(context.player, world)
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun showPasswordDialog(player: Player, worldData: WorldData) {
        CCSystem.getAPI().getMenuDialogService().show(
            player,
            MenuDialogRequest(
                owner = CAPABILITY_OWNER,
                id = "member-management-password",
                title = i18nComponent(player, ChanponEnvironmentKeys.CHANPON_ENVIRONMENT_PROTECTION_PASSWORD_SET_TITLE),
                body = listOf(i18nComponent(player, ChanponEnvironmentKeys.CHANPON_ENVIRONMENT_PROTECTION_PASSWORD_SET_BODY)),
                inputs = listOf(
                    MenuDialogInput.Text(
                        "protection_password",
                        i18nComponent(player, ChanponEnvironmentKeys.CHANPON_ENVIRONMENT_PROTECTION_PASSWORD_LABEL),
                        storage.getProtectionPassword(worldData.uuid).orEmpty(),
                        maxLength = 64,
                    ),
                ),
                confirm = MenuDialogButton(
                    i18nComponent(player, ChanponEnvironmentKeys.CHANPON_ENVIRONMENT_PROTECTION_PASSWORD_APPLY),
                    MenuDialogHandler { target, response ->
                        val input = response.textValue("protection_password").trim()
                        if (input.length < MIN_PROTECTION_PASSWORD_LENGTH) {
                            return@MenuDialogHandler MenuActionResult.Rejected(
                                Component.text(i18n(target, ChanponEnvironmentKeys.CHANPON_ENVIRONMENT_PROTECTION_PASSWORD_TOO_SHORT)),
                            )
                        }
                        storage.setProtectionPassword(worldData.uuid, input)
                        buildPermissionService.sync(worldData)
                        target.sendMessage(i18n(target, ChanponEnvironmentKeys.CHANPON_ENVIRONMENT_PROTECTION_PASSWORD_UPDATED))
                        // メンバー管理画面へ戻り、伏字表示を最新化します。
                        MenuActionResult.Success(MenuUpdate.Resume)
                    },
                ),
                cancel = MenuDialogButton(
                    Component.text(i18n(player, ChanponEnvironmentKeys.CHANPON_ENVIRONMENT_COMMON_BACK), NamedTextColor.GRAY),
                    MenuDialogHandler { _, _ ->
                        MenuActionResult.Success(MenuUpdate.Resume)
                    },
                ),
            ),
        )
    }

    private fun worldData(context: MenuCapabilityContext): WorldData? {
        val worldUuid = context.arguments[MemberManagementCapabilityContract.WORLD_UUID_ARGUMENT]
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return null
        return MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
    }

    private fun worldData(context: MenuCapabilityActionContext): WorldData? {
        val worldUuid = context.arguments[MemberManagementCapabilityContract.WORLD_UUID_ARGUMENT]
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return null
        return MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
    }

    private fun i18n(player: Player?, key: LocalizationKey<String>): String =
        CCSystem.getAPI().getLocalized(player, key)

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>): Component =
        Component.text(i18n(player, key))

    private companion object {
        const val CAPABILITY_OWNER = "mwm-chanpon"
        const val CAPABILITY_ID = "member-build-password"
        const val MIN_PROTECTION_PASSWORD_LENGTH = 4
    }
}