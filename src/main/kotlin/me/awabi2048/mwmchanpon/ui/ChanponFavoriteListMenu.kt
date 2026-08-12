package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiInteractionGuidance
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiMenuDisplaySpec
import com.awabi2048.ccsystem.api.gui.GuiMenuEntryData
import com.awabi2048.ccsystem.api.gui.GuiMenuEntrySpec
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiValueTone
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuGesture
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.ChanponReversibleActionClassification
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.FavoriteListAction
import me.awabi2048.myworldmanager.api.extension.FavoriteListRouteCapability
import me.awabi2048.myworldmanager.api.extension.FavoriteListRouteRequest
import me.awabi2048.myworldmanager.api.extension.FavoriteListSnapshot
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.GuiHelper
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/** Chanpon用Favorite一覧です。タグによる絞り込み状態を持たず、登録順をそのまま表示します。 */
class ChanponFavoriteListMenu(
    private val plugin: MWMChanponPlugin,
) : FavoriteListRouteCapability {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_ID,
                renderer = { context -> render(context.player, context.route) },
                actions = mapOf(
                    ACTION_WORLD to MenuActionHandler(::world),
                    ACTION_PAGE to MenuActionHandler(::page),
                    ACTION_OTHER_WORLDS to MenuActionHandler(::otherWorlds),
                    ACTION_TOGGLE_CURRENT to MenuActionHandler(::toggleCurrent),
                    ACTION_BACK to MenuActionHandler(::back),
                ),
            ),
        )
    }

    override fun prepare(player: Player, request: FavoriteListRouteRequest): MenuRoute =
        route(request.page, request.showBackButton)

    private fun render(player: Player, route: MenuRoute): InventoryMenuView {
        val snapshot = MyWorldManagerApi.getWorldListMenuService().favoriteSnapshot(player)
        val worlds = snapshot.worlds
        val requestedPage = route.payload[PAGE]?.toIntOrNull() ?: 0
        val page = CCSystem.getAPI().getGuiLayoutService().sevenColumnPage(worlds.size, requestedPage)
        val layout = page.layout
        val elements = mutableListOf<MenuElement>()
        val pageWorlds = worlds.drop(page.startIndex).take(page.itemCount)

        pageWorlds.forEachIndexed { index, data ->
            elements += worldEntry(player, data, layout.itemSlots[index])
        }
        if (worlds.isEmpty()) {
            elements += CCSystem.getAPI().getGuiElementService().menuDisplay(
                GuiMenuDisplaySpec(
                    layout.itemSlots[layout.itemSlots.size / 2],
                    GuiItemSpec(
                        material = Material.QUARTZ,
                        name = GuiNameSpec.FixedLabel(
                            CCSystem.getAPI().getI18nComponent(
                                player,
                                "gui.favorite.empty_message_no_favorites",
                            ),
                        ),
                        lore = GuiLoreSpec.None,
                        role = GuiElementRole.CONTENT,
                        amount = 1,
                    ),
                ),
            )
        }
        // MWM標準Favoriteのフッター配置を保ち、タグフィルターの枠だけを空けます。
        // これにより既存の「他ワールド」「現在地Favorite切替」「現在ワールド」の導線と、
        // ページ矢印の位置を変更せずにタグ操作だけを廃止できます。
        val footerStart = layout.size - 9
        val previousPageSlot = footerStart + 3
        val nextPageSlot = footerStart + 5
        if (page.page > 0) elements += navigationEntry(player, previousPageSlot, false, page.page - 1)
        elements += playerHeadEntry(player, worlds.size, 4)
        if (page.page < page.totalPages - 1) elements += navigationEntry(player, nextPageSlot, true, page.page + 1)

        val currentWorld = snapshot.currentWorldUuid?.let { uuid ->
            pluginCoreWorldRepository().findByUuid(uuid)
        }
        currentWorld?.let { elements += otherWorldsEntry(player, footerStart + 1, it.uuid) }
        elements += toggleCurrentEntry(player, footerStart + 2, currentWorld, snapshot)
        elements += MyWorldManagerApi.createCurrentWorldMenuElement(player, footerStart + 4)
        if (GuiHelper.canGoBack(player)) elements += backEntry(player, layout.backSlot)

        return InventoryMenuView(
            size = layout.size,
            title = GuiHelper.inventoryTitle(
                CCSystem.getAPI().getI18nString(player, "gui.favorite.title"),
            ),
            elements = elements,
        )
    }

    private fun world(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        return MyWorldManagerApi.getWorldListMenuService().executeFavorite(
            context.player,
            FavoriteListAction.OpenWorldDetail(worldUuid),
        )
    }

    private fun page(context: MenuActionContext): MenuActionResult {
        val target = context.payload[PAGE]?.toIntOrNull() ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Replace(route(target, showBack(context.route))))
    }

    private fun otherWorlds(context: MenuActionContext): MenuActionResult {
        val expectedUuid = context.payload[EXPECTED_CURRENT_WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        return MyWorldManagerApi.getWorldListMenuService().executeFavorite(
            context.player,
            FavoriteListAction.OpenOtherWorlds(expectedUuid),
        )
    }

    private fun toggleCurrent(context: MenuActionContext): MenuActionResult {
        val expectedUuid = context.payload[EXPECTED_CURRENT_WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        return MyWorldManagerApi.getWorldListMenuService().executeFavorite(
            context.player,
            FavoriteListAction.ToggleCurrentFavorite(expectedUuid),
        )
    }

    private fun back(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(MenuUpdate.Back)

    private fun worldEntry(player: Player, data: WorldData, slot: Int): MenuElement {
        val ownerName = Bukkit.getOfflinePlayer(data.owner).name
            ?: plugin.getMessage(player, "general.unknown")
        val isMember = data.owner == player.uniqueId ||
            player.uniqueId in data.moderators ||
            player.uniqueId in data.members
        val canWarp = MyWorldManagerApi.getWorldAccessPolicy().canDirectWorldWarp(player, data, isMember)
        val tagNames = data.tags.takeIf { it.isNotEmpty() }?.joinToString(", ") { tagId ->
            MyWorldManagerApi.getWorldTagService()?.getDisplayName(player, tagId) ?: tagId
        }
        val dataLines = buildList {
            add(GuiMenuEntryData(plugin.getMessage(player, "gui.common.world_item.owner"), ownerName, GuiValueTone.INFO))
            add(GuiMenuEntryData(plugin.getMessage(player, "gui.common.world_item.favorite"), data.favorite, GuiValueTone.DANGER))
            add(
                GuiMenuEntryData(
                    plugin.getMessage(player, "gui.common.world_item.recent_visitors"),
                    plugin.getMessage(
                        player,
                        "gui.common.world_item.recent_visitors_value",
                        "count" to data.recentVisitors.sum(),
                    ),
                    GuiValueTone.SUCCESS,
                ),
            )
            tagNames?.let { add(GuiMenuEntryData(plugin.getMessage(player, "gui.common.world_item.tags"), it, GuiValueTone.PRIMARY)) }
        }
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = data.icon,
                name = GuiNameSpec.TargetIdentity(
                    CCSystem.getAPI().getI18nComponent(
                        player,
                        "gui.common.world_item_name",
                        mapOf("world" to data.name),
                    ),
                ),
                role = GuiElementRole.ACTION,
                description = listOfNotNull(data.description.takeIf(String::isNotBlank)),
                data = dataLines,
                warnings = buildList {
                    if (data.isArchived) add(plugin.getMessage(player, "gui.favorite.world_item.archived_label"))
                    else if (!canWarp) add(plugin.getMessage(player, "gui.favorite.world_item.direct_warp_unavailable"))
                },
                actions = listOf(
                    chanponGestureAction(
                        ACTION_WORLD,
                        MenuGesture.ANY,
                        plugin.getMessage(player, "gui.favorite.world_item.open_actions"),
                        mapOf(WORLD_UUID to data.uuid.toString()),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                ),
            ),
        )
    }

    private fun playerHeadEntry(player: Player, totalCount: Int, slot: Int): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = Material.PLAYER_HEAD,
                name = GuiNameSpec.FixedLabel(
                    CCSystem.getAPI().getI18nComponent(
                        player,
                        "gui.favorite.player_icon.name",
                        mapOf("player" to player.name),
                    ),
                ),
                role = GuiElementRole.CONTENT,
                data = listOf(
                    GuiMenuEntryData(
                        plugin.getMessage(player, "gui.favorite.player_icon.lore_count"),
                        totalCount,
                        GuiValueTone.SUCCESS,
                    ),
                ),
                playerHeadOwner = player.uniqueId,
            ),
        )

    private fun otherWorldsEntry(player: Player, slot: Int, expectedCurrentUuid: UUID): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = Material.COMPASS,
                name = GuiNameSpec.FixedLabel(
                    CCSystem.getAPI().getI18nComponent(player, "gui.favorite.favorite_menu.other_worlds.name"),
                ),
                role = GuiElementRole.ACTION,
                description = CCSystem.getAPI().getI18nStringList(player, "gui.favorite.favorite_menu.other_worlds.lore"),
                actions = listOf(
                    chanponGestureAction(
                        ACTION_OTHER_WORLDS,
                        MenuGesture.ANY,
                        plugin.getMessage(player, "gui.favorite.favorite_menu.other_worlds.action"),
                        mapOf(EXPECTED_CURRENT_WORLD_UUID to expectedCurrentUuid.toString()),
                        safety = MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE,
                    ),
                ),
            ),
        )

    private fun toggleCurrentEntry(
        player: Player,
        slot: Int,
        currentWorld: WorldData?,
        snapshot: FavoriteListSnapshot,
    ): MenuElement {
        val restrictedKey = when {
            currentWorld == null -> "gui.favorite.favorite_menu.toggle.lore_restricted_not_managed"
            currentWorld.owner == player.uniqueId -> "gui.favorite.favorite_menu.toggle.lore_restricted_owner"
            else -> null
        }
        if (restrictedKey != null) {
            return CCSystem.getAPI().getGuiElementService().menuEntry(
                player,
                GuiMenuEntrySpec(
                    slot = slot,
                    material = Material.BARRIER,
                    name = GuiNameSpec.FixedLabel(
                        CCSystem.getAPI().getI18nComponent(player, "gui.favorite.favorite_menu.toggle.name_restricted"),
                    ),
                    role = GuiElementRole.CONTENT,
                    warnings = listOf(plugin.getMessage(player, restrictedKey)),
                ),
            )
        }
        val world = requireNotNull(currentWorld)
        val isFavorite = snapshot.currentWorldIsFavorite
        val nameKey = if (isFavorite) {
            "gui.favorite.favorite_menu.toggle.name_remove"
        } else {
            "gui.favorite.favorite_menu.toggle.name_add"
        }
        val safety = if (isFavorite) MenuActionSafety.CONFIRM_ENTRY else MenuActionSafety.REVERSIBLE
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = if (isFavorite) Material.RED_DYE else Material.GRAY_DYE,
                name = GuiNameSpec.FixedLabel(CCSystem.getAPI().getI18nComponent(player, nameKey)),
                role = GuiElementRole.ACTION,
                actions = listOf(
                    chanponGestureAction(
                        ACTION_TOGGLE_CURRENT,
                        MenuGesture.ANY,
                        plugin.getMessage(player, "gui.favorite.favorite_menu.toggle.action"),
                        mapOf(EXPECTED_CURRENT_WORLD_UUID to world.uuid.toString()),
                        safety = safety,
                        reversibleContract = if (safety == MenuActionSafety.REVERSIBLE) {
                            MyWorldManagerApi.getWorldListMenuService().favoriteToggleContract()
                        } else {
                            null
                        },
                        classification = if (safety == MenuActionSafety.REVERSIBLE) {
                            ChanponReversibleActionClassification.Operation.FAVORITE_TOGGLE
                        } else {
                            null
                        },
                    ),
                ),
                interactionGuidance = GuiInteractionGuidance.SINGLE_ACTION_CLICK,
            ),
        )
    }

    private fun navigationEntry(player: Player, slot: Int, next: Boolean, targetPage: Int): MenuElement {
        val labelKey = if (next) "gui.common.next_page" else "gui.common.prev_page"
        val iconKey = if (next) "next_page" else "prev_page"
        val label = plugin.getMessage(player, labelKey)
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = MyWorldManagerApi.getConfiguredMenuIcon("favorite", iconKey, Material.ARROW),
                name = GuiNameSpec.FixedLabel(CCSystem.getAPI().getI18nComponent(player, labelKey)),
                role = GuiElementRole.NAVIGATION,
                actions = listOf(
                    chanponGestureAction(
                        ACTION_PAGE,
                        MenuGesture.LEFT_RIGHT,
                        label,
                        mapOf(PAGE to targetPage.toString(), SHOW_BACK to "true"),
                        safety = MenuActionSafety.NAVIGATION_ONLY,
                    ),
                ),
                interactionGuidance = GuiInteractionGuidance.SINGLE_ACTION_CLICK,
            ),
        )
    }

    private fun backEntry(player: Player, slot: Int): MenuElement =
        CCSystem.getAPI().getGuiElementService().backEntry(
            player,
            slot,
            MyWorldManagerApi.getConfiguredMenuIcon("world_settings", "back", Material.REDSTONE),
        )

    private fun route(page: Int, showBackButton: Boolean): MenuRoute =
        MenuRoute(
            OWNER,
            ROUTE_ID,
            mapOf(
                PAGE to page.coerceAtLeast(0).toString(),
                SHOW_BACK to showBackButton.toString(),
            ),
        )

    private fun showBack(route: MenuRoute): Boolean =
        route.payload[SHOW_BACK]?.toBooleanStrictOrNull() ?: true

    private fun pluginCoreWorldRepository() =
        requireNotNull(MyWorldManagerApi.getWorldRepository()) { "MWM world repository is unavailable" }

    private companion object {
        const val OWNER = "mwm-chanpon"
        const val ROUTE_ID = "favorite-list"
        const val PAGE = "page"
        const val SHOW_BACK = "show_back"
        const val WORLD_UUID = "world_uuid"
        const val EXPECTED_CURRENT_WORLD_UUID = "expected_current_world_uuid"
        const val ACTION_WORLD = "world"
        const val ACTION_PAGE = "page"
        const val ACTION_OTHER_WORLDS = "other_worlds"
        const val ACTION_TOGGLE_CURRENT = "toggle_current"
        const val ACTION_BACK = "back"
    }
}
