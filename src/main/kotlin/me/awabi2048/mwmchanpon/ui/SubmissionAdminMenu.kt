package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
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
        submissionFilter: SubmissionFilter = SubmissionFilter.UNSUBMITTED,
        completionFilter: CompletionFilter = CompletionFilter.UNMARKED
    ) {
        if (!player.hasPermission("myworldmanager.admin")) {
            player.sendMessage(i18nComponent(player, "admin_menu.no_permission"))
            return
        }
        JavaPluginBridge.endCoreMenuSession(player)

        val worlds = filteredWorlds(submissionFilter, completionFilter)
        val totalPages = if (worlds.isEmpty()) 1 else (worlds.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
        val safePage = page.coerceIn(0, totalPages - 1)
        val holder = SubmissionAdminMenuHolder(safePage, submissionFilter, completionFilter)
        val inventory = Bukkit.createInventory(holder, 54, i18nComponent(player, "admin_menu.title"))
        holder.inv = inventory

        val blackPane = decoration(Material.BLACK_STAINED_GLASS_PANE)
        val grayPane = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0..8) inventory.setItem(slot, blackPane)
        for (slot in 45..53) inventory.setItem(slot, blackPane)

        if (safePage > 0) {
            inventory.setItem(0, navItem(player, false, safePage - 1))
        }
        if (safePage < totalPages - 1) {
            inventory.setItem(8, navItem(player, true, safePage + 1))
        }

        worlds.drop(safePage * ITEMS_PER_PAGE)
            .take(ITEMS_PER_PAGE)
            .forEachIndexed { index, worldData ->
                inventory.setItem(9 + index, worldItem(player, worldData))
            }

        inventory.setItem(45, backItem(player))
        inventory.setItem(47, submissionFilterItem(player, submissionFilter))
        inventory.setItem(49, statsItem(player))
        inventory.setItem(51, completionFilterItem(player, completionFilter))
        inventory.setItem(53, closeSubmissionItem(player))

        for (slot in 0 until inventory.size) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, grayPane)
        }

        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? SubmissionAdminMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return

        val item = event.currentItem ?: return
        when (ItemTag.getType(item)) {
            ItemTag.TYPE_GUI_RETURN -> parentMenuOpener?.invoke(player) ?: player.closeInventory()
            ItemTag.TYPE_GUI_NAV_PREV, ItemTag.TYPE_GUI_NAV_NEXT -> {
                val page = ItemTag.getTargetPage(item) ?: holder.page
                open(player, page, holder.submissionFilter, holder.completionFilter)
            }
            TYPE_MENU_CONTROL -> handleControlItem(player, holder, item)
            ItemTag.TYPE_GUI_WORLD_ITEM -> handleWorldItem(player, holder, item, event.click)
        }
    }

    private fun handleControlItem(player: Player, holder: SubmissionAdminMenuHolder, item: ItemStack) {
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_SUBMISSION_FILTER -> open(
                player,
                0,
                holder.submissionFilter.next(),
                holder.completionFilter
            )
            ACTION_COMPLETION_FILTER -> open(
                player,
                0,
                holder.submissionFilter,
                holder.completionFilter.next()
            )
            ACTION_CLOSE_SUBMISSIONS -> {
                if (plugin.isSubmissionClosed()) {
                    plugin.config.set("submission.closed", false)
                    plugin.saveConfig()
                    player.sendMessage(i18nComponent(player, "admin_menu.reopened_message"))
                    open(player, holder.page, holder.submissionFilter, holder.completionFilter)
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
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return

        when (ItemTag.getType(event.currentItem ?: return)) {
            ItemTag.TYPE_GUI_CONFIRM -> {
                plugin.config.set("submission.closed", true)
                plugin.saveConfig()
                player.sendMessage(i18nComponent(player, "admin_menu.closed_message"))
                open(player, holder.page, holder.submissionFilter, holder.completionFilter)
            }
            ItemTag.TYPE_GUI_CANCEL -> open(player, holder.page, holder.submissionFilter, holder.completionFilter)
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
            if (player.gameMode != GameMode.CREATIVE) return
            val state = runCatching { storage.getSubmissionState(worldData.uuid) }.getOrNull() ?: return
            if (state != SubmissionState.SUBMITTED) return
            markCompleted(player, worldData, holder)
            return
        }

        if (!clickType.isLeftClick) return
        MyWorldManagerApi.getWorldService()?.teleportToWorld(player, worldData.uuid)
        player.closeInventory()
    }

    private fun markCompleted(player: Player, worldData: WorldData, holder: SubmissionAdminMenuHolder) {
        val current = try {
            storage.getSubmissionState(worldData.uuid)
        } catch (ex: IllegalStateException) {
            player.sendMessage(i18nComponent(player, "message.state_error"))
            return
        }
        if (current == SubmissionState.UNSUBMITTED) {
            player.sendMessage(i18nComponent(player, "admin_menu.complete_unsubmitted"))
            return
        }
        if (current == SubmissionState.COMPLETED) return

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
        open(player, holder.page, holder.submissionFilter, holder.completionFilter)
    }

    private fun filteredWorlds(
        submissionFilter: SubmissionFilter,
        completionFilter: CompletionFilter
    ): List<WorldData> {
        return MyWorldManagerApi.getWorldRepository()
            ?.findAll()
            .orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
            .filter { worldData ->
                val state = runCatching { storage.getSubmissionState(worldData.uuid) }.getOrNull()
                    ?: return@filter false
                matchesSubmissionFilter(state, submissionFilter)
            }
            .filter { worldData ->
                val state = runCatching { storage.getSubmissionState(worldData.uuid) }.getOrNull()
                    ?: return@filter false
                matchesCompletionFilter(state, completionFilter)
            }
            .sortedByDescending { it.createdAt }
    }

    private fun matchesSubmissionFilter(state: SubmissionState, filter: SubmissionFilter): Boolean {
        return when (filter) {
            SubmissionFilter.UNSUBMITTED -> state == SubmissionState.UNSUBMITTED
            SubmissionFilter.SUBMITTED -> state == SubmissionState.SUBMITTED || state == SubmissionState.COMPLETED
        }
    }

    private fun matchesCompletionFilter(state: SubmissionState, filter: CompletionFilter): Boolean {
        return when (filter) {
            CompletionFilter.UNMARKED -> state != SubmissionState.COMPLETED
            CompletionFilter.COMPLETED -> state == SubmissionState.COMPLETED
        }
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
                lore(
                    i18nList(
                        player,
                        "admin_menu.stats.lore",
                        mapOf(
                            "submitted" to submitted,
                            "total" to worlds.size,
                            "completed" to completed
                        )
                    )
                )
            )
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun openCloseSubmissionConfirmation(player: Player, source: SubmissionAdminMenuHolder) {
        val holder = CloseSubmissionConfirmationHolder(source.page, source.submissionFilter, source.completionFilter)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "confirm.close_submissions.title"))
        holder.inv = inventory

        applyConfirmationFrame(inventory)
        inventory.setItem(22, closeSubmissionConfirmationItem(player))
        inventory.setItem(20, confirmButton(player, true))
        inventory.setItem(24, confirmButton(player, false))
        player.openInventory(inventory)
    }

    private fun worldItem(player: Player, worldData: WorldData): ItemStack {
        val state = runCatching { storage.getSubmissionState(worldData.uuid) }.getOrDefault(SubmissionState.UNSUBMITTED)
        val item = ItemStack(worldData.icon)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin_menu.world.display", mapOf("world" to worldData.name)))
            meta.lore(lore(worldLore(player, worldData, state)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_WORLD_ITEM)
        ItemTag.setWorldUuid(item, worldData.uuid)
        return item
    }

    private fun worldLore(player: Player, worldData: WorldData, state: SubmissionState): List<String> {
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
        val completed = if (state == SubmissionState.COMPLETED) {
            i18n(player, "admin_menu.world.completed_yes")
        } else {
            i18n(player, "admin_menu.world.completed_no")
        }
        val middleAction = if (player.gameMode == GameMode.CREATIVE) {
            if (state == SubmissionState.SUBMITTED) i18n(player, "admin_menu.world.action_complete") else ""
        } else {
            ""
        }
        return i18nList(
            player,
            "admin_menu.world.lore",
            mapOf(
                "owner" to ownerName,
                "members" to memberLine,
                "created" to created,
                "state" to i18n(player, "state.${state.i18nKey}"),
                "completed" to completed,
                "middle_action" to middleAction
            )
        ).filter { it.isNotBlank() }
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
            meta.lore(lore(i18nList(player, "admin_menu.back.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun submissionFilterItem(player: Player, filter: SubmissionFilter): ItemStack {
        return extensionItem(
            player,
            Material.HOPPER,
            "admin_menu.submission_filter.display",
            "admin_menu.submission_filter.lore",
            ACTION_SUBMISSION_FILTER,
            mapOf("filter" to i18n(player, filter.key))
        )
    }

    private fun completionFilterItem(player: Player, filter: CompletionFilter): ItemStack {
        return extensionItem(
            player,
            Material.LIME_DYE,
            "admin_menu.completion_filter.display",
            "admin_menu.completion_filter.lore",
            ACTION_COMPLETION_FILTER,
            mapOf("filter" to i18n(player, filter.key))
        )
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
            meta.lore(lore(i18nList(player, "confirm.close_submissions.lore")))
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
            meta.lore(lore(i18nList(player, "$key.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun applyConfirmationFrame(inventory: Inventory) {
        val blackPane = decoration(Material.BLACK_STAINED_GLASS_PANE)
        val grayPane = decoration(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 0 until inventory.size) inventory.setItem(slot, grayPane)
        for (slot in 0..8) inventory.setItem(slot, blackPane)
        for (slot in inventory.size - 9 until inventory.size) inventory.setItem(slot, blackPane)
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
            meta.lore(lore(i18nList(player, loreKey, placeholders)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_MENU_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun decoration(material: Material): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_DECORATION)
        return item
    }

    private fun lore(lines: List<String>): List<Component> {
        return CCSystem.getAPI().buildLore(lines, closingSeparator = true)
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

    enum class SubmissionFilter(val key: String) {
        UNSUBMITTED("admin_menu.submission_filter.unsubmitted"),
        SUBMITTED("admin_menu.submission_filter.submitted");

        fun next(): SubmissionFilter = if (this == UNSUBMITTED) SUBMITTED else UNSUBMITTED
    }

    enum class CompletionFilter(val key: String) {
        UNMARKED("admin_menu.completion_filter.unmarked"),
        COMPLETED("admin_menu.completion_filter.completed");

        fun next(): CompletionFilter = if (this == UNMARKED) COMPLETED else UNMARKED
    }

    private class SubmissionAdminMenuHolder(
        val page: Int,
        val submissionFilter: SubmissionFilter,
        val completionFilter: CompletionFilter
    ) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class CloseSubmissionConfirmationHolder(
        val page: Int,
        val submissionFilter: SubmissionFilter,
        val completionFilter: CompletionFilter
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
        private const val ACTION_SUBMISSION_FILTER = "submission_filter"
        private const val ACTION_COMPLETION_FILTER = "completion_filter"
        private const val ACTION_CLOSE_SUBMISSIONS = "close_submissions"
    }
}
