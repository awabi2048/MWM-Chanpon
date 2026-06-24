package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.mwmchanpon.util.cancelWithDebug
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class ChanponAdminMenu(
    private val plugin: MWMChanponPlugin,
    private val globalStorage: ChanponGlobalStorage,
    private val spawnProtection: SpawnWorldProtectionService,
    private val submissionAdminMenu: SubmissionAdminMenu,
    private val worldDataManagementMenu: WorldDataManagementMenu,
    private val automationSettingsMenu: AutomationSettingsMenu,
    private val worldDataExportMenu: WorldDataExportMenu
) : Listener {
    fun open(player: Player) {
        if (!player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage(i18nComponent(player, "admin.no_permission"))
            return
        }
        val holder = Holder()
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "admin.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(19, menuItem(player, Material.LIME_BANNER, "admin.submission", ACTION_SUBMISSION))
        inventory.setItem(21, menuItem(player, Material.CHEST, "admin.world_data", ACTION_WORLD_DATA))
        inventory.setItem(23, menuItem(player, Material.CLOCK, "admin.automation", ACTION_AUTOMATION))
        inventory.setItem(25, menuItem(player, Material.DISPENSER, "admin.export", ACTION_EXPORT))
        inventory.setItem(38, worldCreationItem(player))
        inventory.setItem(40, infoItem(player))
        currentWorldUuid(player)?.let { worldUuid ->
            inventory.setItem(42, spawnWorldItem(player, worldUuid))
        }
        fill(inventory)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder
        if (holder !is Holder && holder !is SpawnConfirmHolder) return
        event.cancelWithDebug("ChanponAdminMenu.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return
        when (holder) {
            is Holder -> when (ItemTag.getString(item, ACTION_KEY)) {
                ACTION_SUBMISSION -> openChild(player) { submissionAdminMenu.open(player) }
                ACTION_WORLD_DATA -> openChild(player) { worldDataManagementMenu.open(player) }
                ACTION_AUTOMATION -> openChild(player) { automationSettingsMenu.open(player) }
                ACTION_EXPORT -> openChild(player) { worldDataExportMenu.open(player) }
                ACTION_WORLD_CREATION -> {
                    globalStorage.setWorldCreationEnabled(!globalStorage.isWorldCreationEnabled())
                    open(player)
                }
                ACTION_SET_SPAWN_WORLD -> {
                    val worldUuid = ItemTag.getWorldUuid(item) ?: return
                    openSpawnConfirm(player, worldUuid)
                }
            }
            is SpawnConfirmHolder -> when (ItemTag.getType(item)) {
                ItemTag.TYPE_GUI_CONFIRM -> {
                    globalStorage.setSpawnWorldUuid(holder.worldUuid)
                    MyWorldManagerApi.getWorldRepository()?.findByUuid(holder.worldUuid)?.let { worldData ->
                        if (!globalStorage.isSpawnPolicyInitialized(holder.worldUuid) && spawnProtection.applyDefaults(worldData)) {
                            globalStorage.setSpawnPolicyInitialized(holder.worldUuid)
                        }
                    }
                    player.sendMessage(i18nComponent(player, "spawn_world.set_success"))
                    open(player)
                }
                ItemTag.TYPE_GUI_CANCEL -> open(player)
            }
        }
    }

    private fun openSpawnConfirm(player: Player, worldUuid: java.util.UUID) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val holder = SpawnConfirmHolder(worldUuid)
        val layout = ChanponGuiElements.confirmationLayout()
        val inventory = Bukkit.createInventory(holder, layout.size, i18nComponent(player, "spawn_world.confirm.title"))
        holder.inv = inventory
        applyFrame(inventory)
        ChanponGuiElements.setConfirmationItems(
            inventory,
            menuItem(player, Material.COMPASS, "spawn_world.confirm.center", ACTION_NONE).also {
                it.editMeta { meta ->
                    meta.displayName(i18nComponent(player, "spawn_world.confirm.center.display", mapOf("world" to worldData.name)))
                    meta.lore(lore(i18nList(player, "spawn_world.confirm.center.lore", mapOf("world" to worldData.name))))
                }
            },
            confirmButton(player, true),
            confirmButton(player, false)
        )
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun openChild(player: Player, opener: () -> Unit) {
        mwm().menuRouteHistory.pushCustom(player, ROUTE_ADMIN_MENU) { target ->
            open(target)
            true
        }
        opener()
    }

    private fun applyFrame(inventory: Inventory) {
        ChanponGuiElements.applyStandardFrame(inventory)
    }

    private fun fill(inventory: Inventory) {
        ChanponGuiElements.fill(inventory)
    }

    private fun menuItem(player: Player, material: Material, key: String, action: String): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(lore(i18nList(player, "$key.lore")))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, TYPE_CONTROL)
        ItemTag.setString(item, ACTION_KEY, action)
        return item
    }

    private fun spawnWorldItem(player: Player, worldUuid: java.util.UUID): ItemStack {
        val configured = globalStorage.getSpawnWorldUuid()
        val key = if (configured == null) "spawn_world.set" else "spawn_world.change"
        return menuItem(player, Material.COMPASS, key, ACTION_SET_SPAWN_WORLD).also {
            ItemTag.setWorldUuid(it, worldUuid)
        }
    }

    private fun worldCreationItem(player: Player): ItemStack {
        val enabled = globalStorage.isWorldCreationEnabled()
        val key = if (enabled) "admin.world_creation.enabled" else "admin.world_creation.disabled"
        val material = if (enabled) Material.LIME_DYE else Material.GRAY_DYE
        return menuItem(player, material, key, ACTION_WORLD_CREATION)
    }

    private fun confirmButton(player: Player, confirm: Boolean): ItemStack {
        val item = ItemStack(if (confirm) Material.LIME_CONCRETE else Material.RED_CONCRETE)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, if (confirm) "confirm.button.confirm" else "confirm.button.cancel"))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, if (confirm) ItemTag.TYPE_GUI_CONFIRM else ItemTag.TYPE_GUI_CANCEL)
        return item
    }

    private fun currentWorldUuid(player: Player): java.util.UUID? {
        return MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid
    }

    private fun infoItem(player: Player): ItemStack {
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "admin.info.display"))
            meta.lore(lore(i18nList(player, "admin.info.lore", mapOf("version" to plugin.pluginMeta.version))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_INFO)
        return item
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nComponent(player, "chanpon.backup.$key", placeholders)

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()) =
        CCSystem.getAPI().getI18nStringList(player, "chanpon.backup.$key", placeholders)

    private fun lore(lines: List<String>) = ChanponLore.build(lines)

    private fun mwm(): me.awabi2048.myworldmanager.MyWorldManager =
        org.bukkit.plugin.java.JavaPlugin.getPlugin(me.awabi2048.myworldmanager.MyWorldManager::class.java)

    private class Holder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class SpawnConfirmHolder(val worldUuid: java.util.UUID) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val TYPE_CONTROL = "chanpon_admin_control"
        private const val ACTION_KEY = "chanpon_action"
        private const val ACTION_SUBMISSION = "submission"
        private const val ACTION_WORLD_DATA = "world_data"
        private const val ACTION_AUTOMATION = "automation"
        private const val ACTION_EXPORT = "export"
        private const val ACTION_WORLD_CREATION = "world_creation"
        private const val ACTION_SET_SPAWN_WORLD = "set_spawn_world"
        private const val ACTION_NONE = "none"
        private const val ROUTE_ADMIN_MENU = "chanpon.admin_menu"
    }
}
