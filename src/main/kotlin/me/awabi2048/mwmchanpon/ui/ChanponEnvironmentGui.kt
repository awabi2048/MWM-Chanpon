package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsInventoryHolder
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.Material
import org.bukkit.GameMode
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
import java.util.concurrent.ConcurrentHashMap
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.util.cancelWithDebug

class ChanponEnvironmentGui(
    private val plugin: MWMChanponPlugin,
    storage: me.awabi2048.mwmchanpon.storage.ChanponWorldStorage,
    protectionService: me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService,
) : Listener {
    private val accessGuard = WorldSettingsAccessGuard(storage, protectionService)
    private val pendingTimeInputs = ConcurrentHashMap<UUID, UUID>()

    fun open(player: Player, worldData: WorldData) {
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        val holder = EnvMainHolder(worldData.uuid)
        val inventory = Bukkit.createInventory(holder, 45, i18nComponent(player, "title"))
        holder.inv = inventory
        applyFrame(inventory)

        val weather = worldData.fixedWeather
        val time = worldData.fixedTime
        val gravity = worldData.gravityValue
        val scale = worldData.fixedScale
        val allowFlight = worldData.allowFlight

        inventory.setItem(19, directSettingItem(player, ACTION_WEATHER, Material.LIGHT_BLUE_WOOL, weatherDisplay(player, weather), weatherOptions(player), weather ?: NONE_KEY))
        inventory.setItem(20, directSettingItem(player, ACTION_TIME, Material.CLOCK, timeDisplay(player, time), timeOptions(player, time), timeOptionKey(time), customTimeAction = true))
        inventory.setItem(21, directSettingItem(player, ACTION_GRAVITY, Material.FEATHER, gravityDisplay(player, gravity), gravityOptions(player), (gravity ?: DEFAULT_GRAVITY).toString()))
        inventory.setItem(23, directSettingItem(player, ACTION_SCALE, Material.PLAYER_HEAD, scaleDisplay(player, scale), scaleOptions(player), scale?.toString()))
        inventory.setItem(24, directSettingItem(player, ACTION_FLIGHT, Material.ELYTRA, flightDisplay(player, allowFlight), flightOptions(player), allowFlight.toString()))
        inventory.setItem(25, emptySettingSlot())
        inventory.setItem(40, backItem(player))

        fill(inventory)
        player.openInventory(inventory)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder
        if (holder !is EnvMainHolder) {
            return
        }
        event.cancelWithDebug("ChanponEnvironmentGui.onClick: menu_click")
        if (event.clickedInventory != event.view.topInventory) return
        val item = event.currentItem ?: return

        handleMainClick(event, player, holder.worldUuid, item)
    }

    private fun handleMainClick(event: InventoryClickEvent, player: Player, worldUuid: UUID, item: ItemStack) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        when (ItemTag.getString(item, ACTION_KEY)) {
            ACTION_WEATHER -> cycleWeather(player, worldData, reverse = event.isRightClick)
            ACTION_TIME -> {
                if (event.isShiftClick && event.isLeftClick) {
                    showCustomTimeDialog(player, worldData)
                } else {
                    cycleTime(player, worldData, reverse = event.isRightClick)
                }
            }
            ACTION_GRAVITY -> cycleGravity(player, worldData, reverse = event.isRightClick)
            ACTION_SCALE -> cycleScale(player, worldData, reverse = event.isRightClick)
            ACTION_FLIGHT -> toggleFlight(player, worldData)
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

    @EventHandler
    fun onCustomTimeInput(event: PlayerCustomClickEvent) {
        val identifier = event.identifier
        if (identifier != Key.key("mwmchanpon:environment/time_submit") &&
            identifier != Key.key("mwmchanpon:environment/time_cancel")
        ) {
            return
        }

        val conn = event.commonConnection as? PlayerGameConnection ?: return
        val player = conn.player
        val worldUuid = pendingTimeInputs.remove(player.uniqueId) ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (identifier == Key.key("mwmchanpon:environment/time_cancel")) {
            open(player, worldData)
            return
        }

        val input = event.getDialogResponseView()?.getText("custom_time").orEmpty()
        val parsed = parseCustomTime(input)
        if (parsed == null) {
            player.sendMessage(i18n(player, "time.custom.invalid"))
            showCustomTimeDialog(player, worldData, input)
            return
        }

        worldData.fixedTime = parsed
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyTime(worldUuid, parsed)
        open(player, worldData)
    }

    private fun cycleWeather(player: Player, worldData: WorldData, reverse: Boolean) {
        val next = nextNullableValue(worldData.fixedWeather, WEATHER_VALUES, reverse)
        worldData.fixedWeather = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyWeather(worldData.uuid, next)
        open(player, worldData)
    }

    private fun cycleTime(player: Player, worldData: WorldData, reverse: Boolean) {
        val next = nextNullableValue(worldData.fixedTime, TIME_VALUES, reverse)
        worldData.fixedTime = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyTime(worldData.uuid, next)
        open(player, worldData)
    }

    private fun cycleGravity(player: Player, worldData: WorldData, reverse: Boolean) {
        val next = nextValue(worldData.gravityValue, GRAVITY_VALUES, reverse)
        worldData.gravityValue = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyGravityToWorld(worldData.uuid, next)
        open(player, worldData)
    }

    private fun cycleScale(player: Player, worldData: WorldData, reverse: Boolean) {
        val next = nextValue(worldData.fixedScale, SCALE_VALUES, reverse)
        worldData.fixedScale = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyScaleToWorld(worldData.uuid, next)
        open(player, worldData)
    }

    private fun toggleFlight(player: Player, worldData: WorldData) {
        worldData.allowFlight = !worldData.allowFlight
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyFlightToWorld(worldData.uuid, worldData.allowFlight)
        open(player, worldData)
    }

    private fun showCustomTimeDialog(player: Player, worldData: WorldData, initial: String = worldData.fixedTime?.toString() ?: "") {
        pendingTimeInputs[player.uniqueId] = worldData.uuid
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(i18nComponent(player, "time.custom.title"))
                        .body(listOf(DialogBody.plainMessage(i18nComponent(player, "time.custom.body"))))
                        .inputs(
                            listOf(
                                DialogInput.text("custom_time", i18nComponent(player, "time.custom.label"))
                                    .initial(initial)
                                    .maxLength(8)
                                    .build()
                            )
                        )
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        ActionButton.create(
                            i18nComponent(player, "time.custom.apply"),
                            null,
                            100,
                            DialogAction.customClick(Key.key("mwmchanpon:environment/time_submit"), null)
                        ),
                        ActionButton.create(
                            Component.text(i18n(player, "common.back"), NamedTextColor.GRAY),
                            null,
                            200,
                            DialogAction.customClick(Key.key("mwmchanpon:environment/time_cancel"), null)
                        )
                    )
                )
        }
        player.showDialog(dialog)
    }

    private fun parseCustomTime(input: String): Long? {
        val normalized = input.trim()
        val tickValue = normalized.toLongOrNull()
        if (tickValue != null) return tickValue.takeIf { it in 0L..23999L }

        val parts = normalized.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return Math.floorMod((hour * 1000L + minute * 1000L / 60L) - 6000L, 24000L)
    }

    private fun applyFlightToWorld(worldUuid: UUID, allowFlight: Boolean) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.$worldUuid"
        val world = Bukkit.getWorld(worldName) ?: return
        world.players.forEach { p ->
            val gameModeAllowsFlight = p.gameMode == GameMode.CREATIVE || p.gameMode == GameMode.SPECTATOR
            if (allowFlight || gameModeAllowsFlight) {
                p.allowFlight = true
            } else {
                if (p.isFlying) p.isFlying = false
                p.allowFlight = false
            }
        }
    }

    private fun <T> nextValue(current: T?, values: List<T>, reverse: Boolean): T {
        val index = values.indexOf(current)
        if (index < 0) {
            return if (reverse) values.last() else values.first()
        }
        val nextIndex = if (reverse) {
            (index + values.size - 1) % values.size
        } else {
            (index + 1) % values.size
        }
        return values[nextIndex]
    }

    private fun <T> nextNullableValue(current: T?, values: List<T?>, reverse: Boolean): T? {
        val index = values.indexOf(current)
        if (index < 0) {
            return if (reverse) values.last() else values.first()
        }
        val nextIndex = if (reverse) {
            (index + values.size - 1) % values.size
        } else {
            (index + 1) % values.size
        }
        return values[nextIndex]
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

    private fun applyWeather(worldUuid: UUID, weather: String?) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.$worldUuid"
        val world = Bukkit.getWorld(worldName) ?: return
        if (weather == null) {
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, true)
            world.weatherDuration = 0
            world.thunderDuration = 0
            return
        }
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
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

    private fun applyTime(worldUuid: UUID, time: Long?) {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        val worldName = worldData.customWorldName ?: "my_world.$worldUuid"
        val world = Bukkit.getWorld(worldName) ?: return
        if (time == null) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true)
            return
        }
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

    private fun directSettingItem(
        player: Player,
        key: String,
        material: Material,
        currentValue: String,
        options: List<SettingOption>,
        currentKey: String?,
        customTimeAction: Boolean = false
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(buildList {
                add(GuiLoreLine.Data(i18n(player, "common.current_label"), currentValue, "\u00A7e"))
                add(GuiLoreLine.Warning(i18n(player, "common.must_be_in_world_warning")))
                add(GuiLoreLine.Text(i18n(player, "$key.description")))
                add(GuiLoreLine.Spacer)
                options.forEach { option ->
                    val selected = option.key == currentKey
                    val marker = if (selected) "\u00A7a\u00BB" else "\u00A78\u30FB"
                    val color = if (selected) option.selectedColor else "\u00A77"
                    add(GuiLoreLine.Raw("$marker $color${option.display}"))
                }
                add(GuiLoreLine.Spacer)
                add(GuiLoreLine.SingleAction(i18n(player, "common.action_next")))
            }, GuiLoreFrame.BOTH)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setString(item, ACTION_KEY, key)
        return item
    }

    private fun emptySettingSlot(): ItemStack {
        return decoration(Material.WHITE_STAINED_GLASS_PANE)
    }

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

    private fun weatherOptions(player: Player): List<SettingOption> {
        return listOf(SettingOption(NONE_KEY, i18n(player, "common.not_set"), "\u00A77")) +
                WEATHER_VALUES.filterNotNull().map { weather ->
            SettingOption(weather, i18n(player, "weather.${weather.lowercase()}.display"), weatherColor(weather))
        }
    }

    private fun timeDisplay(player: Player, time: Long?): String {
        return when (time) {
            0L -> i18n(player, "time.morning.display")
            6000L -> i18n(player, "time.noon.display")
            12500L -> i18n(player, "time.dusk.display")
            18000L -> i18n(player, "time.night.display")
            null -> i18n(player, "common.not_set")
            else -> i18n(player, "time.custom.display", mapOf("time" to time))
        }
    }

    private fun timeOptions(player: Player, current: Long?): List<SettingOption> {
        val base = listOf(
            SettingOption(NONE_KEY, i18n(player, "common.not_set"), "\u00A77"),
            SettingOption("0", i18n(player, "time.morning.display"), "\u00A7e"),
            SettingOption("6000", i18n(player, "time.noon.display"), "\u00A7e"),
            SettingOption("12500", i18n(player, "time.dusk.display"), "\u00A76"),
            SettingOption("18000", i18n(player, "time.night.display"), "\u00A79")
        )
        if (current != null && current !in TIME_VALUES.filterNotNull()) {
            return base + SettingOption("CUSTOM", timeDisplay(player, current), "\u00A7d")
        }
        return base
    }

    private fun timeOptionKey(time: Long?): String? {
        return when (time) {
            null -> NONE_KEY
            in TIME_VALUES.filterNotNull() -> time.toString()
            else -> "CUSTOM"
        }
    }

    private fun flightDisplay(player: Player, allowFlight: Boolean): String {
        return i18n(player, if (allowFlight) "flight.enabled" else "flight.disabled")
    }

    private fun flightDisplay(player: Player, allowFlight: Boolean?): String {
        return flightDisplay(player, allowFlight == true)
    }

    private fun flightOptions(player: Player): List<SettingOption> {
        return listOf(
            SettingOption("true", i18n(player, "flight.enabled"), "\u00A7a"),
            SettingOption("false", i18n(player, "flight.disabled"), "\u00A7c")
        )
    }

    private fun gravityDisplay(player: Player, gravity: Double?): String {
        return when (gravity) {
            null -> i18n(player, "gravity.earth.display")
            0.08 -> i18n(player, "gravity.earth.display")
            0.02 -> i18n(player, "gravity.mars.display")
            0.01 -> i18n(player, "gravity.moon.display")
            else -> i18n(player, "common.not_set")
        }
    }

    private fun gravityOptions(player: Player): List<SettingOption> {
        return GRAVITY_VALUES.map { gravity ->
            val key = when (gravity) {
                0.02 -> "mars"
                0.01 -> "moon"
                else -> "earth"
            }
            SettingOption(gravity.toString(), i18n(player, "gravity.$key.display"), "\u00A7e")
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

    private fun scaleOptions(player: Player): List<SettingOption> {
        return SCALE_VALUES.map { scale ->
            val key = when (scale) {
                0.5 -> "small"
                2.0 -> "giant"
                else -> "normal"
            }
            SettingOption(scale.toString(), i18n(player, "scale.$key.display"), "\u00A7e")
        }
    }

    private fun weatherColor(weather: String): String {
        return when (weather) {
            "CLEAR" -> "\u00A7e"
            "RAIN" -> "\u00A7b"
            "THUNDER" -> "\u00A73"
            else -> "\u00A7f"
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

    private data class SettingOption(
        val key: String,
        val display: String,
        val selectedColor: String
    )

    companion object {
        private const val ACTION_KEY = "chanpon_env_action"
        private const val ACTION_WEATHER = "weather"
        private const val ACTION_TIME = "time"
        private const val ACTION_GRAVITY = "gravity"
        private const val ACTION_SCALE = "scale"
        private const val ACTION_FLIGHT = "flight"
        private const val NONE_KEY = "NONE"
        private const val DEFAULT_GRAVITY = 0.08
        private const val DEFAULT_SCALE = 1.0
        private val WEATHER_VALUES = listOf<String?>(null, "CLEAR", "RAIN", "THUNDER")
        private val TIME_VALUES = listOf<Long?>(null, 0L, 6000L, 12500L, 18000L)
        private val GRAVITY_VALUES = listOf(0.08, 0.02, 0.01)
        private val SCALE_VALUES = listOf(1.0, 0.5, 2.0)
    }
}
