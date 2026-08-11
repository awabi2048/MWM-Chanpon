package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiInteractionGuidance
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
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
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.DiscoveryRouteCapability
import me.awabi2048.myworldmanager.api.extension.DiscoveryRouteRequest
import me.awabi2048.myworldmanager.api.extension.DiscoveryWorldAction
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.GuiHelper
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chanpon用Discoveryです。MWM標準画面のソート・フィルター・SPOTLIGHTには依存せず、
 * 完成日時と訪問履歴というChanponの表示契約だけを画面へ反映します。
 */
class ChanponDiscoveryMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
) : DiscoveryRouteCapability {
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()
    private val warnedMissingCompletion = ConcurrentHashMap.newKeySet<UUID>()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = OWNER,
                id = ROUTE_ID,
                renderer = { context -> render(context.player, context.route) },
                actions = mapOf(
                    ACTION_WORLD to MenuActionHandler(::world),
                    ACTION_PAGE to MenuActionHandler(::page),
                    ACTION_BACK to MenuActionHandler(::back),
                ),
            ),
        )
    }

    override fun prepare(player: Player, request: DiscoveryRouteRequest): MenuRoute =
        route(request.page, request.showBackButton)

    private fun render(player: Player, route: MenuRoute): InventoryMenuView {
        val snapshot = MyWorldManagerApi.getWorldListMenuService().discoverySnapshot(player)
        val worlds = snapshot.worlds
            .asSequence()
            .filterNot { it.uuid in snapshot.visitedWorldUuids }
            .mapNotNull { worldData ->
                val completedAt = storage.getCompletedAt(worldData.uuid)
                if (completedAt == null) {
                    warnMissingCompletion(worldData)
                    null
                } else {
                    CompletedWorld(worldData, completedAt)
                }
            }
            .sortedWith(compareBy<CompletedWorld> { it.completedAt }.thenBy { it.world.uuid.toString() })
            .map { it.world }
            .toList()

        val requestedPage = route.payload[PAGE]?.toIntOrNull() ?: 0
        val page = CCSystem.getAPI().getGuiLayoutService().sevenColumnPage(worlds.size, requestedPage)
        val layout = page.layout
        val elements = mutableListOf<MenuElement>()
        val pageWorlds = worlds.drop(page.startIndex).take(page.itemCount)

        if (pageWorlds.isEmpty()) {
            elements += CCSystem.getAPI().getGuiElementService().menuDisplay(
                GuiMenuDisplaySpec(
                    layout.itemSlots[layout.itemSlots.size / 2],
                    GuiItemSpec(
                        material = Material.GRAY_DYE,
                        name = GuiNameSpec.FixedLabel(i18nComponent(player, "no_result")),
                        lore = GuiLoreSpec.None,
                        role = GuiElementRole.CONTENT,
                        amount = 1,
                    ),
                ),
            )
        } else {
            pageWorlds.forEachIndexed { index, worldData ->
                elements += worldEntry(
                    player,
                    worldData,
                    layout.itemSlots[index],
                    snapshot,
                )
            }
        }

        // Discoveryではソート等の機能枠を廃止し、説明だけをフッター中央へ固定します。
        elements += infoEntry(player, layout.actionSlot)
        if (GuiHelper.canGoBack(player)) elements += backEntry(player, layout.backSlot)
        if (page.page > 0) elements += navigationEntry(player, layout.previousPageSlot, false, page.page - 1)
        if (page.page < page.totalPages - 1) elements += navigationEntry(player, layout.nextPageSlot, true, page.page + 1)

        return InventoryMenuView(
            size = layout.size,
            title = GuiHelper.inventoryTitle(i18n(player, "title")),
            elements = elements,
        )
    }

    private fun world(context: MenuActionContext): MenuActionResult {
        val worldUuid = context.payload[WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        val action = context.payload[WORLD_ACTION]
            ?.let { runCatching { DiscoveryWorldAction.valueOf(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        return MyWorldManagerApi.getWorldListMenuService().executeDiscovery(context.player, worldUuid, action)
    }

    private fun page(context: MenuActionContext): MenuActionResult {
        val target = context.payload[PAGE]?.toIntOrNull() ?: return MenuActionResult.Rejected()
        return MenuActionResult.Success(MenuUpdate.Replace(route(target, showBack(context.route))))
    }

    private fun back(context: MenuActionContext): MenuActionResult =
        MenuActionResult.Success(MenuUpdate.Back)

    private fun worldEntry(
        player: Player,
        data: WorldData,
        slot: Int,
        snapshot: me.awabi2048.myworldmanager.api.extension.DiscoveryListSnapshot,
    ): MenuElement {
        val isBedrock = MyWorldManagerApi.getBedrockFormService()?.isBedrock(player) == true
        val isCurrentWorld = snapshot.currentWorldUuid == data.uuid
        val isFavorited = data.uuid in snapshot.favoriteWorldUuids
        val isMember = player.uniqueId == data.owner ||
            player.uniqueId in data.moderators ||
            player.uniqueId in data.members
        val payload = mapOf(WORLD_UUID to data.uuid.toString())
        val actions = buildList {
            if (!isCurrentWorld) {
                add(
                    action(
                        DiscoveryWorldAction.VISIT,
                        MenuGesture.PLAIN_LEFT,
                        plugin.getMessage(player, "gui.discovery.world_item.warp_hint"),
                        payload,
                        MenuActionSafety.EXTERNAL_SIDE_EFFECT,
                    ),
                )
            }
            if (!isCurrentWorld && !isBedrock) {
                add(
                    action(
                        DiscoveryWorldAction.PREVIEW,
                        MenuGesture.PLAIN_RIGHT,
                        plugin.getMessage(player, "gui.discovery.world_item.preview_hint"),
                        payload,
                        MenuActionSafety.EXTERNAL_SIDE_EFFECT,
                    ),
                )
            }
            if (!isBedrock && !isMember) {
                add(
                    action(
                        DiscoveryWorldAction.REQUEST_MEMBERSHIP,
                        MenuGesture.SHIFT_LEFT,
                        plugin.getMessage(player, "gui.discovery.world_item.member_request_hint"),
                        payload,
                        MenuActionSafety.CONFIRM_ENTRY,
                    ),
                )
                add(
                    action(
                        DiscoveryWorldAction.TOGGLE_FAVORITE,
                        MenuGesture.SHIFT_RIGHT,
                        plugin.getMessage(
                            player,
                            if (isFavorited) {
                                "gui.discovery.world_item.favorite_hint_remove"
                            } else {
                                "gui.discovery.world_item.favorite_hint_add"
                            },
                        ),
                        payload,
                        MenuActionSafety.REVERSIBLE,
                        MyWorldManagerApi.getWorldListMenuService().favoriteToggleContract(),
                        ChanponReversibleActionClassification.Operation.FAVORITE_TOGGLE,
                    ),
                )
            }
        }
        val tagNames = data.tags.takeIf { it.isNotEmpty() }?.joinToString(", ") { tagId ->
            MyWorldManagerApi.getWorldTagService()?.getDisplayName(player, tagId) ?: tagId
        }
        val ownerName = Bukkit.getOfflinePlayer(data.owner).name
            ?: plugin.getMessage(player, "general.unknown")
        val dataLines = buildList {
            add(GuiMenuEntryData(plugin.getMessage(player, "gui.common.world_item.owner"), ownerName))
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
            tagNames?.let {
                add(GuiMenuEntryData(plugin.getMessage(player, "gui.common.world_item.tags"), it, GuiValueTone.PRIMARY))
            }
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
                actions = actions,
            ),
        )
    }

    private fun action(
        action: DiscoveryWorldAction,
        gesture: MenuGesture,
        label: String,
        payload: Map<String, String>,
        safety: MenuActionSafety,
        reversibleContract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract? = null,
        classification: ChanponReversibleActionClassification.Operation? = null,
    ) = chanponGestureAction(
        actionId = ACTION_WORLD,
        gesture = gesture,
        label = label,
        payload = payload + (WORLD_ACTION to action.name),
        safety = safety,
        reversibleContract = reversibleContract,
        classification = classification,
    )

    private fun infoEntry(player: Player, slot: Int): MenuElement =
        CCSystem.getAPI().getGuiElementService().menuDisplay(
            GuiMenuDisplaySpec(
                slot,
                GuiItemSpec(
                    material = Material.BOOK,
                    name = GuiNameSpec.FixedLabel(i18nComponent(player, "info.name")),
                    lore = GuiLoreSpec.Blocks(
                        listOf(
                            GuiLoreBlock(i18nList(player, "info.lore").map(GuiLoreLine::Text)),
                        ),
                    ),
                    role = GuiElementRole.CONTENT,
                    amount = 1,
                ),
            ),
        )

    private fun navigationEntry(player: Player, slot: Int, next: Boolean, targetPage: Int): MenuElement {
        val labelKey = if (next) "gui.common.next_page" else "gui.common.prev_page"
        val iconKey = if (next) "next_page" else "prev_page"
        val label = plugin.getMessage(player, labelKey)
        return CCSystem.getAPI().getGuiElementService().menuEntry(
            player,
            GuiMenuEntrySpec(
                slot = slot,
                material = MyWorldManagerApi.getConfiguredMenuIcon("discovery", iconKey, Material.ARROW),
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

    private fun warnMissingCompletion(worldData: WorldData) {
        if (warnedMissingCompletion.add(worldData.uuid)) {
            plugin.logger.warning(
                "Completed Chanpon world has no valid completion timestamp; excluding from Discovery: ${worldData.uuid}",
            )
        }
    }

    private fun i18n(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.discovery.$key")

    private fun i18nList(player: Player, key: String): List<String> =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.discovery.$key")

    private fun i18nComponent(player: Player, key: String): net.kyori.adventure.text.Component =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.discovery.$key")

    private data class CompletedWorld(
        val world: WorldData,
        val completedAt: LocalDateTime,
    )

    private companion object {
        const val OWNER = "mwm-chanpon"
        const val ROUTE_ID = "discovery"
        const val PAGE = "page"
        const val SHOW_BACK = "show_back"
        const val WORLD_UUID = "world_uuid"
        const val WORLD_ACTION = "world_action"
        const val ACTION_WORLD = "world"
        const val ACTION_PAGE = "page"
        const val ACTION_BACK = "back"
    }
}
