package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.EasyVoidWorldCreationService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.PlayerWorldCapability
import me.awabi2048.myworldmanager.api.extension.PlayerWorldCapabilityContext
import me.awabi2048.myworldmanager.api.extension.PlayerWorldCreationView
import me.awabi2048.myworldmanager.api.extension.WorldCreationOperation
import me.awabi2048.myworldmanager.api.extension.WorldCreationRequest
import me.awabi2048.myworldmanager.api.extension.WorldSettingsNavigationRequest
import me.awabi2048.myworldmanager.api.service.WorldPointBillingMode
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * MWMが所有するPlayerWorld画面へChanpon固有の表示値と操作だけを提供する。
 *
 * 画面、Route、履歴、MWMのGUIセッションは所有しない。
 */
class ChanponPlayerWorldCapability(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val easyVoidCreationService: EasyVoidWorldCreationService,
) : PlayerWorldCapability {

    override fun getId(): String = CAPABILITY_ID

    override fun renderWorldItem(
        context: PlayerWorldCapabilityContext,
        worldData: WorldData,
    ): ItemStack = createWorldItem(context.player, worldData)

    override fun renderSummaryItem(context: PlayerWorldCapabilityContext): ItemStack =
        createPlayerInfoItem(
            context.player,
            context.targetPlayerUuid,
            context.targetPlayerName,
        )

    override fun renderCreationItem(
        context: PlayerWorldCapabilityContext,
    ): PlayerWorldCreationView? {
        if (context.targetPlayerUuid != context.player.uniqueId) return null
        val reason = creationBlockReason(
            context.player,
            context.worlds.count { it.owner == context.targetPlayerUuid },
            plugin.config.getInt("creation.max_worlds_per_player", 50),
        )
        return if (reason == null && hasCreatePermission(context.player)) {
            PlayerWorldCreationView(createCreationButton(context.player), true)
        } else {
            PlayerWorldCreationView(
                createCreationUnavailableButton(
                    context.player,
                    reason ?: CreationBlockReason.NO_PERMISSION,
                ),
                false,
            )
        }
    }

    override fun handleCreate(
        context: PlayerWorldCapabilityContext,
        click: org.bukkit.event.inventory.ClickType,
        returnRoute: MenuRoute,
    ): MenuActionResult? {
        val player = context.player
        if (context.targetPlayerUuid != player.uniqueId) return MenuActionResult.Rejected()
        if (!hasCreatePermission(player)) {
            player.sendMessage(i18n(player, "message.world_creation_no_permission"))
            return MenuActionResult.Rejected()
        }
        if (click.isRightClick && !click.isShiftClick) {
            startEasyCreation(player, returnRoute)
            return MenuActionResult.Success(MenuUpdate.None)
        }
        MyWorldManagerApi.openStandardWorldCreation(player, WorldPointBillingMode.NONE)
        return MenuActionResult.Success(MenuUpdate.None)
    }

    override fun handleWorld(
        context: PlayerWorldCapabilityContext,
        click: org.bukkit.event.inventory.ClickType,
        worldData: WorldData,
    ): MenuActionResult? {
        val player = context.player
        if (
            context.targetPlayerUuid == player.uniqueId &&
            click.isShiftClick &&
            click.isLeftClick
        ) {
            MyWorldManagerApi.prioritizePlayerWorld(player.uniqueId, worldData.uuid)
            return MenuActionResult.Success(MenuUpdate.Refresh)
        }
        if (click.isLeftClick && !click.isShiftClick) {
            if (worldData.isArchived) return MenuActionResult.Rejected()
            if (isCurrentWorld(player, worldData)) {
                if (!canOpenSettings(player, worldData)) return MenuActionResult.Rejected()
                openWorldSettings(context, worldData)
                return MenuActionResult.Success(MenuUpdate.None)
            }
            MyWorldManagerApi.getWorldService()?.teleportToWorld(player, worldData.uuid)
            player.sendMessage(
                text(player, "messages.warp_success", mapOf("world" to worldData.name)),
            )
            return MenuActionResult.Success(MenuUpdate.Close)
        }
        if (click.isRightClick && !click.isShiftClick && canOpenSettings(player, worldData)) {
            openWorldSettings(context, worldData)
            return MenuActionResult.Success(MenuUpdate.None)
        }
        return MenuActionResult.Ignored
    }

    private fun startEasyCreation(player: Player, returnRoute: MenuRoute) {
        easyVoidCreationService.start(player, returnRoute)
    }

    private fun createWorldItem(player: Player, world: WorldData): ItemStack {
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(
                component(
                    player,
                    "gui.common.world_item_name",
                    mapOf("world" to world.name),
                ).decoration(TextDecoration.ITALIC, false),
            )
            val ownerName =
                Bukkit.getOfflinePlayer(world.owner).name ?: text(player, "general.unknown")
            val progressLine = SubmissionProgressDisplay.loreLine(player, storage, world.uuid)
            val tagNames = world.tags.joinToString(", ") {
                MyWorldManagerApi.getWorldTagService()?.getDisplayName(player, it) ?: it
            }
            val warpAction = text(player, "gui.player_world.world_item.warp")
            val settingsAction = text(player, "gui.player_world.world_item.settings")
            val currentWorld = isCurrentWorld(player, world)
            meta.lore(
                CCSystem.getAPI().getLoreService().render(
                    GuiLoreSpec.Blocks(
                        buildList {
                            if (world.description.isNotBlank()) {
                                add(GuiLoreBlock(listOf(GuiLoreLine.Text(world.description))))
                            }
                            add(
                                GuiLoreBlock(
                                    buildList {
                                        add(
                                            GuiLoreLine.Data(
                                                CCSystem.getAPI().getI18nString(player, "gui_data.owner"),
                                                ownerName,
                                                "§f",
                                            ),
                                        )
                                        add(
                                            GuiLoreLine.Data(
                                                CCSystem.getAPI().getI18nString(player, "gui_data.visibility"),
                                                visibilityName(player, storage.getVisibility(world.uuid)),
                                                "§e",
                                            ),
                                        )
                                        progressLine?.let(::add)
                                        tagNames.takeIf(String::isNotBlank)?.let {
                                            add(
                                                GuiLoreLine.Data(
                                                    CCSystem.getAPI().getI18nString(player, "gui_data.tags"),
                                                    it,
                                                    "§f",
                                                ),
                                            )
                                        }
                                    },
                                ),
                            )
                            if (world.isArchived) {
                                add(
                                    GuiLoreBlock(
                                        listOf(
                                            GuiLoreLine.Danger(
                                                text(player, "gui.player_world.world_item.expired"),
                                            ),
                                        ),
                                    ),
                                )
                            }
                            add(
                                GuiLoreBlock(
                                    if (currentWorld) {
                                        val operation = text(player, "lore.click.any")
                                        listOf(
                                            GuiLoreLine.SingleAction(
                                                operation,
                                                settingsAction,
                                                text(
                                                    player,
                                                    "lore.action_single_with_operation",
                                                    mapOf(
                                                        "operation" to operation,
                                                        "action" to settingsAction,
                                                    ),
                                                ),
                                            ),
                                        )
                                    } else {
                                        listOf(
                                            GuiLoreLine.Action(
                                                text(player, "gui.settings.click.left"),
                                                warpAction,
                                            ),
                                            GuiLoreLine.Action(
                                                text(player, "gui.settings.click.right"),
                                                settingsAction,
                                            ),
                                        )
                                    },
                                ),
                            )
                        },
                    ),
                ),
            )
            if (world.isArchived) meta.setEnchantmentGlintOverride(true)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun createCreationButton(player: Player): ItemStack {
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(component(player, "gui.player_world.creation_button.display"))
            meta.lore(
                CCSystem.getAPI().getLoreService().render(
                    GuiLoreSpec.Blocks(
                        listOf(
                            GuiLoreBlock(
                                textList(player, "gui.player_world.creation_button.description")
                                    .map(GuiLoreLine::Text),
                            ),
                            GuiLoreBlock(
                                listOf(
                                    GuiLoreLine.Action(
                                        text(player, "gui.settings.click.left"),
                                        text(player, "gui.player_world.creation_button.action"),
                                    ),
                                    GuiLoreLine.Action(
                                        text(player, "gui.settings.click.right"),
                                        plugin.getMessage(
                                            player,
                                            "chanpon.player_world.easy_creation.action",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_CREATION_BUTTON)
        return item
    }

    private fun createCreationUnavailableButton(
        player: Player,
        reason: CreationBlockReason,
    ): ItemStack {
        val item = ItemStack(Material.BARRIER)
        item.editMeta { meta ->
            meta.displayName(component(player, reason.displayKey))
            meta.lore(ChanponLore.build(textList(player, reason.loreKey).map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun createPlayerInfoItem(
        player: Player,
        targetPlayerUuid: java.util.UUID,
        targetPlayerName: String?,
    ): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { rawMeta ->
            val meta = rawMeta as? SkullMeta ?: return@editMeta
            meta.owningPlayer = Bukkit.getOfflinePlayer(targetPlayerUuid)
            meta.displayName(
                CCSystem.getAPI().getGuiElementService()
                    .name(targetPlayerName ?: targetPlayerUuid.toString()),
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun visibilityName(
        player: Player,
        visibility: ChanponWorldVisibility,
    ): String {
        val key = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> "chanpon.publish.public"
            ChanponWorldVisibility.DEVELOPMENT -> "chanpon.publish.development"
            ChanponWorldVisibility.PRIVATE -> "chanpon.publish.private"
        }
        return plugin.getMessage(player, key)
    }

    private fun canOpenSettings(player: Player, world: WorldData): Boolean =
        player.hasPermission("myworldmanager.admin") ||
            world.owner == player.uniqueId ||
            world.moderators.contains(player.uniqueId) ||
            world.members.contains(player.uniqueId)

    private fun openWorldSettings(
        context: PlayerWorldCapabilityContext,
        worldData: WorldData,
    ) {
        MyWorldManagerApi.openWorldSettings(
            context.player,
            worldData.uuid,
            WorldSettingsNavigationRequest(
                showBackButton = true,
                isPlayerWorldFlow = true,
                parentShowBackButton = context.showBackButton,
            ),
        )
    }

    private fun isCurrentWorld(player: Player, world: WorldData): Boolean =
        MyWorldManagerApi.getWorldService()?.isPlayerInWorld(player, world) == true

    private fun canBypassWorldLimits(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin.bypass_world_limits") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("craftercrossing.test")

    private fun hasCreatePermission(player: Player): Boolean =
        player.hasPermission("myworldmanager.world.create") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin") ||
            player.hasPermission("craftercrossing.test")

    private fun creationBlockReason(
        player: Player,
        currentCreateCount: Int,
        maxCreateCount: Int,
    ): CreationBlockReason? {
        if (!hasCreatePermission(player)) return CreationBlockReason.NO_PERMISSION
        val creationDecision = MyWorldManagerApi.checkWorldCreation(
            WorldCreationRequest(player, player, WorldCreationOperation.NORMAL, null),
        )
        if (!creationDecision.allowed) return CreationBlockReason.POLICY_DENIED
        if (canBypassWorldLimits(player)) return null
        if (currentCreateCount >= maxCreateCount) return CreationBlockReason.NO_SLOT
        return null
    }

    private fun text(
        player: Player,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ): String = CCSystem.getAPI().getI18nString(player, key, placeholders)

    private fun textList(player: Player, key: String): List<String> =
        CCSystem.getAPI().getI18nStringList(player, key)

    private fun component(
        player: Player,
        key: String,
        placeholders: Map<String, Any> = emptyMap(),
    ) = CCSystem.getAPI().getI18nComponent(player, key, placeholders)

    private fun i18n(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")

    private enum class CreationBlockReason(
        val displayKey: String,
        val loreKey: String,
    ) {
        POLICY_DENIED(
            "gui.player_world.creation_unavailable.policy_denied.display",
            "gui.player_world.creation_unavailable.policy_denied.lore",
        ),
        NO_SLOT(
            "gui.player_world.creation_unavailable.no_slot.display",
            "gui.player_world.creation_unavailable.no_slot.lore",
        ),
        NO_PERMISSION(
            "gui.player_world.creation_unavailable.no_permission.display",
            "gui.player_world.creation_unavailable.no_permission.lore",
        ),
    }

    private companion object {
        const val CAPABILITY_ID = "chanpon.player_world_menu"
    }
}
