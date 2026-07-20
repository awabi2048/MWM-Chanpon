package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiCycleDirection
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
import me.awabi2048.myworldmanager.util.GuiLoreActions
import me.awabi2048.myworldmanager.util.ItemTag
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
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
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.mwmchanpon.util.cancelWithDebug

class ChanponEnvironmentGui(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    protectionService: me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService,
    private val buildPermissionService: WorldBuildPermissionService,
) : Listener {
    private val accessGuard = WorldSettingsAccessGuard(storage, protectionService)
    private val pendingTimeInputs = ConcurrentHashMap<UUID, UUID>()
    private val pendingProtectionPasswordInputs = ConcurrentHashMap<UUID, UUID>()

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
        val outsideTargetWorld = !isInTargetWorld(player, worldData)

        inventory.setItem(19, directSettingItem(player, ACTION_WEATHER, Material.LIGHT_BLUE_WOOL, weatherDisplay(player, weather), weatherOptions(player), weather ?: NONE_KEY, outsideTargetWorld))
        inventory.setItem(20, directSettingItem(player, ACTION_TIME, Material.CLOCK, timeDisplay(player, time), timeOptions(player, time), timeOptionKey(time), outsideTargetWorld, customTimeAction = true))
        inventory.setItem(21, directSettingItem(player, ACTION_GRAVITY, Material.FEATHER, gravityDisplay(player, gravity), gravityOptions(player), (gravity ?: DEFAULT_GRAVITY).toString(), outsideTargetWorld))
        inventory.setItem(23, directSettingItem(player, ACTION_SCALE, Material.PLAYER_HEAD, scaleDisplay(player, scale), scaleOptions(player), scale?.toString(), outsideTargetWorld))
        inventory.setItem(24, directSettingItem(player, ACTION_FLIGHT, Material.ELYTRA, flightDisplay(player, allowFlight), flightOptions(player), allowFlight.toString(), outsideTargetWorld))
        inventory.setItem(25, protectionSettingItem(player, worldData))
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
            ACTION_WEATHER -> cycleWeather(player, worldData, GuiCycle.direction(event.click) ?: return)
            ACTION_TIME -> {
                if (event.isShiftClick && event.isLeftClick) {
                    showCustomTimeDialog(player, worldData)
                } else {
                    cycleTime(player, worldData, GuiCycle.direction(event.click) ?: return)
                }
            }
            ACTION_GRAVITY -> cycleGravity(player, worldData, GuiCycle.direction(event.click) ?: return)
            ACTION_SCALE -> cycleScale(player, worldData, GuiCycle.direction(event.click) ?: return)
            ACTION_FLIGHT -> toggleFlight(player, worldData)
            ACTION_PROTECTION -> {
                val mode = storage.getProtectionMode(worldData.uuid)
                if (event.isShiftClick && event.isLeftClick && mode == ChanponWorldProtectionMode.PASSWORD) {
                    showProtectionPasswordDialog(
                        player,
                        worldData,
                        storage.getProtectionPassword(worldData.uuid).orEmpty()
                    )
                } else {
                    cycleProtection(player, worldData, GuiCycle.direction(event.click) ?: return)
                }
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

    @EventHandler
    fun onProtectionPasswordInput(event: PlayerCustomClickEvent) {
        val identifier = event.identifier
        if (identifier != Key.key("mwmchanpon:environment/protection_password_submit") &&
            identifier != Key.key("mwmchanpon:environment/protection_password_cancel")
        ) {
            return
        }

        val conn = event.commonConnection as? PlayerGameConnection ?: return
        val player = conn.player
        val worldUuid = pendingProtectionPasswordInputs.remove(player.uniqueId) ?: return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: return
        if (identifier == Key.key("mwmchanpon:environment/protection_password_cancel")) {
            open(player, worldData)
            return
        }

        val input = event.getDialogResponseView()?.getText("protection_password").orEmpty().trim()
        if (input.length < MIN_PROTECTION_PASSWORD_LENGTH) {
            player.sendMessage(i18n(player, "protection.password_too_short"))
            showProtectionPasswordDialog(player, worldData, input)
            return
        }

        storage.setProtectionPassword(worldData.uuid, input)
        buildPermissionService.sync(worldData)
        player.sendMessage(i18n(player, "protection.password_updated"))
        open(player, worldData)
    }

    private fun cycleWeather(player: Player, worldData: WorldData, direction: GuiCycleDirection) {
        val next = GuiCycle.selectNullable(worldData.fixedWeather, WEATHER_VALUES, direction)
        worldData.fixedWeather = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyWeather(worldData.uuid, next)
        open(player, worldData)
    }

    private fun cycleTime(player: Player, worldData: WorldData, direction: GuiCycleDirection) {
        // カスタム時刻は固定候補リスト外の有効値なので、循環時は未設定位置から再開する。
        val current = worldData.fixedTime.takeIf { it in TIME_VALUES }
        val next = GuiCycle.selectNullable(current, TIME_VALUES, direction)
        worldData.fixedTime = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyTime(worldData.uuid, next)
        open(player, worldData)
    }

    private fun cycleGravity(player: Player, worldData: WorldData, direction: GuiCycleDirection) {
        val next = GuiCycle.select(worldData.gravityValue ?: DEFAULT_GRAVITY, GRAVITY_VALUES, direction)
        worldData.gravityValue = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyGravityToWorld(worldData.uuid, next)
        open(player, worldData)
    }

    private fun cycleScale(player: Player, worldData: WorldData, direction: GuiCycleDirection) {
        val next = GuiCycle.select(worldData.fixedScale ?: DEFAULT_SCALE, SCALE_VALUES, direction)
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

    private fun cycleProtection(player: Player, worldData: WorldData, direction: GuiCycleDirection) {
        val current = storage.getProtectionMode(worldData.uuid)
        val next = GuiCycle.select(current, PROTECTION_VALUES, direction)
        storage.setProtectionMode(worldData.uuid, next)
        buildPermissionService.sync(worldData)
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

    private fun showProtectionPasswordDialog(player: Player, worldData: WorldData, initial: String = "") {
        pendingProtectionPasswordInputs[player.uniqueId] = worldData.uuid
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(
                    DialogBase.builder(i18nComponent(player, "protection.password_set_title"))
                        .body(listOf(DialogBody.plainMessage(i18nComponent(player, "protection.password_set_body"))))
                        .inputs(
                            listOf(
                                DialogInput.text("protection_password", i18nComponent(player, "protection.password_label"))
                                    .initial(initial)
                                    .maxLength(64)
                                    .build()
                            )
                        )
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        ActionButton.create(
                            i18nComponent(player, "protection.password_apply"),
                            null,
                            100,
                            DialogAction.customClick(Key.key("mwmchanpon:environment/protection_password_submit"), null)
                        ),
                        ActionButton.create(
                            Component.text(i18n(player, "common.back"), NamedTextColor.GRAY),
                            null,
                            200,
                            DialogAction.customClick(Key.key("mwmchanpon:environment/protection_password_cancel"), null)
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
        MyWorldManagerApi.getWorldEnvironmentService()?.applyFlight(worldUuid)
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
        MyWorldManagerApi.getWorldEnvironmentService()?.applyWeather(worldUuid)
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
        MyWorldManagerApi.getWorldEnvironmentService()?.applyTime(worldUuid)
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
        MyWorldManagerApi.getWorldEnvironmentService()?.applyAttributes(worldUuid)
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
        MyWorldManagerApi.getWorldEnvironmentService()?.applyAttributes(worldUuid)
    }

    // ---- Item helpers ----

    private fun directSettingItem(
        player: Player,
        key: String,
        material: Material,
        currentValue: String,
        options: List<SettingOption>,
        currentKey: String?,
        showWorldRequiredWarning: Boolean,
        customTimeAction: Boolean = false
    ): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(buildList {
                add(GuiLoreLine.Data(i18n(player, "common.current_label"), currentValue, "\u00A7e"))
                if (showWorldRequiredWarning) {
                    add(GuiLoreLine.Warning(i18n(player, "common.must_be_in_world_warning")))
                }
                add(GuiLoreLine.Text(i18n(player, "$key.description")))
                add(GuiLoreLine.Spacer)
                options.forEach { option ->
                    val selected = option.key == currentKey
                    add(GuiLoreLine.Option(option.display, selected, option.selectedColor, "\u00A77"))
                }
                add(GuiLoreLine.Spacer)
                addAll(
                    listNavigationActionLines(
                        player,
                        if (customTimeAction) {
                            listOf(GuiLoreLine.Action(i18n(player, "common.shift_left"), i18n(player, "time.custom.action")))
                        } else {
                            emptyList()
                        }
                    )
                )
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

    private fun protectionSettingItem(player: Player, worldData: WorldData): ItemStack {
        val mode = storage.getProtectionMode(worldData.uuid)
        val item = ItemStack(Material.IRON_DOOR)
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "protection.display"))
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(buildList {
                val password =
                    if (mode == ChanponWorldProtectionMode.PASSWORD) {
                        storage.getProtectionPassword(worldData.uuid)
                    } else {
                        null
                    }
                add(GuiLoreLine.Data(i18n(player, "common.current_label"), protectionDisplay(player, mode), "\u00A7e"))
                add(GuiLoreLine.Text(i18n(player, "protection.description")))
                if (password != null) {
                    add(GuiLoreLine.Data(i18n(player, "protection.password_label"), password, "\u00A7f"))
                }
                add(GuiLoreLine.Spacer)
                protectionOptions(player).forEach { option ->
                    val selected = option.key == mode.storageValue
                    add(GuiLoreLine.Option(option.display, selected, option.selectedColor, "\u00A77"))
                }
                if (mode == ChanponWorldProtectionMode.PASSWORD && password == null) {
                    add(GuiLoreLine.Warning(i18n(player, "protection.password_missing_warning")))
                }
                add(GuiLoreLine.Spacer)
                addAll(
                    listNavigationActionLines(
                        player,
                        if (mode == ChanponWorldProtectionMode.PASSWORD) {
                            listOf(GuiLoreLine.Action(i18n(player, "common.shift_left"), i18n(player, "protection.password_change_action")))
                        } else {
                            emptyList()
                        }
                    )
                )
            }, GuiLoreFrame.BOTH)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        ItemTag.tagItem(item, ItemTag.TYPE_GUI_EXTENSION)
        ItemTag.setString(item, ACTION_KEY, ACTION_PROTECTION)
        return item
    }

    private fun listNavigationActionLines(player: Player, extraActions: List<GuiLoreLine>): List<GuiLoreLine> {
        return listOf(
            GuiLoreLine.Action(i18n(player, "common.left_right_click"), i18n(player, "common.action_next"))
        ) + extraActions
    }

    private fun categoryItem(player: Player, key: String, currentValue: String, active: Boolean): ItemStack {
        val item = ItemStack(
            if (active) Material.GRASS_BLOCK else Material.GRASS_BLOCK
        )
        item.editMeta { meta ->
            meta.displayName(i18nComponent(player, "$key.display"))
            meta.lore(CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(listOf(
                GuiLoreLine.Data(i18n(player, "common.current_label"), currentValue, "§e"),
                GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "common.action_next"))
            ), GuiLoreFrame.BOTH)))
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
            meta.lore(optionLore(player, selectionMarker(active), i18n(player, "weather.${weather.lowercase()}.display"), weatherColor(weather)))
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
            meta.lore(optionLore(player, selectionMarker(active), i18n(player, "time.$key.display"), "§e", i18n(player, "time.$key.value")))
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
            meta.lore(optionLore(player, selectionMarker(active), i18n(player, "gravity.$key.display"), "§e", i18n(player, "gravity.$key.value")))
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
            meta.lore(optionLore(player, selectionMarker(active), i18n(player, "scale.$key.display"), "§e", i18n(player, "scale.$key.value")))
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

    private fun optionLore(player: Player, marker: String, display: String, color: String, detail: String? = null): List<Component> {
        return CCSystem.getAPI().getLoreService().render(GuiLoreSpec.Rich(buildList {
            add(GuiLoreLine.Option(detail?.let { "$display §7($it)" } ?: display, marker.contains("\u00A7a"), color, "\u00A77"))
            add(GuiLoreActions.singleClick(mwm().languageManager, player, i18n(player, "common.action_set")))
        }, GuiLoreFrame.BOTH))
    }

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

    private fun protectionDisplay(player: Player, mode: ChanponWorldProtectionMode): String =
        i18n(player, "protection.${mode.i18nKey}.display")

    private fun protectionOptions(player: Player): List<SettingOption> =
        PROTECTION_VALUES.map { mode ->
            SettingOption(mode.storageValue, protectionDisplay(player, mode), protectionColor(mode))
        }

    private fun protectionColor(mode: ChanponWorldProtectionMode): String =
        when (mode) {
            ChanponWorldProtectionMode.OWNER_ONLY -> "\u00A7c"
            ChanponWorldProtectionMode.MEMBERS -> "\u00A7e"
            ChanponWorldProtectionMode.PASSWORD -> "\u00A7d"
            ChanponWorldProtectionMode.PUBLIC -> "\u00A7a"
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

    private fun isInTargetWorld(player: Player, worldData: WorldData): Boolean {
        val currentWorldUuid = MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid
        if (currentWorldUuid == worldData.uuid) return true
        val targetWorldName = MyWorldManagerApi.getWorldService()?.getWorldFolderName(worldData)
            ?: worldData.customWorldName
            ?: "my_world.${worldData.uuid}"
        return player.world.name == targetWorldName
    }

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
        private const val ACTION_PROTECTION = "protection"
        private const val NONE_KEY = "NONE"
        private const val DEFAULT_GRAVITY = 0.08
        private const val DEFAULT_SCALE = 1.0
        private const val MIN_PROTECTION_PASSWORD_LENGTH = 4
        private val WEATHER_VALUES = listOf<String?>(null, "CLEAR", "RAIN", "THUNDER")
        private val TIME_VALUES = listOf<Long?>(null, 0L, 6000L, 12500L, 18000L)
        private val GRAVITY_VALUES = listOf(0.08, 0.02, 0.01)
        private val SCALE_VALUES = listOf(1.0, 0.5, 2.0)
        private val PROTECTION_VALUES = listOf(
            ChanponWorldProtectionMode.OWNER_ONLY,
            ChanponWorldProtectionMode.MEMBERS,
            ChanponWorldProtectionMode.PASSWORD,
            ChanponWorldProtectionMode.PUBLIC
        )
    }
}
