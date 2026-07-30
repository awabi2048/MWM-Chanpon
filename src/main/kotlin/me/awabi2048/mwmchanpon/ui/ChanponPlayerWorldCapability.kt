package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentation
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
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

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
    ): MenuCapabilityPresentation = createWorldPresentation(context.player, worldData)

    override fun renderSummaryItem(
        context: PlayerWorldCapabilityContext,
    ): MenuCapabilityPresentation =
        createPlayerInfoPresentation(
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
            PlayerWorldCreationView(createCreationPresentation(context.player), true)
        } else {
            PlayerWorldCreationView(
                createCreationUnavailablePresentation(
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

    private fun createWorldPresentation(
        player: Player,
        world: WorldData,
    ): MenuCapabilityPresentation {
        val ownerName = Bukkit.getOfflinePlayer(world.owner).name
            ?: text(player, "general.unknown")
        val progressLine = SubmissionProgressDisplay.loreLine(player, storage, world.uuid)
        val tagNames = world.tags.joinToString(", ") {
            MyWorldManagerApi.getWorldTagService()?.getDisplayName(player, it) ?: it
        }
        val currentWorld = isCurrentWorld(player, world)
        val information = buildList {
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
        }
        val actions = if (currentWorld) {
            listOf(
                CCSystem.getAPI().getGuiActionService().singleClick(
                    player,
                    text(player, "gui.player_world.world_item.settings"),
                ),
            )
        } else {
            listOf(
                GuiLoreLine.Action(
                    CCSystem.getAPI().getGuiActionService()
                        .clickLabel(player, com.awabi2048.ccsystem.api.gui.GuiClickLabel.LEFT),
                    text(player, "gui.player_world.world_item.warp"),
                ),
                GuiLoreLine.Action(
                    CCSystem.getAPI().getGuiActionService()
                        .clickLabel(player, com.awabi2048.ccsystem.api.gui.GuiClickLabel.RIGHT),
                    text(player, "gui.player_world.world_item.settings"),
                ),
            )
        }
        val blocks = buildList {
            if (world.description.isNotBlank()) {
                add(GuiLoreBlock(listOf(GuiLoreLine.Text(world.description))))
            }
            add(GuiLoreBlock(information))
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
            add(GuiLoreBlock(actions))
        }
        return MenuCapabilityPresentation(
            item = GuiItemSpec(
                material = world.icon,
                name = GuiNameSpec.Component(
                    component(
                        player,
                        "gui.common.world_item_name",
                        mapOf("world" to world.name),
                    ).decoration(TextDecoration.ITALIC, false),
                ),
                lore = GuiLoreSpec.Blocks(blocks),
                role = GuiElementRole.ACTION,
                amount = 1,
            ),
            glint = world.isArchived,
        )
    }

    private fun createCreationPresentation(player: Player): MenuCapabilityPresentation =
        MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.NETHER_STAR,
                name = GuiNameSpec.Component(
                    component(player, "gui.player_world.creation_button.display"),
                ),
                lore = GuiLoreSpec.Blocks(
                    listOf(
                        GuiLoreBlock(
                            textList(player, "gui.player_world.creation_button.description")
                                .map(GuiLoreLine::Text),
                        ),
                        GuiLoreBlock(
                            listOf(
                                GuiLoreLine.Action(
                                    CCSystem.getAPI().getGuiActionService()
                                        .clickLabel(
                                            player,
                                            com.awabi2048.ccsystem.api.gui.GuiClickLabel.LEFT,
                                        ),
                                    text(player, "gui.player_world.creation_button.action"),
                                ),
                                GuiLoreLine.Action(
                                    CCSystem.getAPI().getGuiActionService()
                                        .clickLabel(
                                            player,
                                            com.awabi2048.ccsystem.api.gui.GuiClickLabel.RIGHT,
                                        ),
                                    plugin.getMessage(
                                        player,
                                        "chanpon.player_world.easy_creation.action",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                role = GuiElementRole.ACTION,
                amount = 1,
            ),
        )

    private fun createCreationUnavailablePresentation(
        player: Player,
        reason: CreationBlockReason,
    ): MenuCapabilityPresentation =
        MenuCapabilityPresentation(
            GuiItemSpec(
                material = Material.BARRIER,
                name = GuiNameSpec.Component(component(player, reason.displayKey)),
                lore = GuiLoreSpec.Rich(
                    textList(player, reason.loreKey).map(GuiLoreLine::Text),
                    com.awabi2048.ccsystem.api.gui.GuiLoreFrame.BOTH,
                ),
                role = GuiElementRole.CONTENT,
                amount = 1,
            ),
        )

    private fun createPlayerInfoPresentation(
        player: Player,
        targetPlayerUuid: java.util.UUID,
        targetPlayerName: String?,
    ): MenuCapabilityPresentation =
        MenuCapabilityPresentation(
            item = GuiItemSpec(
                material = Material.PLAYER_HEAD,
                name = GuiNameSpec.Component(
                    CCSystem.getAPI().getGuiElementService()
                        .name(targetPlayerName ?: targetPlayerUuid.toString()),
                ),
                lore = GuiLoreSpec.None,
                role = GuiElementRole.CONTENT,
                amount = 1,
            ),
            playerHeadOwner = targetPlayerUuid,
        )

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
