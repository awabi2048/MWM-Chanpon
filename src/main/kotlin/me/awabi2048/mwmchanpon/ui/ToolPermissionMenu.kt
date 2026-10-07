package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.generated.CommonKeys
import com.awabi2048.ccsystem.api.localization.generated.MyworldRoleKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponMemberLoreKeys
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuActionIntent
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.GuiValueTone
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
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.mwmchanpon.service.ChanponReversibleContracts
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.mwmchanpon.service.ChanponReversibleStateProviders
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.auth.AdminAccess
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

class ToolPermissionMenu(
    private val plugin: MWMChanponPlugin,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>,
    private val storage: ChanponWorldStorage,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        // 手動で切り替えるツール権限は、この画面の4枠に収めます。
        require(definitions.size <= TOOL_ITEM_SLOTS.size) {
            "ToolPermissionMenu only supports ${TOOL_ITEM_SLOTS.size} manual tool slots, but ${definitions.size} definitions were loaded"
        }
        ChanponReversibleStateProviders.registerToolPermission(
            CCSystem.getAPI().getMenuReversibleStateProviderRegistry(),
            storage,
            controller,
        )
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE,
                renderer = { context -> render(context.player, context.route) },
                actions = mapOf(
                    ACTION_BACK to MenuActionHandler(::back),
                    ACTION_TOGGLE to MenuActionHandler(::toggle),
                    ACTION_ROLE to MenuActionHandler(::toggleRole),
                    ACTION_BYPASS to MenuActionHandler(::toggleBypass),
                ),
            ),
        )
    }

    fun open(player: Player, worldData: WorldData, targetPlayerUuid: UUID) {
        runtime.navigate(player, route(worldData.uuid, targetPlayerUuid))
    }

    fun route(worldUuid: UUID, targetPlayerUuid: UUID): MenuRoute =
        MenuRoute(
            OWNER,
            ROUTE,
            mapOf(
                WORLD_UUID to worldUuid.toString(),
                TARGET_UUID to targetPlayerUuid.toString(),
            ),
        )

    private fun render(player: Player, route: MenuRoute): InventoryMenuView {
        val worldData = world(route)
        val targetUuid = uuid(route.payload[TARGET_UUID])
        val elements = mutableListOf<MenuElement>()
        if (targetUuid != null) {
            val targetPlayer = Bukkit.getOfflinePlayer(targetUuid)
            elements += displayEntry(
                4,
                Material.PLAYER_HEAD,
                targetIdentityName("§e${targetPlayer.name ?: "Unknown"}", GuiNameStyle.DEFAULT),
                GuiElementRole.CONTENT,
                targetUuid,
            )
        }
        if (worldData != null && targetUuid != null) {
            centeredToolSlots(definitions.size).zip(definitions).forEach { (slot, definition) ->
                elements += createToolItem(player, slot, worldData, targetUuid, definition)
            }
            // 中央7枠は「ツール4・背景1・役割切替1・バイパス切替1」で固定します。
            // 役割切替はオーナーと管理者、バイパス切替は管理者のみが操作できます。
            // 操作できない利用者には白の板ガラス装飾として表示し、操作入口を作りません。
            // slot 23 は要素を置かず、InventoryMenuViewの共通背景（灰色板ガラス）を使います。
            // 白い装飾枠を明示すると、背景と余白の視認性が変わってしまうためです。
            if (targetUuid != worldData.owner && canManageRoles(player, worldData)) {
                elements += createRoleItem(player, ROLE_SLOT, worldData, targetUuid)
            } else {
                elements += decorationEntry(ROLE_SLOT)
            }
            if (isAdminOnly(player)) {
                elements += createBypassItem(player, BYPASS_SLOT, worldData, targetUuid)
            } else {
                elements += decorationEntry(BYPASS_SLOT)
            }
        }
        elements += CCSystem.getAPI().getGuiElementService().backEntry(player, 40)
        return InventoryMenuView(
            45,
            CCSystem.getAPI().getGuiElementService().name(i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_TITLE)),
            elements,
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Back)
    }

    private fun toggle(context: MenuActionContext): MenuActionResult {
        val worldData = world(context.route) ?: return MenuActionResult.Rejected()
        val targetUuid = uuid(context.route.payload[TARGET_UUID])
            ?: return MenuActionResult.Rejected()
        if (!canManage(context.player, worldData)) {
            context.player.sendMessage("§cオーナー・モデレータのみ操作できます")
            return MenuActionResult.Rejected()
        }
        val definition = context.payload[TOOL_ID]
            ?.let { id -> definitions.firstOrNull { it.id == id } }
            ?: return MenuActionResult.Rejected()
        val next = !controller.isEnabled(worldData, targetUuid, definition.id)
        controller.setEnabled(worldData, targetUuid, definition.id, next)
        return MenuActionResult.Success(MenuUpdate.Refresh)
    }

    private fun toggleRole(context: MenuActionContext): MenuActionResult {
        val worldData = world(context.route) ?: return MenuActionResult.Rejected()
        val targetUuid = uuid(context.route.payload[TARGET_UUID])
            ?: return MenuActionResult.Rejected()
        if (!canManageRoles(context.player, worldData) || targetUuid == worldData.owner) {
            return MenuActionResult.Rejected()
        }
        // 役割変更はMWM側の単一経路で永続化します。MwmMemberRoleChangedEventを経由して
        // MWM側のWorldGuard・LuckPerms同期とこのプラグイン側のツール状態同期の双方が再適用されます。
        if (!MyWorldManagerApi.toggleMemberRole(worldData.uuid, targetUuid, context.player.uniqueId)) {
            return MenuActionResult.Rejected()
        }
        return MenuActionResult.Success(MenuUpdate.Refresh)
    }

    private fun toggleBypass(context: MenuActionContext): MenuActionResult {
        val worldData = world(context.route) ?: return MenuActionResult.Rejected()
        val targetUuid = uuid(context.route.payload[TARGET_UUID])
            ?: return MenuActionResult.Rejected()
        // グローバル権限のバイパスは管理者のみが設定できます。オーナー判定は含めません。
        if (!isAdminOnly(context.player)) {
            return MenuActionResult.Rejected()
        }
        val next = !storage.isGlobalBypassAllowed(worldData.uuid, targetUuid)
        storage.setGlobalBypassAllowed(worldData.uuid, targetUuid, next)
        controller.syncWorld(worldData)
        MyWorldManagerApi.syncOnlineWorldPlayerStates()
        return MenuActionResult.Success(MenuUpdate.Refresh)
    }

    private fun createRoleItem(
        player: Player,
        slot: Int,
        worldData: WorldData,
        targetUuid: UUID,
    ): MenuElement {
        val moderator = targetUuid in worldData.moderators
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = Material.PLAYER_HEAD,
                name = fixedName(i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_ROLE_DISPLAY), GuiNameStyle.DEFAULT),
                role = GuiElementRole.ACTION,
                data = listOf(GuiMenuEntryData(
                    i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_ROLE_STATE_LABEL),
                    i18n(if (moderator) ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_ROLE_MODERATOR else ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_ROLE_MEMBER),
                    if (moderator) GuiValueTone.PRIMARY else GuiValueTone.DEFAULT,
                )),
                actions = listOf(chanponGestureAction(
                    ACTION_ROLE,
                    MenuGesture.ANY,
                    i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_ROLE_ACTION),
                    safety = MenuActionSafety.REVERSIBLE,
                    reversibleContract = ChanponReversibleContracts.toolPermission(
                        worldData.uuid,
                        targetUuid,
                        "role",
                    ),
                    classification = ChanponReversibleActionClassification.Operation.MEMBER_ROLE_TOGGLE,
                )),
            ),
        )
    }

    private fun decorationEntry(slot: Int): MenuElement =
        displayEntry(
            slot,
            Material.WHITE_STAINED_GLASS_PANE,
            GuiNameSpec.Empty,
            GuiElementRole.DECORATION,
        )

    private fun displayEntry(
        slot: Int,
        material: Material,
        name: GuiNameSpec,
        role: GuiElementRole,
        playerHeadOwner: UUID? = null,
    ): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuDisplay(
            GuiMenuDisplaySpec(
                slot = slot,
                item = GuiItemSpec(
                    material = material,
                    name = name,
                    lore = GuiLoreSpec.None,
                    role = role,
                    amount = 1,
                ),
                playerHeadOwner = playerHeadOwner,
            ),
        )

    /** メンバーのツール権限は、MWMのメンバー管理と同じくオーナー・モデレータ・管理者が扱います。 */
    private fun canManage(player: Player, worldData: WorldData): Boolean =
        canManageRoles(player, worldData) || player.uniqueId in worldData.moderators

    /** 役割（メンバー⇔モデレータ）の変更はオーナーと管理者のみが扱います。 */
    private fun canManageRoles(player: Player, worldData: WorldData): Boolean =
        AdminAccess.isOwnerOrAdmin(
            player.uniqueId == worldData.owner,
            MyWorldManagerApi.hasWorldSettingsAdminAccess(player),
            player.hasPermission("mwmchanpon.admin"),
        )

    /** グローバル権限のバイパスは管理者のみが設定できます。 */
    private fun isAdminOnly(player: Player): Boolean =
        AdminAccess.isAdmin(
            MyWorldManagerApi.hasWorldSettingsAdminAccess(player),
            player.hasPermission("mwmchanpon.admin"),
        )

    /**
     * 全体停止の個人バイパス切替です。
     * 迂回するのはグローバル設定だけであり、ツール個別の有効状態はそのまま残ります。
     */
    private fun createBypassItem(
        player: Player,
        slot: Int,
        worldData: WorldData,
        targetUuid: UUID,
    ): MenuElement {
        val bypassed = storage.isGlobalBypassAllowed(worldData.uuid, targetUuid)
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = Material.BEACON,
                name = fixedName(i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_BYPASS_DISPLAY), GuiNameStyle.DEFAULT),
                role = GuiElementRole.ACTION,
                description = listOf(i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_BYPASS_DESCRIPTION)),
                data = listOf(GuiMenuEntryData(
                    i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_STATE_LABEL),
                    i18n(if (bypassed) ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_ENABLED else ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_DISABLED),
                    if (bypassed) GuiValueTone.SUCCESS else GuiValueTone.DANGER,
                )),
                actions = listOf(chanponGestureAction(
                    ACTION_BYPASS,
                    MenuGesture.ANY,
                    i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_BYPASS_ACTION),
                    safety = MenuActionSafety.REVERSIBLE,
                    reversibleContract = ChanponReversibleContracts.toolPermission(
                        worldData.uuid,
                        targetUuid,
                        "bypass",
                    ),
                    classification = ChanponReversibleActionClassification.Operation.GLOBAL_BYPASS_TOGGLE,
                )),
                glint = bypassed,
            ),
        )
    }

    private fun createToolItem(
        player: Player,
        slot: Int,
        worldData: WorldData,
        targetPlayerUuid: UUID,
        definition: ToolPermissionDefinition,
    ): MenuElement {
        val enabled = controller.isEnabled(worldData, targetPlayerUuid, definition.id)
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = definition.material,
                name = fixedName(definition.displayName, GuiNameStyle.DEFAULT),
                role = GuiElementRole.ACTION,
                data = listOf(GuiMenuEntryData(
                    i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_STATE_LABEL),
                    i18n(if (enabled) ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_ENABLED else ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_DISABLED),
                    if (enabled) GuiValueTone.SUCCESS else GuiValueTone.DANGER,
                )),
                actions = listOf(chanponGestureAction(
                    ACTION_TOGGLE,
                    MenuGesture.ANY,
                    i18n(ChanponMemberLoreKeys.CHANPON_MEMBER_LORE_PERMISSION_MENU_TOGGLE_ACTION),
                    mapOf(TOOL_ID to definition.id),
                    safety = MenuActionSafety.REVERSIBLE,
                    reversibleContract = ChanponReversibleContracts.toolPermission(
                        worldData.uuid,
                        targetPlayerUuid,
                        "toggle",
                    ),
                    classification = ChanponReversibleActionClassification.Operation.TOOL_ENABLE_TOGGLE,
                )),
                glint = enabled,
            ),
        )
    }

    private fun world(route: MenuRoute): WorldData? =
        uuid(route.payload[WORLD_UUID])
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }

    private fun uuid(value: String?): UUID? =
        value?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** 設定されたツール数が減っても、残ったアイコンが左へ偏らないよう中央寄せします。 */
    private fun centeredToolSlots(count: Int): List<Int> {
        require(count in 0..TOOL_ITEM_SLOTS.size)
        val first = (TOOL_ITEM_SLOTS.size - count) / 2
        return TOOL_ITEM_SLOTS.drop(first).take(count)
    }

    private fun i18n(key: LocalizationKey<String>): String =
        CCSystem.getAPI().getLocalized(null, key)

    companion object {
        const val EXTENSION_ID = "chanpon.tool_permissions"
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE = "mwm-chanpon:tool-permissions"
        private val TOOL_ITEM_SLOTS = listOf(19, 20, 21, 22)
        private const val ROLE_SLOT = 24
        private const val BYPASS_SLOT = 25
        private const val ACTION_BACK = "back"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_ROLE = "role"
        private const val ACTION_BYPASS = "bypass"
        private const val WORLD_UUID = "world_uuid"
        private const val TARGET_UUID = "target_uuid"
        private const val TOOL_ID = "tool_id"
    }
}
