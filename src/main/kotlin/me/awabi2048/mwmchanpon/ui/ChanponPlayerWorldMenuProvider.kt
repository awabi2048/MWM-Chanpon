package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiItemSpec
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.GuiLoreBlock
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiNameSpec
import com.awabi2048.ccsystem.api.gui.GuiNameStyle
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.EasyVoidWorldCreationService
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.PlayerWorldMenuProvider
import me.awabi2048.myworldmanager.api.extension.PlayerWorldMenuRequest
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
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin

class ChanponPlayerWorldMenuProvider(
    private val plugin: MWMChanponPlugin,
    private val globalStorage: ChanponGlobalStorage,
    private val storage: ChanponWorldStorage,
    private val easyVoidCreationService: EasyVoidWorldCreationService
) : PlayerWorldMenuProvider, Listener {
    private val legacy = LegacyComponentSerializer.legacySection()

    override fun getId(): String = PROVIDER_ID

    override fun open(player: Player, request: PlayerWorldMenuRequest): Boolean {
        val mwm = mwm()
        if (mwm.playerPlatformResolver.isBedrock(player)) return false
        val targetPlayerUuid = request.targetPlayerUuid ?: player.uniqueId
        val targetPlayerName = request.targetPlayerName ?: player.name
        val isOwnMenu = targetPlayerUuid == player.uniqueId
        if (globalStorage.isArchiveLocked() && !player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(i18n(player, "message.archive_locked"))
            return true
        }

        val session = mwm.playerWorldSessionManager.getSession(player.uniqueId)
        session.showBackButton = request.showBackButton
        val worlds = mwm.playerWorldGui.getPlayerWorlds(targetPlayerUuid)
        val pageLayout = CCSystem.getAPI().getGuiLayoutService().sevenColumnPage(worlds.size, request.page)
        val page = pageLayout.page
        session.currentPage = page
        val pageWorlds = worlds.drop(pageLayout.startIndex).take(pageLayout.itemCount)
        val title = inventoryTitle(mwm.languageManager.getMessage(player, "gui.player_world.title"))
        val holder = Holder(page, request.showBackButton, targetPlayerUuid, targetPlayerName)
        val inventory = Bukkit.createInventory(holder, pageLayout.layout.size, title)
        holder.inv = inventory

        mwm.settingsSessionManager.updateSessionAction(
            player,
            targetPlayerUuid,
            SettingsAction.PLAYER_WORLD_GUI,
            true,
            null,
            null,
            null
        )

        applyFrame(inventory)
        pageWorlds.forEachIndexed { index, worldData ->
            inventory.setItem(pageLayout.layout.itemSlots[index], createWorldItem(player, worldData))
        }

        val currentCreateCount = worlds.count { it.owner == targetPlayerUuid }
        val configuredMaxCreateCount = mwm.config.getInt("creation.max_create_count_default", 3)
        val maxCreateCount = MyWorldManagerApi.getWorldRuntimePolicy().getMaxCreateCountDefault(configuredMaxCreateCount)
        val creationBlockReason = creationBlockReason(player, currentCreateCount, maxCreateCount)
        if (isOwnMenu && creationBlockReason == null && hasCreatePermission(player)) {
            inventory.setItem(pageLayout.layout.actionSlot - 2, createCreationButton(player))
        } else if (isOwnMenu && creationBlockReason != null) {
            inventory.setItem(pageLayout.layout.actionSlot - 2, createCreationUnavailableButton(player, creationBlockReason))
        }
        inventory.setItem(pageLayout.layout.actionSlot, createPlayerInfoItem(player, targetPlayerUuid, targetPlayerName))
        if (isOwnMenu) {
            inventory.setItem(pageLayout.layout.actionSlot + 2, createUserSettingsButton(player))
        }

        if (page > 0) {
            inventory.setItem(pageLayout.layout.previousPageSlot, createPageItem(player, page - 1, false))
        }
        if (request.showBackButton) {
            inventory.setItem(pageLayout.layout.backSlot, createReturnItem(player))
        }
        if (page + 1 < pageLayout.totalPages) {
            inventory.setItem(pageLayout.layout.nextPageSlot, createPageItem(player, page + 1, true))
        }

        mwm.soundManager.playMenuOpenSound(player, "player_world")
        player.openInventory(inventory)
        clearGuiTransitionLater(player)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.cancelWithDebug("ChanponPlayerWorldMenuProvider.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        val mwm = mwm()
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_NAV_NEXT, ItemTag.TYPE_GUI_NAV_PREV -> {
                val target = ItemTag.getTargetPage(item) ?: return
                mwm.soundManager.playClickSound(player, item, "player_world")
                open(player, PlayerWorldMenuRequest(target, holder.showBackButton, holder.targetPlayerUuid, holder.targetPlayerName))
            }
            ItemTag.TYPE_GUI_RETURN -> handleReturn(player, item)
            ItemTag.TYPE_GUI_CREATION_BUTTON -> {
                if (holder.targetPlayerUuid != player.uniqueId) return
                if (!hasCreatePermission(player)) {
                    player.sendMessage(i18n(player, "message.world_creation_no_permission"))
                    return
                }
                mwm.soundManager.playClickSound(player, item, "player_world")
                if (event.isRightClick && !event.isShiftClick) {
                    startEasyCreation(player, holder)
                    return
                }
                val session = mwm.creationSessionManager.startSession(player.uniqueId)
                session.isDialogMode = true
                player.closeInventory()
                player.sendMessage(mwm.languageManager.getMessage(player, "messages.wizard_start"))
                mwm.creationGui.openTypeSelection(player)
            }
            ItemTag.TYPE_GUI_USER_SETTINGS_BUTTON -> {
                if (holder.targetPlayerUuid != player.uniqueId) return
                mwm.soundManager.playClickSound(player, item, "player_world")
                mwm.menuRouteHistory.pushPlayerWorld(player, holder.page, holder.showBackButton)
                mwm.userSettingsGui.open(player, true)
            }
            ItemTag.TYPE_GUI_WORLD_ITEM -> handleWorldClick(player, holder, item, event)
        }
    }

    private fun handleWorldClick(player: Player, holder: Holder, item: ItemStack, event: InventoryClickEvent) {
        val mwm = mwm()
        val uuid = ItemTag.getWorldUuid(item) ?: return
        val worldData = mwm.worldConfigRepository.findByUuid(uuid) ?: return
        if (holder.targetPlayerUuid == player.uniqueId && event.isShiftClick && event.isLeftClick) {
            val stats = mwm.playerStatsRepository.findByUuid(player.uniqueId)
            stats.worldDisplayOrder.remove(uuid)
            stats.worldDisplayOrder.add(0, uuid)
            mwm.playerStatsRepository.save(stats)
            mwm.soundManager.playClickSound(player, item, "player_world")
            open(player, PlayerWorldMenuRequest(holder.page, holder.showBackButton, holder.targetPlayerUuid, holder.targetPlayerName))
            return
        }
        if (event.isLeftClick && !event.isShiftClick) {
            if (worldData.isArchived) return
            if (isCurrentWorld(player, worldData)) {
                if (canOpenSettings(player, worldData)) {
                    openWorldSettings(player, holder, item, worldData)
                }
                return
            }
            player.closeInventory()
            // MWM本体の具象サービスを直接呼ぶとKotlinランタイムのクラスローダー差で壊れるため、公開API境界だけを通す。
            MyWorldManagerApi.getWorldService()?.teleportToWorld(player, uuid)
            player.sendMessage(mwm.languageManager.getMessage(player, "messages.warp_success", mapOf("world" to worldData.name)))
            mwm.soundManager.playClickSound(player, item, "player_world")
            return
        }
        if (event.isRightClick && !event.isShiftClick && canOpenSettings(player, worldData)) {
            openWorldSettings(player, holder, item, worldData)
        }
    }

    private fun applyFrame(inventory: Inventory) {
        ChanponGuiElements.applyStandardFrame(inventory)
        ChanponGuiElements.fill(inventory)
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
            val description = if (world.description.isNotBlank()) {
                lang.getMessage(player, "gui.common.world_desc", mapOf("description" to world.description))
            } else {
                ""
            }
            val ownerName = Bukkit.getOfflinePlayer(world.owner).name ?: lang.getMessage(player, "general.unknown")
            val ownerLine = lang.getMessage(player, "gui.player_world.world_item.owner", mapOf("owner" to ownerName))
            val publishLine = lang.getMessage(
                player,
                "gui.player_world.world_item.publish",
                mapOf("level" to visibilityName(player, storage.getVisibility(world.uuid)), "status_color" to "")
            )
            val progressLine = SubmissionProgressDisplay.loreLine(player, storage, world.uuid)
            val tagLine = if (world.tags.isNotEmpty()) {
                val tagNames = world.tags.joinToString(", ") { mwm.worldTagManager.getDisplayName(player, it) }
                lang.getMessage(player, "gui.player_world.world_item.tag", mapOf("tags" to tagNames))
            } else {
                ""
            }
            val expiresAtLine = if (world.isArchived) {
                lang.getMessage(player, "gui.player_world.world_item.expired")
            } else {
                ""
            }
            val warpAction = lang.getMessage(player, "gui.player_world.world_item.warp")
            val settingsAction = lang.getMessage(player, "gui.player_world.world_item.settings")
            val isCurrentWorld = isCurrentWorld(player, world)
            // Chanpon changes domain values only; MWM and CC-System retain the shared block layout contract.
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Blocks(buildList {
                if (description.isNotBlank()) add(GuiLoreBlock(listOf(GuiLoreLine.Raw(description))))
                add(GuiLoreBlock(
                    buildList {
                        add(GuiLoreLine.Raw(ownerLine))
                        add(GuiLoreLine.Raw(publishLine))
                        progressLine?.let(::add)
                        tagLine.takeIf(String::isNotBlank)?.let { add(GuiLoreLine.Raw(it)) }
                    }
                ))
                if (expiresAtLine.isNotBlank()) add(GuiLoreBlock(listOf(GuiLoreLine.Raw(expiresAtLine))))
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

    private fun startEasyCreation(player: Player, holder: Holder) {
        // Dialogへ移る前の位置をCC-Systemのパンくずへ積み、キャンセル時に同じ一覧を再生成する。
        mwm().menuRouteHistory.pushPlayerWorld(player, holder.page, holder.showBackButton)
        easyVoidCreationService.start(player)
    }

    private fun createCreationUnavailableButton(player: Player, reason: CreationBlockReason): ItemStack {
        val item = ItemStack(Material.BARRIER)
        item.editMeta { meta ->
            meta.displayName(mwm().languageManager.getComponent(player, reason.displayKey))
            meta.lore(ChanponLore.build(mwm().languageManager.getMessageList(player, reason.loreKey)))
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

    private fun handleReturn(player: Player, item: ItemStack) {
        val mwm = mwm()
        mwm.soundManager.playClickSound(player, item, "player_world")
        if (!mwm.menuRouteHistory.openPrevious(player)) {
            player.closeInventory()
        }
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
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

    private fun openWorldSettings(player: Player, holder: Holder, item: ItemStack, worldData: WorldData) {
        val mwm = mwm()
        mwm.soundManager.playClickSound(player, item, "player_world")
        mwm.menuRouteHistory.pushPlayerWorld(player, holder.page, holder.showBackButton)
        mwm.worldSettingsGui.open(
            player,
            worldData,
            true,
            true,
            holder.showBackButton
        )
    }

    private fun isCurrentWorld(player: Player, world: WorldData): Boolean =
        mwm().worldConfigRepository.findByWorldName(player.world.name)?.uuid == world.uuid

    private fun canBypassWorldLimits(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin.bypass_world_limits") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("craftercrossing.test")

    private fun hasCreatePermission(player: Player): Boolean =
        player.hasPermission("myworldmanager.command.mwm.create") ||
            player.hasPermission("myworldmanager.admin") ||
            player.hasPermission("mwmchanpon.admin") ||
            player.hasPermission("craftercrossing.test")

    private fun creationBlockReason(player: Player, currentCreateCount: Int, maxCreateCount: Int): CreationBlockReason? {
        // 作成権限不足は Chanpon 側の作成導線でも MWM 本体と同じ理由表示に揃える。
        if (!hasCreatePermission(player)) return CreationBlockReason.NO_PERMISSION
        if (canBypassWorldLimits(player)) return null
        // MWM本体の作成停止フラグを唯一の運用フラグとして扱う。
        if (!MyWorldManagerApi.isWorldCreationEnabled()) return CreationBlockReason.PERIOD_DISABLED
        if (currentCreateCount >= maxCreateCount) return CreationBlockReason.NO_SLOT
        return null
    }

    private fun i18n(player: Player, key: String): String =
        CCSystem.getAPI().getI18nString(player, "chanpon.backup.$key")

    private fun clearGuiTransitionLater(player: Player) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val session = mwm().settingsSessionManager.getSession(player)
            if (session != null && session.isGuiTransition) {
                session.isGuiTransition = false
            }
        }, 5L)
    }

    private fun List<String>.filterMeaningful(): List<String> =
        filter { line ->
            val stripped = line.replace(Regex("[§&][0-9A-FK-ORa-fk-or]"), "").trim()
            stripped.isNotBlank() && !stripped.all { it == '─' || it == '━' || it == '-' }
        }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private enum class CreationBlockReason(val displayKey: String, val loreKey: String) {
        PERIOD_DISABLED(
            "gui.player_world.creation_unavailable.period_disabled.display",
            "gui.player_world.creation_unavailable.period_disabled.lore"
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

    private class Holder(
        val page: Int,
        val showBackButton: Boolean,
        val targetPlayerUuid: java.util.UUID,
        val targetPlayerName: String?
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val PROVIDER_ID = "chanpon.player_world_menu"
    }
}
