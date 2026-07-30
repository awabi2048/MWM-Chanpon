package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiElementRole
import com.awabi2048.ccsystem.api.gui.GuiCycle
import com.awabi2048.ccsystem.api.gui.GuiCycleDirection
import com.awabi2048.ccsystem.api.gui.GuiLoreFrame
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import com.awabi2048.ccsystem.api.gui.GuiLoreSpec
import com.awabi2048.ccsystem.api.gui.InventoryMenuDefinition
import com.awabi2048.ccsystem.api.gui.InventoryMenuView
import com.awabi2048.ccsystem.api.gui.MenuActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionHandler
import com.awabi2048.ccsystem.api.gui.MenuActionResult
import com.awabi2048.ccsystem.api.gui.MenuDialogButton
import com.awabi2048.ccsystem.api.gui.MenuDialogHandler
import com.awabi2048.ccsystem.api.gui.MenuDialogInput
import com.awabi2048.ccsystem.api.gui.MenuDialogRequest
import com.awabi2048.ccsystem.api.gui.MenuElement
import com.awabi2048.ccsystem.api.gui.MenuRoute
import com.awabi2048.ccsystem.api.gui.MenuUpdate
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.util.UUID
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService

class ChanponEnvironmentGui(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    protectionService: me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService,
    private val buildPermissionService: WorldBuildPermissionService,
) {
    private val accessGuard = WorldSettingsAccessGuard(storage, protectionService)
    private val runtime = CCSystem.getAPI().getMenuRuntimeService()

    init {
        runtime.register(
            InventoryMenuDefinition(
                owner = RUNTIME_OWNER,
                id = ROUTE_ENVIRONMENT,
                renderer = { context -> render(context.player, world(context.route)) },
                actions = mapOf(
                    ACTION_SETTING to MenuActionHandler(::changeSetting),
                    ACTION_BACK to MenuActionHandler { MenuActionResult.Success(MenuUpdate.Back) },
                ),
            ),
        )
    }

    fun open(player: Player, worldData: WorldData, replaceCurrent: Boolean = false) {
        if (!accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)) return
        if (replaceCurrent) {
            runtime.replace(player, route(worldData.uuid))
        } else {
            runtime.navigate(player, route(worldData.uuid))
        }
    }

    private fun render(player: Player, worldData: WorldData?): InventoryMenuView {
        if (worldData == null ||
            !accessGuard.allow(player, worldData, ownerOnly = true, requireTargetWorld = true)
        ) {
            return InventoryMenuView(9, i18nComponent(player, "title"), emptyList())
        }
        val weather = worldData.fixedWeather
        val time = worldData.fixedTime
        val gravity = worldData.gravityValue
        val scale = worldData.fixedScale
        val allowFlight = worldData.allowFlight
        val allowFreecam = storage.isFreecamAllowed(worldData.uuid)
        val outsideTargetWorld = !isInTargetWorld(player, worldData)

        fun setting(slot: Int, key: String, item: ItemStack) = MenuElement(
            slot,
            item,
            GuiElementRole.ACTION,
            ACTION_SETTING,
            mapOf(PAYLOAD_WORLD_UUID to worldData.uuid.toString(), PAYLOAD_SETTING to key),
        )
        return InventoryMenuView(
            45,
            i18nComponent(player, "title"),
            listOf(
                setting(19, ACTION_WEATHER, directSettingItem(player, ACTION_WEATHER, Material.LIGHT_BLUE_WOOL, weatherDisplay(player, weather), weatherOptions(player), weather ?: NONE_KEY, outsideTargetWorld)),
                setting(20, ACTION_TIME, directSettingItem(player, ACTION_TIME, Material.CLOCK, timeDisplay(player, time), timeOptions(player, time), timeOptionKey(time), outsideTargetWorld, customTimeAction = true)),
                setting(21, ACTION_GRAVITY, directSettingItem(player, ACTION_GRAVITY, Material.FEATHER, gravityDisplay(player, gravity), gravityOptions(player), (gravity ?: DEFAULT_GRAVITY).toString(), outsideTargetWorld)),
                setting(22, ACTION_FREECAM, directSettingItem(player, ACTION_FREECAM, Material.ENDER_EYE, freecamDisplay(player, allowFreecam), freecamOptions(player), allowFreecam.toString(), outsideTargetWorld)),
                setting(23, ACTION_SCALE, directSettingItem(player, ACTION_SCALE, Material.PLAYER_HEAD, scaleDisplay(player, scale), scaleOptions(player), scale?.toString(), outsideTargetWorld)),
                setting(24, ACTION_FLIGHT, directSettingItem(player, ACTION_FLIGHT, Material.ELYTRA, flightDisplay(player, allowFlight), flightOptions(player), allowFlight.toString(), outsideTargetWorld)),
                setting(25, ACTION_PROTECTION, protectionSettingItem(player, worldData)),
                MenuElement(40, backItem(player), GuiElementRole.BACK, ACTION_BACK),
            ),
        )
    }

    private fun changeSetting(context: MenuActionContext): MenuActionResult {
        val worldData = world(context.route) ?: return MenuActionResult.Rejected()
        if (!accessGuard.allow(
                context.player,
                worldData,
                ownerOnly = true,
                requireTargetWorld = true,
            )
        ) {
            return MenuActionResult.Rejected()
        }
        val direction = GuiCycle.direction(context.click)
        when (context.payload[PAYLOAD_SETTING]) {
            ACTION_WEATHER -> cycleWeather(worldData, direction ?: return MenuActionResult.Ignored)
            ACTION_TIME -> {
                if (context.click.isShiftClick && context.click.isLeftClick) {
                    showCustomTimeDialog(context.player, worldData)
                    return MenuActionResult.Success(MenuUpdate.None)
                } else {
                    cycleTime(worldData, direction ?: return MenuActionResult.Ignored)
                }
            }
            ACTION_GRAVITY -> cycleGravity(worldData, direction ?: return MenuActionResult.Ignored)
            ACTION_SCALE -> cycleScale(worldData, direction ?: return MenuActionResult.Ignored)
            ACTION_FLIGHT -> toggleFlight(worldData)
            ACTION_FREECAM -> toggleFreecam(worldData)
            ACTION_PROTECTION -> {
                val mode = storage.getProtectionMode(worldData.uuid)
                if (context.click.isShiftClick &&
                    context.click.isLeftClick &&
                    mode == ChanponWorldProtectionMode.PASSWORD
                ) {
                    showProtectionPasswordDialog(
                        context.player,
                        worldData,
                        storage.getProtectionPassword(worldData.uuid).orEmpty(),
                    )
                    return MenuActionResult.Success(MenuUpdate.None)
                } else {
                    cycleProtection(worldData, direction ?: return MenuActionResult.Ignored)
                }
            }
            else -> return MenuActionResult.Ignored
        }
        return MenuActionResult.Success(MenuUpdate.Refresh)
    }

    private fun cycleWeather(worldData: WorldData, direction: GuiCycleDirection) {
        val next = GuiCycle.selectNullable(worldData.fixedWeather, WEATHER_VALUES, direction)
        worldData.fixedWeather = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyWeather(worldData.uuid, next)
    }

    private fun cycleTime(worldData: WorldData, direction: GuiCycleDirection) {
        // カスタム時刻は固定候補リスト外の有効値なので、循環時は未設定位置から再開する。
        val current = worldData.fixedTime.takeIf { it in TIME_VALUES }
        val next = GuiCycle.selectNullable(current, TIME_VALUES, direction)
        worldData.fixedTime = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyTime(worldData.uuid, next)
    }

    private fun cycleGravity(worldData: WorldData, direction: GuiCycleDirection) {
        val next = GuiCycle.select(worldData.gravityValue ?: DEFAULT_GRAVITY, GRAVITY_VALUES, direction)
        worldData.gravityValue = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyGravityToWorld(worldData.uuid, next)
    }

    private fun cycleScale(worldData: WorldData, direction: GuiCycleDirection) {
        val next = GuiCycle.select(worldData.fixedScale ?: DEFAULT_SCALE, SCALE_VALUES, direction)
        worldData.fixedScale = next
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyScaleToWorld(worldData.uuid, next)
    }

    private fun toggleFlight(worldData: WorldData) {
        worldData.allowFlight = !worldData.allowFlight
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        applyFlightToWorld(worldData.uuid, worldData.allowFlight)
    }

    private fun toggleFreecam(worldData: WorldData) {
        storage.setFreecamAllowed(worldData.uuid, !storage.isFreecamAllowed(worldData.uuid))
    }

    private fun cycleProtection(worldData: WorldData, direction: GuiCycleDirection) {
        val current = storage.getProtectionMode(worldData.uuid)
        val next = GuiCycle.select(current, PROTECTION_VALUES, direction)
        storage.setProtectionMode(worldData.uuid, next)
        buildPermissionService.sync(worldData)
    }

    private fun showCustomTimeDialog(player: Player, worldData: WorldData, initial: String = worldData.fixedTime?.toString() ?: "") {
        CCSystem.getAPI().getMenuDialogService().show(
            player,
            MenuDialogRequest(
                owner = "mwmchanpon",
                id = "environment-custom-time",
                title = i18nComponent(player, "time.custom.title"),
                body = listOf(i18nComponent(player, "time.custom.body")),
                inputs = listOf(
                    MenuDialogInput.Text(
                        "custom_time",
                        i18nComponent(player, "time.custom.label"),
                        initial,
                        maxLength = 8,
                    ),
                ),
                confirm = MenuDialogButton(
                    i18nComponent(player, "time.custom.apply"),
                    MenuDialogHandler { target, response ->
                        val parsed = parseCustomTime(response.textValue("custom_time"))
                            ?: return@MenuDialogHandler MenuActionResult.Rejected(
                                Component.text(i18n(target, "time.custom.invalid")),
                            )
                        worldData.fixedTime = parsed
                        MyWorldManagerApi.getWorldRepository()?.save(worldData)
                        applyTime(worldData.uuid, parsed)
                        MenuActionResult.Success(MenuUpdate.Replace(route(worldData.uuid)))
                    },
                ),
                cancel = MenuDialogButton(
                    Component.text(i18n(player, "common.back"), NamedTextColor.GRAY),
                    MenuDialogHandler { target, _ ->
                        MenuActionResult.Success(MenuUpdate.Replace(route(worldData.uuid)))
                    },
                ),
            ),
        )
    }

    private fun showProtectionPasswordDialog(player: Player, worldData: WorldData, initial: String = "") {
        CCSystem.getAPI().getMenuDialogService().show(
            player,
            MenuDialogRequest(
                owner = "mwmchanpon",
                id = "environment-protection-password",
                title = i18nComponent(player, "protection.password_set_title"),
                body = listOf(i18nComponent(player, "protection.password_set_body")),
                inputs = listOf(
                    MenuDialogInput.Text(
                        "protection_password",
                        i18nComponent(player, "protection.password_label"),
                        initial,
                        maxLength = 64,
                    ),
                ),
                confirm = MenuDialogButton(
                    i18nComponent(player, "protection.password_apply"),
                    MenuDialogHandler { target, response ->
                        val input = response.textValue("protection_password").trim()
                        if (input.length < MIN_PROTECTION_PASSWORD_LENGTH) {
                            return@MenuDialogHandler MenuActionResult.Rejected(
                                Component.text(i18n(target, "protection.password_too_short")),
                            )
                        }
                        storage.setProtectionPassword(worldData.uuid, input)
                        buildPermissionService.sync(worldData)
                        target.sendMessage(i18n(target, "protection.password_updated"))
                        MenuActionResult.Success(MenuUpdate.Replace(route(worldData.uuid)))
                    },
                ),
                cancel = MenuDialogButton(
                    Component.text(i18n(player, "common.back"), NamedTextColor.GRAY),
                    MenuDialogHandler { target, _ ->
                        MenuActionResult.Success(MenuUpdate.Replace(route(worldData.uuid)))
                    },
                ),
            ),
        )
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


    private fun applyWeather(worldUuid: UUID, weather: String?) {
        MyWorldManagerApi.getWorldEnvironmentService()?.applyWeather(worldUuid)
    }

    private fun applyTime(worldUuid: UUID, time: Long?) {
        MyWorldManagerApi.getWorldEnvironmentService()?.applyTime(worldUuid)
    }

    private fun applyGravityToWorld(worldUuid: UUID, gravity: Double) {
        MyWorldManagerApi.getWorldEnvironmentService()?.applyAttributes(worldUuid)
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
        return item
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
                CCSystem.getAPI().getGuiActionService()
                    .singleClick(player, i18n(player, "common.action_next"))
            ), GuiLoreFrame.BOTH)))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        }
        return item
    }

    private fun backItem(player: Player): ItemStack {
        val item = ItemStack(Material.REDSTONE)
        item.editMeta { meta ->
            meta.displayName(CCSystem.getAPI().getGuiElementService().name(i18n(player, "common.back_to_settings")))
        }
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

    private fun freecamDisplay(player: Player, allowed: Boolean): String {
        return i18n(player, if (allowed) "freecam.enabled" else "freecam.disabled")
    }

    private fun freecamOptions(player: Player): List<SettingOption> {
        return listOf(
            SettingOption("true", i18n(player, "freecam.enabled"), "\u00A7a"),
            SettingOption("false", i18n(player, "freecam.disabled"), "\u00A7c")
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

    private fun isInTargetWorld(player: Player, worldData: WorldData): Boolean =
        MyWorldManagerApi.getWorldService()?.isPlayerInWorld(player, worldData) == true

    private fun i18n(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, "chanpon.environment.$key", placeholders)
    }

    private fun i18nList(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
        return CCSystem.getAPI().getI18nStringList(player, "chanpon.environment.$key", placeholders)
    }

    private fun i18nComponent(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        return CCSystem.getAPI().getI18nComponent(player, "chanpon.environment.$key", placeholders)
    }

    internal fun route(worldUuid: UUID): MenuRoute = MenuRoute(
        RUNTIME_OWNER,
        ROUTE_ENVIRONMENT,
        mapOf(PAYLOAD_WORLD_UUID to worldUuid.toString()),
    )

    private fun world(route: MenuRoute): WorldData? {
        val worldUuid = route.payload[PAYLOAD_WORLD_UUID]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        return MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
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
        private const val ACTION_FREECAM = "freecam"
        private const val ACTION_PROTECTION = "protection"
        private const val RUNTIME_OWNER = "mwm-chanpon"
        private const val ROUTE_ENVIRONMENT = "environment"
        private const val ACTION_SETTING = "change-setting"
        private const val ACTION_BACK = "back"
        private const val PAYLOAD_WORLD_UUID = "world_uuid"
        private const val PAYLOAD_SETTING = "setting"
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
