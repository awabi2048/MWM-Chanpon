package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
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
                GuiNameSpec.Text("§e${targetPlayer.name ?: "Unknown"}", GuiNameStyle.DEFAULT),
                GuiElementRole.CONTENT,
                targetUuid,
            )
        }
        if (worldData != null && targetUuid != null) {
            listOf(19, 20, 21).zip(definitions).forEach { (slot, definition) ->
                elements += createToolItem(player, slot, worldData, targetUuid, definition)
            }
            if (targetUuid != worldData.owner) {
                elements += createRoleItem(player, 23, worldData, targetUuid)
            } else {
                elements += decorationEntry(23)
            }
        }
        elements += decorationEntry(24)
        elements += decorationEntry(25)
        elements += CCSystem.getAPI().getGuiElementService().backEntry(player, 40)
        return InventoryMenuView(
            45,
            CCSystem.getAPI().getGuiElementService().name(i18n("title")),
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
            context.player.sendMessage("§cオーナーのみ操作できます")
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
        if (!canManage(context.player, worldData) || targetUuid == worldData.owner) {
            return MenuActionResult.Rejected()
        }
        if (worldData.moderators.remove(targetUuid)) {
            if (!worldData.members.contains(targetUuid)) worldData.members.add(targetUuid)
        } else {
            worldData.members.remove(targetUuid)
            if (!worldData.moderators.contains(targetUuid)) worldData.moderators.add(targetUuid)
        }
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        controller.syncWorld(worldData)
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
                name = GuiNameSpec.Text(i18n("role.display"), GuiNameStyle.DEFAULT),
                role = GuiElementRole.ACTION,
                data = listOf(GuiMenuEntryData(
                    i18n("role.state_label"),
                    i18n(if (moderator) "role.moderator" else "role.member"),
                    if (moderator) GuiValueTone.PRIMARY else GuiValueTone.DEFAULT,
                )),
                actions = listOf(chanponGestureAction(
                    ACTION_ROLE,
                    MenuGesture.ANY,
                    i18n("role.action"),
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

    private fun canManage(player: Player, worldData: WorldData): Boolean =
        player.uniqueId == worldData.owner || player.hasPermission("myworldmanager.admin")

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
                name = GuiNameSpec.Text(definition.displayName, GuiNameStyle.DEFAULT),
                role = GuiElementRole.ACTION,
                data = listOf(GuiMenuEntryData(
                    i18n("state_label"),
                    i18n(if (enabled) "enabled" else "disabled"),
                    if (enabled) GuiValueTone.SUCCESS else GuiValueTone.DANGER,
                )),
                actions = listOf(chanponGestureAction(
                    ACTION_TOGGLE,
                    MenuGesture.ANY,
                    i18n("toggle_action"),
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

    private fun i18n(key: String): String =
        CCSystem.getAPI().getI18nString(null, "chanpon.member_lore.permission_menu.$key")

    companion object {
        const val EXTENSION_ID = "chanpon.tool_permissions"
        private const val OWNER = "mwm-chanpon"
        private const val ROUTE = "mwm-chanpon:tool-permissions"
        private const val ACTION_BACK = "back"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_ROLE = "role"
        private const val WORLD_UUID = "world_uuid"
        private const val TARGET_UUID = "target_uuid"
        private const val TOOL_ID = "tool_id"
    }
}
