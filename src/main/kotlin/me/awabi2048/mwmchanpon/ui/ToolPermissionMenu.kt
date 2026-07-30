package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.mwmchanpon.tool.ToolPermissionDefinition
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

class ToolPermissionMenu(
    private val plugin: MWMChanponPlugin,
    private val controller: ToolPermissionController,
    private val definitions: List<ToolPermissionDefinition>,
) {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
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
            val head = ItemStack(Material.PLAYER_HEAD)
            head.editMeta { meta ->
                if (meta is SkullMeta) meta.owningPlayer = targetPlayer
                meta.displayName(
                    CCSystem.getAPI().getGuiElementService()
                        .name("§e${targetPlayer.name ?: "Unknown"}"),
                )
            }
            elements += MenuElement(4, head, GuiElementRole.CONTENT)
        }
        if (worldData != null && targetUuid != null) {
            listOf(19, 20, 21).zip(definitions).forEach { (slot, definition) ->
                elements += MenuElement(
                    slot,
                    createToolItem(worldData, targetUuid, definition),
                    GuiElementRole.ACTION,
                    ACTION_TOGGLE,
                    mapOf(TOOL_ID to definition.id),
                )
            }
            if (targetUuid != worldData.owner) {
                elements += MenuElement(
                    23,
                    createRoleItem(worldData, targetUuid),
                    GuiElementRole.ACTION,
                    ACTION_ROLE,
                )
            } else {
                elements += MenuElement(23, decoration(), GuiElementRole.DECORATION)
            }
        }
        elements += MenuElement(24, decoration(), GuiElementRole.DECORATION)
        elements += MenuElement(25, decoration(), GuiElementRole.DECORATION)
        elements += MenuElement(40, backItem(), GuiElementRole.BACK, ACTION_BACK)
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

    private fun createRoleItem(worldData: WorldData, targetUuid: UUID): ItemStack {
        val moderator = targetUuid in worldData.moderators
        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(i18n("role.display")))
            meta.lore(
                ChanponLore.build(
                    listOf(
                        GuiLoreLine.Data(
                            i18n("role.state_label"),
                            i18n(if (moderator) "role.moderator" else "role.member"),
                            if (moderator) "§e" else "§f",
                        ),
                        GuiLoreLine.SingleAction(
                            i18n("role.operation"),
                            i18n("role.action"),
                            i18n("role.resolved"),
                        ),
                    ),
                ),
            )
        }
        return item
    }

    private fun decoration(): ItemStack =
        ChanponGuiElements.decoration(Material.WHITE_STAINED_GLASS_PANE)

    private fun canManage(player: Player, worldData: WorldData): Boolean =
        player.uniqueId == worldData.owner || player.hasPermission("myworldmanager.admin")

    private fun createToolItem(
        worldData: WorldData,
        targetPlayerUuid: UUID,
        definition: ToolPermissionDefinition,
    ): ItemStack {
        val enabled = controller.isEnabled(worldData, targetPlayerUuid, definition.id)
        val item = ItemStack(definition.material)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name("§f${definition.displayName}"))
            meta.lore(
                ChanponLore.build(
                    listOf(
                        GuiLoreLine.Data(
                            i18n("state_label"),
                            i18n(if (enabled) "enabled" else "disabled"),
                            if (enabled) "§a" else "§c",
                        ),
                        GuiLoreLine.SingleAction(
                            i18n("toggle_operation"),
                            i18n("toggle_action"),
                            i18n("toggle_resolved"),
                        ),
                    ),
                ),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        if (enabled) item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        return item
    }

    private fun backItem(): ItemStack =
        ItemStack(Material.REDSTONE).also { item ->
            item.editMeta { meta ->
                meta.displayName(CCSystem.getAPI().getGuiElementService().name("§7戻る"))
            }
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
