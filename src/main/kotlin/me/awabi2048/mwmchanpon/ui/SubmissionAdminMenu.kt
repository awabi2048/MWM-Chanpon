package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiCycleDirection
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class SubmissionAdminMenu(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController
) : Listener {
    private var parentMenuOpener: ((Player) -> Unit)? = null

    fun setParentMenuOpener(opener: (Player) -> Unit) {
        parentMenuOpener = opener
    }

    fun open(
        player: Player,
        page: Int = 0,
        progressFilter: ProgressFilter = ProgressFilter.ALL,
        sortType: SortType = SortType.CREATED_DESC
    ) {
        if (!player.hasPermission("myworldmanager.admin")) {
            player.sendMessage(i18nComponent(player, "admin_menu.no_permission"))
            return
        }
        JavaPluginBridge.endCoreMenuSession(player)

        val worlds = filteredWorlds(progressFilter, sortType)
        val totalPages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, totalPages - 1)
        val holder = SubmissionAdminMenuHolder(safePage, progressFilter, sortType)
        val layout = ChanponGuiElements.pagedListLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "admin_menu.title"))
        holder.inv = inventory

        ChanponGuiElements.applyStandardFrame(inventory)
        ChanponGuiElements.fill(inventory)

        ChanponGuiElements.setPagedListNavigation(
            inventory,
            if (safePage > 0) navItem(player, false, safePage - 1) else null,
            if (safePage < totalPages - 1) navItem(player, true, safePage + 1) else null,
            backItem(player),
            statsItem(player)
        )

        worlds.drop(safePage * ITEMS_PER_PAGE)
            .take(ITEMS_PER_PAGE)
            .forEachIndexed { index, worldData ->
                inventory.setItem(layout.itemSlots[index], worldItem(player, worldData))
            }

        inventory.setItem(47, progressFilterItem(player, progressFilter))
        inventory.setItem(51, sortItem(player, sortType))
        inventory.setItem(52, closeSubmissionItem(player))

        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? SubmissionAdminMenuHolder ?: return
        event.cancelWithDebug("SubmissionAdminMenu.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> {
                if (!mwm().menuRouteHistory.openPrevious(player)) {
                    parentMenuOpener?.invoke(player) ?: player.closeInventory()
                }
            }
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> {
                val page = ItemTag.getTargetPage(item) ?: holder.page
                open(player, page, holder.progressFilter, holder.sortType)
            }
            TYPE_MENU_CONTROL -> handleControlItem(
                player,
                holder,
                item,
                GuiCycle.direction(event.click) ?: return
            )
            ItemTag.TYPE_GUI_WORLD_ITEM -> handleWorldItem(player, holder, item, event.click)
        }
    }

    private fun handleControlItem(
        player: Player,
        holder: SubmissionAdminMenuHolder,
        item: ItemStack,
        direction: GuiCycleDirection
    ) {
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_PROGRESS_FILTER -> open(
                player,
                0,
                GuiCycle.select(holder.progressFilter, ProgressFilter.entries, direction),
                holder.sortType
            )
            ACTION_SORT -> open(
                player,
                0,
                holder.progressFilter,
                GuiCycle.select(holder.sortType, SortType.entries, direction)
            )
            ACTION_CLOSE_SUBMISSIONS -> {
                if (plugin.isSubmissionClosed()) {
                    plugin.config.set("submission.closed", false)
                    plugin.saveConfig()
                    player.sendMessage(i18nComponent(player, "admin_menu.reopened_message"))
                    open(player, holder.page, holder.progressFilter, holder.sortType)
                } else {
                    openCloseSubmissionConfirmation(player, holder)
                }
            }
        }
    }

    @EventHandler
    fun onCloseSubmissionConfirmationClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? CloseSubmissionConfirmationHolder ?: return
        event.cancelWithDebug("SubmissionAdminMenu.onCloseSubmissionConfirmationClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return

        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CONFIRM -> {
                plugin.config.set("submission.closed", true)
                plugin.saveConfig()
                player.sendMessage(i18nComponent(player, "admin_menu.closed_message"))
                open(player, holder.page, holder.progressFilter, holder.sortType)
            }
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.page, holder.progressFilter, holder.sortType)
        }
    }

    private fun handleWorldItem(
        player: Player,
        holder: SubmissionAdminMenuHolder,
        item: ItemStack,
        clickType: ClickType
    ) {
        val worldUuid = ItemTag.getWorldUuid(item) ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return

        if (clickType == ClickType.MIDDLE) {
            openProductionToggleConfirmation(player, worldData, holder)
            return
        }

        if (clickType.isRightClick) {
            if (player.gameMode != GameMode.CREATIVE) return
            val state = stateFor(worldData)
            when (state) {
                SubmissionState.COMPLETED -> unmarkCompleted(player, worldData, holder)
                SubmissionState.UNSUBMITTED -> openCompleteUnsubmittedConfirmation(player, worldData, holder)
                SubmissionState.SUBMITTED -> markCompleted(player, worldData, holder)
            }
            return
        }

        if (!clickType.isLeftClick) return
        MyWorldManagerApi.getWorldService()?.teleportToWorld(player, worldData.uuid)
        player.closeInventory()
    }

    private fun markCompleted(player: Player, worldData: WorldData, holder: SubmissionAdminMenuHolder) {
        val current = stateFor(worldData)
        if (current == SubmissionState.COMPLETED) return

        if (!storage.isProductionWorld(worldData.uuid)) {
            player.sendMessage(i18nComponent(player, "admin_menu.not_production"))
            return
        }
        storage.setSubmissionState(
            worldData.uuid,
            SubmissionState.COMPLETED,
            player.uniqueId,
            player.name,
            "mark_completed"
        )
        protectionService.sync(worldData)
        toolPermissionController.syncWorld(worldData)
        player.sendMessage(i18nComponent(player, "admin_menu.completed_message"))
        open(player, holder.page, holder.progressFilter, holder.sortType)
    }

    private fun unmarkCompleted(player: Player, worldData: WorldData, holder: SubmissionAdminMenuHolder) {
        val current = stateFor(worldData)
        if (current != SubmissionState.COMPLETED) return

        storage.setSubmissionState(
            worldData.uuid,
            SubmissionState.SUBMITTED,
            player.uniqueId,
            player.name,
            "unmark_completed"
        )
        protectionService.sync(worldData)
        toolPermissionController.syncWorld(worldData)
        player.sendMessage(i18nComponent(player, "admin_menu.unmarked_message"))
        open(player, holder.page, holder.progressFilter, holder.sortType)
    }

    private fun filteredWorlds(
        progressFilter: ProgressFilter,
        sortType: SortType
    ): List<WorldData> {
        val filtered = MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            // 提出状況メニューは提出運用対象である本番用ワールドだけを扱う。
            .filter { storage.isProductionWorld(it.uuid) }
            .filter { worldData ->
                val state = stateFor(worldData)
                matchesProgressFilter(state, progressFilter)
            }
        return when (sortType) {
            SortType.CREATED_DESC -> filtered.sortedByDescending { it.createdAt }
            SortType.CREATED_ASC -> filtered.sortedBy { it.createdAt }
            SortType.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
        }
    }

    private fun matchesProgressFilter(state: SubmissionState, filter: ProgressFilter): Boolean {
        return when (filter) {
            ProgressFilter.ALL -> true
            ProgressFilter.UNSUBMITTED -> state == SubmissionState.UNSUBMITTED
            ProgressFilter.SUBMITTED -> state == SubmissionState.SUBMITTED
            ProgressFilter.COMPLETED -> state == SubmissionState.COMPLETED
        }
    }

    private fun stateFor(worldData: WorldData): SubmissionState {
        if (!storage.isProductionWorld(worldData.uuid)) return SubmissionState.UNSUBMITTED
        return runCatching { storage.getSubmissionState(worldData.uuid) }.getOrDefault(SubmissionState.UNSUBMITTED)
    }

    private fun productionWorlds(): List<WorldData> {
        return MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
    }

    private fun statsItem(player: Player): ItemStack {
        val worlds = productionWorlds()
        val states = worlds.mapNotNull { runCatching { storage.getSubmissionState(it.uuid) }.getOrNull() }
        val submitted = states.count { it == SubmissionState.SUBMITTED || it == SubmissionState.COMPLETED }
        val completed = states.count { it == SubmissionState.COMPLETED }
        val item = ItemStack(Material.WRITABLE_BOOK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.stats.display"))
            meta.lore(
                ChanponLore.build(
                    i18nList(
                        player,
                        "admin_menu.stats.lore",
                        mapOf(
                            "submitted" to submitted,
                            "total" to worlds.size,
                            "completed" to completed
                        )
                    ).map(GuiLoreLine::Text)
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun openCloseSubmissionConfirmation(player: Player, source: SubmissionAdminMenuHolder) {
        val holder = CloseSubmissionConfirmationHolder(source.page, source.progressFilter, source.sortType)
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "confirm.close_submissions.title"))
        holder.inv = inventory

        applyConfirmationFrame(inventory)
        ChanponGuiElements.setConfirmationItems(
            inventory,
            closeSubmissionConfirmationItem(player),
            confirmButton(player, true),
            confirmButton(player, false)
        )
        player.openInventory(inventory)
    }

    private fun openProductionToggleConfirmation(player: Player, worldData: WorldData, source: SubmissionAdminMenuHolder) {
        if (!canToggleProduction(player)) return
        val enabling = !storage.isProductionWorld(worldData.uuid)
        val holder = ProductionToggleConfirmationHolder(worldData.uuid, enabling, source.page, source.progressFilter, source.sortType)
        val key = if (enabling) "confirm.set_production" else "confirm.unset_production"
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "$key.title"))
        holder.inv = inventory
        applyConfirmationFrame(inventory)
        ChanponGuiElements.setConfirmationItems(
            inventory,
            productionToggleConfirmationItem(player, key),
            confirmButton(player, true),
            confirmButton(player, false)
        )
        player.openInventory(inventory)
    }

    private fun openCompleteUnsubmittedConfirmation(player: Player, worldData: WorldData, source: SubmissionAdminMenuHolder) {
        val holder = CompleteUnsubmittedConfirmationHolder(worldData.uuid, source.page, source.progressFilter, source.sortType)
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "confirm.complete_unsubmitted.title"))
        holder.inv = inventory
        applyConfirmationFrame(inventory)
        ChanponGuiElements.setConfirmationItems(
            inventory,
            completeUnsubmittedConfirmationItem(player),
            confirmButton(player, true),
            confirmButton(player, false)
        )
        player.openInventory(inventory)
    }

    @EventHandler
    fun onProductionToggleConfirmationClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? ProductionToggleConfirmationHolder ?: return
        event.cancelWithDebug("SubmissionAdminMenu.onProductionToggleConfirmationClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.page, holder.progressFilter, holder.sortType)
            ItemTag.TYPE_GUI_CONFIRM -> {
                if (!canToggleProduction(player)) return
                val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
                if (holder.enabling && ownerHasProductionWorld(worldData.owner, worldData.uuid)) {
                    player.sendMessage(i18nComponent(player, "admin_menu.production_limit"))
                    open(player, holder.page, holder.progressFilter, holder.sortType)
                    return
                }
                storage.setProductionWorld(worldData.uuid, holder.enabling)
                if (holder.enabling) applyForcedTags(worldData)
                protectionService.sync(worldData)
                toolPermissionController.syncWorld(worldData)
                player.sendMessage(i18nComponent(player, if (holder.enabling) "admin_menu.production_enabled" else "admin_menu.production_disabled"))
                open(player, holder.page, holder.progressFilter, holder.sortType)
            }
        }
    }

    @EventHandler
    fun onCompleteUnsubmittedConfirmationClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? CompleteUnsubmittedConfirmationHolder ?: return
        event.cancelWithDebug("SubmissionAdminMenu.onCompleteUnsubmittedConfirmationClick: confirm_click")
        if (event.clickedInventory != event.view.topInventory) return
        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.page, holder.progressFilter, holder.sortType)
            ItemTag.TYPE_GUI_CONFIRM -> {
                val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid) ?: return
                markCompleted(player, worldData, SubmissionAdminMenuHolder(holder.page, holder.progressFilter, holder.sortType))
            }
        }
    }

    private fun worldItem(player: Player, worldData: WorldData): ItemStack {
        val state = stateFor(worldData)
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.world.display", mapOf("world" to worldData.name)))
            meta.lore(structuredLore(worldLore(player, worldData, state)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, worldData.uuid)
        return item
    }

    private fun worldLore(player: Player, worldData: WorldData, state: SubmissionState): List<GuiLoreLine> {
        val unknown = i18n(player, "admin_menu.unknown")
        val ownerName = playerName(worldData.owner, unknown)
        val members = (worldData.moderators + worldData.members)
            .distinct()
            .map { playerName(it, unknown) }
        val memberLine = if (members.isEmpty()) {
            i18n(player, "admin_menu.world.no_members")
        } else {
            members.joinToString("§7, §f", prefix = "§f")
        }
        val created = formatCreatedAt(worldData.createdAt)
        val production = if (storage.isProductionWorld(worldData.uuid)) {
            i18n(player, "admin_menu.world.production_yes")
        } else {
            i18n(player, "admin_menu.world.production_no")
        }
        val completed = if (state == SubmissionState.COMPLETED) {
            i18n(player, "admin_menu.world.completed_yes")
        } else {
            i18n(player, "admin_menu.world.completed_no")
        }
        return buildList {
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.owner_label"), ownerName, "§f"))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.members_label"), memberLine, ""))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.created_label"), created, "§f"))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.production_label"), production, ""))
            add(GuiLoreLine.Data(
                i18n(player, "admin_menu.world.state_label"),
                i18n(player, "state.${state.i18nKey}"),
                when (state) {
                    SubmissionState.UNSUBMITTED -> "§6"
                    SubmissionState.SUBMITTED -> "§a"
                    SubmissionState.COMPLETED -> "§b"
                }
            ))
            add(GuiLoreLine.Data(i18n(player, "admin_menu.world.completed_label"), completed, ""))
            add(GuiLoreLine.Spacer)
            add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.left"), i18n(player, "admin_menu.world.action_warp")))
            if (player.gameMode == GameMode.CREATIVE) {
                add(GuiLoreLine.Action(mwm().languageManager.getMessage(player, "gui.settings.click.right"), i18n(player, "admin_menu.world.action_complete")))
            }
            add(GuiLoreLine.Action(i18n(player, "common.middle_click"), i18n(player, "admin_menu.world.action_toggle_production")))
        }
    }

    private fun productionToggleConfirmationItem(player: Player, key: String): ItemStack {
        val item = ItemStack(Material.HEART_OF_THE_SEA)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun completeUnsubmittedConfirmationItem(player: Player): ItemStack {
        val item = ItemStack(Material.GOLDEN_APPLE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "confirm.complete_unsubmitted.display"))
            meta.lore(ChanponLore.build(i18nList(player, "confirm.complete_unsubmitted.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun ownerHasProductionWorld(ownerUuid: UUID, excludingWorldUuid: UUID): Boolean {
        return MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            .any { it.owner == ownerUuid && it.uuid != excludingWorldUuid && storage.isProductionWorld(it.uuid) }
    }

    private fun canToggleProduction(player: Player): Boolean {
        val decision = MyWorldManagerApi.checkWorldCreation(
            me.awabi2048.myworldmanager.api.extension.WorldCreationRequest(
                actor = player,
                player = player,
                operation = me.awabi2048.myworldmanager.api.extension.WorldCreationOperation.PRODUCTION,
                type = null
            )
        )
        if (!decision.allowed) decision.denialMessage?.let(player::sendMessage)
        return decision.allowed
    }

    private fun applyForcedTags(worldData: WorldData) {
        val tags = plugin.config.getStringList("production.forced_tags")
            .mapNotNull { raw ->
                MyWorldManagerApi.getWorldTagService()?.normalizeTagId(raw)
                    ?: raw.trim().lowercase().takeIf { it.isNotBlank() }
            }
            .distinct()
        val missing = tags.filterNot { worldData.tags.contains(it) }
        if (missing.isEmpty()) return
        worldData.tags.addAll(missing)
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
    }

    private fun formatCreatedAt(value: String): String {
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        }.getOrDefault(value)
    }

    private fun playerName(uuid: UUID, fallback: String): String {
        return Bukkit.getOfflinePlayer(uuid).name ?: fallback
    }

    private fun navItem(player: Player, next: Boolean, targetPage: Int): ItemStack {
        val item = ItemStack(Material.ARROW)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, if (next) "admin_menu.next" else "admin_menu.prev"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (next) ItemTag.TYPE_GUI_NAV_NEXT else ItemTag.TYPE_GUI_NAV_PREV)
        ItemTag.setTargetPage(item, targetPage)
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.back.display"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun progressFilterItem(player: Player, filter: ProgressFilter): ItemStack {
        return filterListItem(
            player,
            Material.HOPPER,
            "admin_menu.progress_filter.display",
            ProgressFilter.entries.map { it.key },
            filter.key,
            ACTION_PROGRESS_FILTER
        )
    }

    private fun sortItem(player: Player, sortType: SortType): ItemStack {
        return filterListItem(
            player,
            Material.COMPARATOR,
            "admin_menu.sort.display",
            SortType.entries.map { it.key },
            sortType.key,
            ACTION_SORT
        )
    }

    private fun filterListItem(
        player: Player,
        material: Material,
        displayKey: String,
        optionKeys: List<String>,
        selectedKey: String,
        action: String
    ): ItemStack {
        val item = ItemStack(material)
        val lore = CCSystem.getAPI().getLoreService().render(
            GuiLoreSpec.Rich(buildList {
                optionKeys.forEach { optionKey ->
                    val selected = optionKey == selectedKey
                    add(GuiLoreLine.Option(i18n(player, optionKey), selected, "\u00A7f", "\u00A77"))
                }
                add(GuiLoreLine.Spacer)
                addAll(GuiLoreActions.cyclePreviousNext(mwm().languageManager, player))
            }, GuiLoreFrame.BOTH)
        )
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, displayKey))
            meta.lore(lore)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_MENU_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun closeSubmissionItem(player: Player): ItemStack {
        val key = if (plugin.isSubmissionClosed()) {
            "admin_menu.close.closed"
        } else {
            "admin_menu.close.open"
        }
        return extensionItem(
            player,
            if (plugin.isSubmissionClosed()) Material.BARRIER else Material.REDSTONE_BLOCK,
            "$key.display",
            "$key.lore",
            ACTION_CLOSE_SUBMISSIONS
        )
    }

    private fun closeSubmissionConfirmationItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE_BLOCK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "confirm.close_submissions.display"))
            meta.lore(ChanponLore.build(i18nList(player, "confirm.close_submissions.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun confirmButton(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        val key = if (confirm) "confirm.button.confirm" else "confirm.button.cancel"
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore").map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun applyConfirmationFrame(inventory: Inventory) {
        ChanponGuiElements.applyConfirmationFrame(inventory)
    }

    private fun extensionItem(
        player: Player,
        material: Material,
        displayKey: String,
        loreKey: String,
        action: String,
        placeholders: Map<String, Any> = emptyMap()
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, displayKey, placeholders))
            meta.lore(ChanponLore.build(i18nList(player, loreKey, placeholders).map(GuiLoreLine::Text)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_MENU_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun structuredLore(lines: List<GuiLoreLine>): List<Component> {
        return CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(lines, GuiLoreFrame.BOTH))
    }

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.submission.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.submission.$key", placeholders)
    }

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)

    enum class ProgressFilter(val key: String) {
        ALL("admin_menu.progress_filter.all"),
        UNSUBMITTED("admin_menu.progress_filter.unsubmitted"),
        SUBMITTED("admin_menu.progress_filter.submitted"),
        COMPLETED("admin_menu.progress_filter.completed")
    }

    enum class SortType(val key: String) {
        CREATED_DESC("admin_menu.sort.created_desc"),
        CREATED_ASC("admin_menu.sort.created_asc"),
        NAME_ASC("admin_menu.sort.name_asc")
    }

    private class SubmissionAdminMenuHolder(
        val page: Int,
        val progressFilter: ProgressFilter,
        val sortType: SortType
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class CloseSubmissionConfirmationHolder(
        val page: Int,
        val progressFilter: ProgressFilter,
        val sortType: SortType
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class ProductionToggleConfirmationHolder(
        val worldUuid: UUID,
        val enabling: Boolean,
        val page: Int,
        val progressFilter: ProgressFilter,
        val sortType: SortType
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class CompleteUnsubmittedConfirmationHolder(
        val worldUuid: UUID,
        val page: Int,
        val progressFilter: ProgressFilter,
        val sortType: SortType
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private object JavaPluginBridge {
        fun endCoreMenuSession(player: Player) {
            val mwm = org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)
            mwm.settingsSessionManager.endSession(player)
        }
    }

    companion object {
        const val EXTENSION_ID = "chanpon.submission_admin_menu"
        private const val ITEMS_PER_PAGE = 36
        private const val TYPE_MENU_CONTROL = "chanpon_submission_admin_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_PROGRESS_FILTER = "progress_filter"
        private const val ACTION_SORT = "sort"
        private const val ACTION_CLOSE_SUBMISSIONS = "close_submissions"
    }
}
