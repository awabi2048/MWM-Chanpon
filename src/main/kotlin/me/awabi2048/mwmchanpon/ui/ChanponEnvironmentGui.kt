package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.util.cancelWithDebug

class ChanponEnvironmentGui(
    private val plugin: MWMChanponPlugin,
    storage: me.awabi2048.mwmchanpon.storage.ChanponWorldStorage,
    protectionService: me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService,
) : Listener {
    private val accessGuard = WorldSettingsAccessGuard(storage, protectionService)

    fun open(player: Player, worldData: WorldData) {
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        val holder = EnvMainHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 27, i18nComponent(player, "title"))
        holder.inv = inventory
        applyFrame(inventory)

        val weather = worldData.fixedWeather
        val time = worldData.fixedTime
        val gravity = worldData.gravityValue
        val scale = worldData.fixedScale

        inventory.setItem(10, categoryItem(player, "weather", weatherDisplay(player, weather), weather != null))
        inventory.setItem(12, categoryItem(player, "time", timeDisplay(player, time), time != null))
        inventory.setItem(14, categoryItem(player, "gravity", gravityDisplay(player, gravity), gravity != null))
        inventory.setItem(16, categoryItem(player, "scale", scaleDisplay(player, scale), scale != null))
        inventory.setItem(22, backItem(player))

        fill(inventory)
        player.openInventory(inventory)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder
        if (holder !is EnvMainHolder &&
            holder !is EnvWeatherHolder &&
            holder !is EnvTimeHolder &&
            holder !is EnvGravityHolder &&
            holder !is EnvScaleHolder
        ) {
            return
        }
        event.cancelWithDebug("ChanponEnvironmentGui.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return

        when (holder) {
            is EnvMainHolder -> handleMainClick(event, player, holder.worldUuid, item)
            is EnvWeatherHolder -> handleWeatherClick(event, player, holder.worldUuid, item)
            is EnvTimeHolder -> handleTimeClick(event, player, holder.worldUuid, item)
            is EnvGravityHolder -> handleGravityClick(event, player, holder.worldUuid, item)
            is EnvScaleHolder -> handleScaleClick(event, player, holder.worldUuid, item)
        }
    }

    private fun handleMainClick(event: InventoryClickEvent, player: Player, worldUuid: UUID, item: ItemStack) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_WEATHER -> {
                pushMainRoute(player, worldData)
                openWeatherMenu(player, worldData)
            }
            ACTION_TIME -> {
                pushMainRoute(player, worldData)
                openTimeMenu(player, worldData)
            }
            ACTION_GRAVITY -> {
                pushMainRoute(player, worldData)
                openGravityMenu(player, worldData)
            }
            ACTION_SCALE -> {
                pushMainRoute(player, worldData)
                openScaleMenu(player, worldData)
            }
        }
        if (ItemTag.isType(item, ItemTag.TYPE_GUI_RETURN)) {
            if (!mwm().menuRouteHistory.openPrevious(player)) {
                mwm().settingsSessionManager.getSession(player)?.let { session ->
                    val worldSettings = JavaPlugin.getPlugin(MyWorldManager::class.java).worldSettingsGui
                    worldSettings.open(player, worldData, session.showBackButton, session.isPlayerWorldFlow, session.parentShowBackButton)
                }
            }
        }
    }

    // ---- Weather ----

    private fun openWeatherMenu(player: Player, worldData: WorldData) {
        val holder = EnvWeatherHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 27, i18nComponent(player, "weather.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(10, weatherOptionItem(player, "CLEAR", worldData.fixedWeather))
        inventory.setItem(13, weatherOptionItem(player, "RAIN", worldData.fixedWeather))
        inventory.setItem(16, weatherOptionItem(player, "THUNDER", worldData.fixedWeather))
        inventory.setItem(22, backToMainItem(player))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun handleWeatherClick(event: InventoryClickEvent, player: Player, worldUuid: UUID, item: ItemStack) {
        if (ItemTag.isType(item, ItemTag.TYPE_GUI_RETURN)) {
            val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
            if (!mwm().menuRouteHistory.openPrevious(player)) {
                open(player, worldData)
            }
            return
        }
        val weather = ItemTag.getString(item, ACTION_KEY) ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        worldData.fixedWeather = weather
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyWeather(worldUuid, weather)
        openWeatherMenu(player, worldData)
    }

    private fun applyWeather(worldUuid: UUID, weather: String) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.$worldUuid"
        val world = Bukkit.getWorld(worldName) ?: return
        when (weather) {
            "CLEAR" -> {
                world.setStorm(false)
                world.setThundering(false)
                world.weatherDuration = Int.MAX_VALUE
            }
            "RAIN" -> {
                world.setStorm(true)
                world.setThundering(false)
                world.weatherDuration = Int.MAX_VALUE
                world.thunderDuration = 0
            }
            "THUNDER" -> {
                world.setStorm(true)
                world.setThundering(true)
                world.weatherDuration = Int.MAX_VALUE
                world.thunderDuration = Int.MAX_VALUE
            }
        }
    }

    // ---- Time ----

    private fun openTimeMenu(player: Player, worldData: WorldData) {
        val holder = EnvTimeHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 27, i18nComponent(player, "time.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(9, timeOptionItem(player, 0L, "morning", worldData.fixedTime))
        inventory.setItem(11, timeOptionItem(player, 6000L, "noon", worldData.fixedTime))
        inventory.setItem(13, timeOptionItem(player, 12500L, "dusk", worldData.fixedTime))
        inventory.setItem(15, timeOptionItem(player, 18000L, "night", worldData.fixedTime))
        inventory.setItem(22, backToMainItem(player))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun handleTimeClick(event: InventoryClickEvent, player: Player, worldUuid: UUID, item: ItemStack) {
        if (ItemTag.isType(item, ItemTag.TYPE_GUI_RETURN)) {
            val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
            if (!mwm().menuRouteHistory.openPrevious(player)) {
                open(player, worldData)
            }
            return
        }
        val timeStr = ItemTag.getString(item, ACTION_KEY) ?: return
        val time = timeStr.toLongOrNull() ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        worldData.fixedTime = time
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyTime(worldUuid, time)
        openTimeMenu(player, worldData)
    }

    private fun applyTime(worldUuid: UUID, time: Long) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.$worldUuid"
        val world = Bukkit.getWorld(worldName) ?: return
        world.time = time
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
    }

    // ---- Gravity ----

    private fun openGravityMenu(player: Player, worldData: WorldData) {
        val holder = EnvGravityHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 27, i18nComponent(player, "gravity.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(10, gravityOptionItem(player, 0.08, "earth", worldData.gravityValue))
        inventory.setItem(13, gravityOptionItem(player, 0.02, "mars", worldData.gravityValue))
        inventory.setItem(16, gravityOptionItem(player, 0.01, "moon", worldData.gravityValue))
        inventory.setItem(22, backToMainItem(player))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun handleGravityClick(event: InventoryClickEvent, player: Player, worldUuid: UUID, item: ItemStack) {
        if (ItemTag.isType(item, ItemTag.TYPE_GUI_RETURN)) {
            val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
            if (!mwm().menuRouteHistory.openPrevious(player)) {
                open(player, worldData)
            }
            return
        }
        val gravityStr = ItemTag.getString(item, ACTION_KEY) ?: return
        val gravity = gravityStr.toDoubleOrNull() ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        worldData.gravityValue = gravity
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyGravityToWorld(worldUuid, gravity)
        openGravityMenu(player, worldData)
    }

    private fun applyGravityToWorld(worldUuid: UUID, gravity: Double) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.$worldUuid"
        val world = Bukkit.getWorld(worldName) ?: return
        val key = org.bukkit.NamespacedKey("myworldmanager", "env_gravity")
        world.players.forEach { p ->
            val attr = p.getAttribute(Attribute.GRAVITY) ?: return@forEach
            attr.modifiers.filter { it.key == key }.forEach { attr.removeModifier(it.key) }
            attr.baseValue = DEFAULT_GRAVITY
            if (gravity != DEFAULT_GRAVITY) {
                attr.addModifier(AttributeModifier(key, gravity - DEFAULT_GRAVITY, AttributeModifier.Operation.ADD_NUMBER))
            }
        }
    }

    // ---- Scale ----

    private fun openScaleMenu(player: Player, worldData: WorldData) {
        val holder = EnvScaleHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 27, i18nComponent(player, "scale.title"))
        holder.inv = inventory
        applyFrame(inventory)
        inventory.setItem(10, scaleOptionItem(player, 1.0, "normal", worldData.fixedScale))
        inventory.setItem(13, scaleOptionItem(player, 0.5, "small", worldData.fixedScale))
        inventory.setItem(16, scaleOptionItem(player, 2.0, "giant", worldData.fixedScale))
        inventory.setItem(22, backToMainItem(player))
        fill(inventory)
        player.openInventory(inventory)
    }

    private fun handleScaleClick(event: InventoryClickEvent, player: Player, worldUuid: UUID, item: ItemStack) {
        if (ItemTag.isType(item, ItemTag.TYPE_GUI_RETURN)) {
            val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
            if (!mwm().menuRouteHistory.openPrevious(player)) {
                open(player, worldData)
            }
            return
        }
        val scaleStr = ItemTag.getString(item, ACTION_KEY) ?: return
        val scale = scaleStr.toDoubleOrNull() ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        worldData.fixedScale = scale
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyScaleToWorld(worldUuid, scale)
        openScaleMenu(player, worldData)
    }

    private fun applyScaleToWorld(worldUuid: UUID, scale: Double) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.$worldUuid"
        val world = Bukkit.getWorld(worldName) ?: return
        val key = org.bukkit.NamespacedKey("myworldmanager", "env_scale")
        world.players.forEach { p ->
            val attr = p.getAttribute(Attribute.SCALE) ?: return@forEach
            attr.modifiers.filter { it.key == key }.forEach { attr.removeModifier(it.key) }
            attr.baseValue = DEFAULT_SCALE
            if (scale != DEFAULT_SCALE) {
                attr.addModifier(AttributeModifier(key, scale - DEFAULT_SCALE, AttributeModifier.Operation.ADD_NUMBER))
            }
        }
    }

    // ---- Item helpers ----

    private fun categoryItem(player: Player, key: String, currentValue: String, active: Boolean): ItemStack {
        val item = ItemStack(
            if (active) Material.GRASS_BLOCK else Material.GRASS_BLOCK
        )
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(ChanponLore.build(i18nList(player, "$key.lore", mapOf("current" to currentValue))))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setString(item, ACTION_KEY, key)
        return item
    }

    private fun weatherOptionItem(player: Player, weather: String, current: String?): ItemStack {
        val active = current == weather
        val material = when (weather) {
            "CLEAR" -> Material.YELLOW_WOOL
            "RAIN" -> Material.LIGHT_BLUE_WOOL
            "THUNDER" -> Material.CYAN_WOOL
            else -> Material.WHITE_WOOL
        }
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "weather.${weather.lowercase()}.display"))
            meta.lore(ChanponLore.build(
                i18nList(player, "weather.${weather.lowercase()}.lore", mapOf("marker" to selectionMarker(active))),
                closingSeparator = true
            ))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setString(item, ACTION_KEY, weather)
        return item
    }

    private fun timeOptionItem(player: Player, time: Long, key: String, current: Long?): ItemStack {
        val active = current == time
        val item = ItemStack(Material.CLOCK)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "time.$key.display"))
            meta.lore(ChanponLore.build(
                i18nList(player, "time.$key.lore", mapOf("marker" to selectionMarker(active))),
                closingSeparator = true
            ))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setString(item, ACTION_KEY, time.toString())
        return item
    }

    private fun gravityOptionItem(player: Player, gravity: Double, key: String, current: Double?): ItemStack {
        val active = current == gravity
        val item = ItemStack(Material.FEATHER)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "gravity.$key.display"))
            meta.lore(ChanponLore.build(
                i18nList(player, "gravity.$key.lore", mapOf("marker" to selectionMarker(active))),
                closingSeparator = true
            ))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setString(item, ACTION_KEY, gravity.toString())
        return item
    }

    private fun scaleOptionItem(player: Player, scale: Double, key: String, current: Double?): ItemStack {
        val active = current == scale
        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "scale.$key.display"))
            meta.lore(ChanponLore.build(
                i18nList(player, "scale.$key.lore", mapOf("marker" to selectionMarker(active))),
                closingSeparator = true
            ))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setString(item, ACTION_KEY, scale.toString())
        return item
    }

    private fun backToMainItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(i18n(player, "common.back")))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    private fun selectionMarker(active: Boolean): String = if (active) "§a»" else "§8・"

    private fun backItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(i18n(player, "common.back_to_settings")))
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_RETURN)
        return item
    }

    // ---- Display helpers ----

    private fun weatherDisplay(player: Player, weather: String?): String {
        return if (weather != null) i18n(player, "weather.${weather.lowercase()}.display") else i18n(player, "common.not_set")
    }

    private fun timeDisplay(player: Player, time: Long?): String {
        return when (time) {
            0L -> i18n(player, "time.morning.display")
            6000L -> i18n(player, "time.noon.display")
            12500L -> i18n(player, "time.dusk.display")
            18000L -> i18n(player, "time.night.display")
            else -> i18n(player, "common.not_set")
        }
    }

    private fun gravityDisplay(player: Player, gravity: Double?): String {
        return when (gravity) {
            0.08 -> i18n(player, "gravity.earth.display")
            0.02 -> i18n(player, "gravity.mars.display")
            0.01 -> i18n(player, "gravity.moon.display")
            else -> i18n(player, "common.not_set")
        }
    }

    private fun scaleDisplay(player: Player, scale: Double?): String {
        return when (scale) {
            1.0 -> i18n(player, "scale.normal.display")
            0.5 -> i18n(player, "scale.small.display")
            2.0 -> i18n(player, "scale.giant.display")
            else -> i18n(player, "common.not_set")
        }
    }

    // ---- Utility ----

    private fun applyFrame(inventory: Inventory) {
        ChanponGuiElements.applyStandardFrame(inventory)
    }

    private fun fill(inventory: Inventory) {
        ChanponGuiElements.fill(inventory)
    }

    private fun decoration(material: Material): ItemStack {
        return ChanponGuiElements.decoration(material)
    }

    private fun pushMainRoute(player: Player, worldData: WorldData) {
        mwm().menuRouteHistory.pushCustom(
            player,
            "chanpon.environment:${worldData.uuid}"
        ) { target ->
            val latest = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldData.uuid) ?: return@pushCustom false
            open(target, latest)
            true
        }
    }

    private fun mwm(): MyWorldManager = JavaPlugin.getPlugin(MyWorldManager::class.java)

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.environment.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.environment.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.environment.$key", placeholders)
    }

    // ---- Holders ----

    private class EnvMainHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class EnvWeatherHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class EnvTimeHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class EnvGravityHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class EnvScaleHolder(val worldUuid: UUID) : WorldSettingsInventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    companion object {
        private const val ACTION_KEY = "chanpon_env_action"
        private const val ACTION_WEATHER = "weather"
        private const val ACTION_TIME = "time"
        private const val ACTION_GRAVITY = "gravity"
        private const val ACTION_SCALE = "scale"
        private const val DEFAULT_GRAVITY = 0.08
        private const val DEFAULT_SCALE = 1.0
    }
}
