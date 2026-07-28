package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.EasyVoidWorldCreationService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.PlayerWorldMenuProvider
import me.awabi2048.myworldmanager.api.extension.PlayerWorldMenuRequest
import me.awabi2048.myworldmanager.api.extension.WorldCreationOperation
import me.awabi2048.myworldmanager.api.extension.WorldCreationRequest
import me.awabi2048.myworldmanager.api.service.WorldPointBillingMode
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.session.SettingsAction
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin

class ChanponPlayerWorldMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val easyVoidCreationService: EasyVoidWorldCreationService
) : PlayerWorldMenuProvider {
    private val legacy = LegacyComponentSerializer.legacySection()
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_PLAYER_WORLDS,
                renderer = { context -> render(context.player, source(context.route)) },
                actions = mapOf(
                    ACTION_PAGE to MenuActionHandler(::changePage),
                    ACTION_BACK to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                    ACTION_CREATE to MenuActionHandler(::createWorld),
                    ACTION_SETTINGS to MenuActionHandler(::openUserSettings),
                    ACTION_PENDING to MenuActionHandler(::openPending),
                    ACTION_WORLD to MenuActionHandler(::selectWorld),
                ),
            ),
        )
    }

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, request: PlayerWorldMenuRequest): Boolean {
        val mwm = mwm()
        if (mwm.playerPlatformResolver.isBedrock(player)) return false
        val targetPlayerUuid = request.targetPlayerUuid ?: player.uniqueId
        val targetPlayerName = request.targetPlayerName ?: player.name
        val opened = if (request.showBackButton) runtime.navigate(
            player,
            route(
                Source(
                    request.page,
                    request.showBackButton,
                    targetPlayerUuid,
                    targetPlayerName,
                ),
            ),
        ) else runtime.open(
            player,
            route(
                Source(
                    request.page,
                    request.showBackButton,
                    targetPlayerUuid,
                    targetPlayerName,
                ),
            ),
        )
        return opened
    }

    private fun render(player: Player, source: Source): InventoryMenuView {
        val mwm = mwm()
        val targetPlayerUuid = source.targetPlayerUuid
        val targetPlayerName = source.targetPlayerName
        val isOwnMenu = targetPlayerUuid == player.uniqueId
        val session = mwm.playerWorldSessionManager.getSession(player.uniqueId)
        session.showBackButton = source.showBackButton
        val worlds = mwm.playerWorldGui.getPlayerWorlds(targetPlayerUuid)
        val pageLayout = CCSystem.getAPI().getGuiLayoutService().sevenColumnPage(worlds.size, source.page)
        val page = pageLayout.page
        session.currentPage = page
        val pageWorlds = worlds.drop(pageLayout.startIndex).take(pageLayout.itemCount)
        val currentSource = source.copy(page = page)
        val footerStart = pageLayout.layout.size - 9

        mwm.settingsSessionManager.updateSessionAction(
            player,
            targetPlayerUuid,
            SettingsAction.PLAYER_WORLD_GUI,
            true,
            null,
            null,
            null
        )

        val elements = mutableListOf<MenuElement>()
        pageWorlds.forEachIndexed { index, worldData ->
            elements += MenuElement(
                pageLayout.layout.itemSlots[index],
                createWorldItem(player, worldData),
                GuiElementRole.ACTION,
                ACTION_WORLD,
                payload(currentSource) + (PAYLOAD_WORLD_UUID to worldData.uuid.toString()),
            )
        }

        val currentCreateCount = worlds.count { it.owner == targetPlayerUuid }
        val maxCreateCount = plugin.config.getInt("creation.max_worlds_per_player", 50)
        val creationBlockReason = creationBlockReason(player, currentCreateCount, maxCreateCount)
        if (isOwnMenu && creationBlockReason == null && hasCreatePermission(player)) {
            elements += MenuElement(
                pageLayout.layout.actionSlot - 2,
                createCreationButton(player),
                GuiElementRole.ACTION,
                ACTION_CREATE,
                payload(currentSource),
            )
        } else if (isOwnMenu && creationBlockReason != null) {
            elements += MenuElement(
                pageLayout.layout.actionSlot - 2,
                createCreationUnavailableButton(player, creationBlockReason),
                GuiElementRole.CONTENT,
            )
        }
        elements += MenuElement(
            pageLayout.layout.actionSlot,
            createPlayerInfoItem(player, targetPlayerUuid, targetPlayerName),
            GuiElementRole.CONTENT,
        )
        if (isOwnMenu) {
            elements += MenuElement(
                pageLayout.layout.actionSlot + 2,
                createUserSettingsButton(player),
                GuiElementRole.ACTION,
                ACTION_SETTINGS,
                payload(currentSource),
            )
            elements += MenuElement(
                footerStart + 7,
                createPendingButton(player),
                GuiElementRole.ACTION,
                ACTION_PENDING,
                payload(currentSource),
            )
        }

        if (page > 0) {
            elements += MenuElement(
                pageLayout.layout.previousPageSlot,
                createPageItem(player, page - 1, false),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                payload(currentSource.copy(page = page - 1)),
            )
        }
        if (CCSystem.getAPI().getMenuNavigationService().canGoBack(player)) {
            elements += MenuElement(
                pageLayout.layout.backSlot,
                createReturnItem(player),
                GuiElementRole.BACK,
                ACTION_BACK,
            )
        }
        if (page + 1 < pageLayout.totalPages) {
            elements += MenuElement(
                footerStart + 8,
                createPageItem(player, page + 1, true),
                GuiElementRole.NAVIGATION,
                ACTION_PAGE,
                payload(currentSource.copy(page = page + 1)),
            )
        }

        return InventoryMenuView(
            pageLayout.layout.size,
            inventoryTitle(mwm.languageManager.getMessage(player, "gui.player_world.title")),
            elements,
        )
    }

    private fun changePage(context: MenuActionContext): MenuActionResult {
        return MenuActionResult.Success(MenuUpdate.Replace(route(source(context.payload))))
    }

    private fun createWorld(context: MenuActionContext): MenuActionResult {
        val player = context.player
        val source = source(context.payload)
        val mwm = mwm()
        if (source.targetPlayerUuid != player.uniqueId) return MenuActionResult.Rejected()
        if (!hasCreatePermission(player)) {
            player.sendMessage(i18n(player, "message.world_creation_no_permission"))
            return MenuActionResult.Rejected()
        }
        if (context.click.isRightClick && !context.click.isShiftClick) {
            startEasyCreation(player, context.route)
            return MenuActionResult.Success(MenuUpdate.None)
        }
        val session = mwm.creationSessionManager.startSession(player.uniqueId)
        session.isDialogMode = true
        session.billingMode = WorldPointBillingMode.NONE
        player.sendMessage(mwm.languageManager.getMessage(player, "messages.wizard_start"))
        mwm.creationGui.openTypeSelection(player)
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun openUserSettings(context: MenuActionContext): MenuActionResult {
        val source = source(context.payload)
        if (source.targetPlayerUuid != context.player.uniqueId) return MenuActionResult.Rejected()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            mwm().userSettingsGui.open(context.player, true)
        })
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun openPending(context: MenuActionContext): MenuActionResult {
        val source = source(context.payload)
        if (source.targetPlayerUuid != context.player.uniqueId) return MenuActionResult.Rejected()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            mwm().pendingInteractionGui.open(
                player = context.player,
                page = 0,
                returnPage = source.page,
                showBackButton = source.showBackButton,
                fromBedrockMenu = false,
            )
        })
        return MenuActionResult.Success(MenuUpdate.None)
    }

    private fun selectWorld(context: MenuActionContext): MenuActionResult {
        val player = context.player
        val source = source(context.payload)
        val mwm = mwm()
        val uuid = context.payload[PAYLOAD_WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return MenuActionResult.Rejected()
        val worldData = mwm.worldConfigRepository.findByUuid(uuid)
            ?: return MenuActionResult.Rejected()
        if (source.targetPlayerUuid == player.uniqueId &&
            context.click.isShiftClick &&
            context.click.isLeftClick
        ) {
            val stats = mwm.playerStatsRepository.findByUuid(player.uniqueId)
            stats.worldDisplayOrder.remove(uuid)
            stats.worldDisplayOrder.add(0, uuid)
            mwm.playerStatsRepository.save(stats)
            return MenuActionResult.Success(MenuUpdate.Refresh)
        }
        if (context.click.isLeftClick && !context.click.isShiftClick) {
            if (worldData.isArchived) return MenuActionResult.Rejected()
            if (isCurrentWorld(player, worldData)) {
                if (canOpenSettings(player, worldData)) {
                    openWorldSettings(player, source, worldData)
                    return MenuActionResult.Success(MenuUpdate.None)
                }
                return MenuActionResult.Rejected()
            }
            // MWM本体の具象サービスを直接呼ぶとKotlinランタイムのクラスローダー差で壊れるため、公開API境界だけを通す。
            MyWorldManagerApi.getWorldService()?.teleportToWorld(player, uuid)
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.warp_success", mapOf("world" to worldData.name)))
            return MenuActionResult.Success(MenuUpdate.Close)
        }
        if (context.click.isRightClick &&
            !context.click.isShiftClick &&
            canOpenSettings(player, worldData)
        ) {
            openWorldSettings(player, source, worldData)
            return MenuActionResult.Success(MenuUpdate.None)
        }
        return MenuActionResult.Ignored
    }

    private fun createWorldItem(player: Player, world: WorldData): ItemStack {
        val mwm = mwm()
        val lang = mwm.languageManager
        val item = ItemStack(world.icon)
        item.editMeta { meta ->
            meta.displayName(
                lang.getComponent(player, "gui.common.world_item_name", mapOf("world" to world.name))
                    .decoration(TextDecoration.ITALIC, false)
            )
            val ownerName = Bukkit.getOfflinePlayer(world.owner).name ?: lang.getMessage(player, "general.unknown")
            val progressLine = SubmissionProgressDisplay.loreLine(player, storage, world.uuid)
            val tagNames = world.tags.joinToString(", ") { mwm.worldTagManager.getDisplayName(player, it) }
            val warpAction = lang.getMessage(player, "gui.player_world.world_item.warp")
            val settingsAction = lang.getMessage(player, "gui.player_world.world_item.settings")
            val isCurrentWorld = isCurrentWorld(player, world)
            // Chanpon changes domain values only; MWM and CC-System retain the shared block layout contract.
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(buildList {
                if (world.description.isNotBlank()) add(GuiLoreBlock(listOf(GuiLoreLine.Text(world.description))))
                add(GuiLoreBlock(
                    buildList {
                        add(GuiLoreLine.Data(CCSystem.getAPI().getI18nString(player, "gui_data.owner"), ownerName, "§f"))
                        add(GuiLoreLine.Data(CCSystem.getAPI().getI18nString(player, "gui_data.visibility"), visibilityName(player, storage.getVisibility(world.uuid)), "§e"))
                        progressLine?.let(::add)
                        tagNames.takeIf(String::isNotBlank)?.let { add(GuiLoreLine.Data(CCSystem.getAPI().getI18nString(player, "gui_data.tags"), it, "§f")) }
                    }
                ))
                if (world.isArchived) add(GuiLoreBlock(listOf(GuiLoreLine.Danger(lang.getMessage(player, "gui.player_world.world_item.expired")))))
                add(GuiLoreBlock(buildList {
                    if (isCurrentWorld) {
                        add(GuiLoreActions.singleClick(lang, player, settingsAction))
                    } else {
                        add(GuiLoreLine.Action(lang.getMessage(player, "gui.settings.click.left"), warpAction))
                        add(GuiLoreLine.Action(lang.getMessage(player, "gui.settings.click.right"), settingsAction))
                    }
                }))
            })))
            if (world.isArchived) meta.setEnchantmentGlintOverride(true)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, world.uuid)
        return item
    }

    private fun createCreationButton(player: Player): ItemStack {
        val mwm = mwm()
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(mwm.languageManager.getComponent(player, "gui.player_world.creation_button.display"))
            // 操作名は共通キー、内容はChanpon側キーとして保持し、言語ファイルへ結合済み操作行を置かない。
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(listOf(
                GuiLoreBlock(mwm.languageManager.getMessageList(player, "gui.player_world.creation_button.description").map(GuiLoreLine::Text)),
                GuiLoreBlock(listOf(
                    GuiLoreLine.Action(
                        mwm.languageManager.getMessage(player, "gui.settings.click.left"),
                        mwm.languageManager.getMessage(player, "gui.player_world.creation_button.action")
                    ),
                    GuiLoreLine.Action(
                        mwm.languageManager.getMessage(player, "gui.settings.click.right"),
                        plugin.getMessage(player, "chanpon.player_world.easy_creation.action")
                    )
                ))
            ))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_CREATION_BUTTON)
        return item
    }

    private fun startEasyCreation(player: Player, returnRoute: MenuRoute) {
        // Dialogへ移る前の位置をCC-Systemのパンくずへ積み、キャンセル時に同じ一覧を再生成する。
        easyVoidCreationService.start(player, returnRoute)
    }

    private fun createCreationUnavailableButton(player: Player, reason: CreationBlockReason): ItemStack {
        val item = ItemStack(Material.BARRIER)
        item.editMeta { meta ->
            meta.displayName(mwm().languageManager.getComponent(player, reason.displayKey))
            meta.lore(ChanponLore.build(mwm().languageManager.getMessageList(player, reason.loreKey).map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun createUserSettingsButton(player: Player): ItemStack {
        val mwm = mwm()
        val item = CCSystem.getAPI().getGuiElementService().item(
            GuiItemSpec(
                material = Material.WRITABLE_BOOK,
                name = GuiNameSpec.Text(mwm.languageManager.getMessage(player, "gui.user_settings.button.display"), GuiNameStyle.PRIMARY),
                lore = GuiLoreSpec.Blocks(listOf(GuiLoreBlock(listOf(GuiLoreActions.singleClick(
                        mwm.languageManager,
                        player,
                        mwm.languageManager.getMessage(player, "gui.user_settings.button.action")
                    ))))),
                role = GuiElementRole.ACTION,
                amount = 1
            )
        )
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_USER_SETTINGS_BUTTON)
        return item
    }

    private fun createPlayerInfoItem(player: Player, targetPlayerUuid: java.util.UUID, targetPlayerName: String?): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { rawMeta ->
            val meta = rawMeta as? SkullMeta ?: return@editMeta
            meta.owningPlayer = Bukkit.getOfflinePlayer(targetPlayerUuid)
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(targetPlayerName ?: targetPlayerUuid.toString()))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun createPendingButton(player: Player): ItemStack {
        val mwm = mwm()
        val lang = mwm.languageManager
        val pendingCount = mwm.pendingDecisionManager.getPendingCount(player.uniqueId)
        val latestPendingText = mwm.pendingDecisionManager
            .getLatestPendingCreatedAt(player.uniqueId)
            ?.let {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(it))
            }
            ?: lang.getMessage(player, "gui.player_world.pending_button.none")
        val item = ItemStack(Material.WRITABLE_BOOK)
        item.editMeta { meta ->
            meta.displayName(lang.getComponent(player, "gui.player_world.pending_button.display"))
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(listOf(
                GuiLoreBlock(
                    lang.getMessageList(player, "gui.player_world.pending_button.description")
                        .map(GuiLoreLine::Text)
                ),
                GuiLoreBlock(listOf(
                    GuiLoreLine.Data(
                        lang.getMessage(player, "gui.player_world.pending_button.count_label"),
                        pendingCount,
                        if (pendingCount > 0) "§e" else "§7"
                    ),
                    GuiLoreLine.Data(
                        lang.getMessage(player, "gui.player_world.pending_button.latest_label"),
                        latestPendingText,
                        "§b"
                    )
                )),
                GuiLoreBlock(listOf(
                    GuiLoreActions.singleClick(
                        lang,
                        player,
                        lang.getMessage(player, "gui.player_world.pending_button.action")
                    )
                ))
            ))))
            meta.setEnchantmentGlintOverride(pendingCount > 0)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_PENDING_BUTTON)
        return item
    }

    private fun createPageItem(player: Player, targetPage: Int, next: Boolean): ItemStack {
        val mwm = mwm()
        val item = ItemStack(Material.ARROW)
        item.editMeta { meta ->
            val key = if (next) "gui.common.next_page" else "gui.common.prev_page"
            meta.displayName(mwm.languageManager.getComponent(player, key))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.setTargetPage(item, targetPage)
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        return item
    }

    private fun createReturnItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(mwm().languageManager.getComponent(player, "gui.common.return"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun inventoryTitle(title: String): Component =
        Component.text(title, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)

    private fun visibilityName(player: Player, visibility: ChanponWorldVisibility): String {
        val key = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> "chanpon.publish.public"
            ChanponWorldVisibility.DEVELOPMENT -> "chanpon.publish.development"
            ChanponWorldVisibility.PRIVATE -> "chanpon.publish.private"
        }
        return plugin.getMessage(player, key)
    }

    private fun canOpenSettings(player: Player, world: WorldData): Boolean =
        player.hasPermission("myworldmanager.admin") ||
            world.owner == player.uniqueId || world.moderators.contains(player.uniqueId) || world.members.contains(player.uniqueId)

    private fun openWorldSettings(player: Player, source: Source, worldData: WorldData) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            mwm().worldSettingsGui.open(
                player,
                worldData,
                true,
                true,
                source.showBackButton
            )
        })
    }

    private fun isCurrentWorld(player: Player, world: WorldData): Boolean =
        mwm().worldConfigRepository.findByWorldName(player.world.name)?.uuid == world.uuid

    private fun canBypassWorldLimits(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin.bypass_world_limits") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("craftercrossing.test")

    private fun hasCreatePermission(player: Player): Boolean =
        player.hasPermission("myworldmanager.world.create") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin") ||
            player.hasPermission("craftercrossing.test")

    private fun creationBlockReason(player: Player, currentCreateCount: Int, maxCreateCount: Int): CreationBlockReason? {
        // 作成権限不足は Chanpon 側の作成導線でも MWM 本体と同じ理由表示に揃える。
        if (!hasCreatePermission(player)) return CreationBlockReason.NO_PERMISSION
        val creationDecision = MyWorldManagerApi.checkWorldCreation(
            WorldCreationRequest(player, player, WorldCreationOperation.NORMAL, null)
        )
        if (!creationDecision.allowed) return CreationBlockReason.POLICY_DENIED
        if (canBypassWorldLimits(player)) return null
        if (currentCreateCount >= maxCreateCount) return CreationBlockReason.NO_SLOT
        return null
    }

    private fun i18n(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")

    private fun List<String>.filterMeaningful(): List<String> =
        filter { line ->
            val stripped = line.replace(Regex("[§&][0-9A-FK-ORa-fk-or]"), "").trim()
            stripped.isNotBlank() && !stripped.all { it == '─' || it == '━' || it == '-' }
        }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private enum class CreationBlockReason(val displayKey: String, val loreKey: String) {
        POLICY_DENIED(
            "gui.player_world.creation_unavailable.policy_denied.display",
            "gui.player_world.creation_unavailable.policy_denied.lore"
        ),
        NO_SLOT(
            "gui.player_world.creation_unavailable.no_slot.display",
            "gui.player_world.creation_unavailable.no_slot.lore"
        ),
        NO_PERMISSION(
            "gui.player_world.creation_unavailable.no_permission.display",
            "gui.player_world.creation_unavailable.no_permission.lore"
        )
    }

    private data class Source(
        val page: Int,
        val showBackButton: Boolean,
        val targetPlayerUuid: java.util.UUID,
        val targetPlayerName: String?
    )

    private fun route(source: Source): MenuRoute =
        MenuRoute(RUNTIME_OWNER, ROUTE_PLAYER_WORLDS, payload(source))

    private fun source(route: MenuRoute): Source = source(route.payload)

    private fun source(payload: Map<String, String>): Source {
        val targetPlayerUuid = payload[PAYLOAD_TARGET_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: java.util.UUID(0L, 0L)
        return Source(
            page = payload[PAYLOAD_PAGE]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            showBackButton = payload[PAYLOAD_SHOW_BACK]?.toBooleanStrictOrNull() ?: false,
            targetPlayerUuid = targetPlayerUuid,
            targetPlayerName = payload[PAYLOAD_TARGET_NAME],
        )
    }

    private fun payload(source: Source): Map<String, String> = buildMap {
        put(PAYLOAD_PAGE, source.page.toString())
        put(PAYLOAD_SHOW_BACK, source.showBackButton.toString())
        put(PAYLOAD_TARGET_UUID, source.targetPlayerUuid.toString())
        source.targetPlayerName?.let { put(PAYLOAD_TARGET_NAME, it) }
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.player_world_menu"
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val ROUTE_PLAYER_WORLDS = "player-worlds"
        private const val ACTION_PAGE = "page"
        private const val ACTION_BACK = "back"
        private const val ACTION_CREATE = "create"
        private const val ACTION_SETTINGS = "settings"
        private const val ACTION_PENDING = "pending"
        private const val ACTION_WORLD = "world"
        private const val PAYLOAD_PAGE = "page"
        private const val PAYLOAD_SHOW_BACK = "show_back"
        private const val PAYLOAD_TARGET_UUID = "target_uuid"
        private const val PAYLOAD_TARGET_NAME = "target_name"
        private const val PAYLOAD_WORLD_UUID = "world_uuid"
    }
}
