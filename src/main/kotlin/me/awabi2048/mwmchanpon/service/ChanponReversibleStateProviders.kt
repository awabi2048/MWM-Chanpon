package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderState
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProvider
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderDefinition
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProviderRegistry
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateRestoreContext
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.mwmchanpon.protection.WorldBuildPermissionService
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.storage.WorldCreationAvailability
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import java.util.UUID

/** Chanponの実状態を扱う可逆provider群です。各providerは一つのaggregateだけを復元します。 */
internal object ChanponReversibleStateProviders {
    private const val OWNER = "mwm-chanpon"

    fun registerAutomation(
        registry: MenuReversibleStateProviderRegistry,
        plugin: MWMChanponPlugin,
        onRestored: () -> Unit,
    ) = registry.register(
        MenuReversibleStateProviderDefinition(
            OWNER,
            "automation-settings",
            AutomationSettingsProvider(PluginAutomationSettingsGateway(plugin, onRestored)),
        ),
    )

    fun registerAdminWorldControls(
        registry: MenuReversibleStateProviderRegistry,
        storage: ChanponGlobalStorage,
        spawnProtection: SpawnWorldProtectionService,
    ) = registry.register(
        MenuReversibleStateProviderDefinition(
            OWNER,
            "admin-world-controls",
            AdminWorldControlsProvider(storage, spawnProtection, MyWorldManagerApi::syncOnlineWorldPlayerStates),
        ),
    )

    fun registerEnvironment(
        registry: MenuReversibleStateProviderRegistry,
        storage: ChanponWorldStorage,
        buildPermissionService: WorldBuildPermissionService,
    ) = registry.register(
        MenuReversibleStateProviderDefinition(
            OWNER,
            "world-environment",
            EnvironmentProvider(storage) { worldData ->
                MyWorldManagerApi.getWorldEnvironmentService()?.applyWeather(worldData.uuid)
                MyWorldManagerApi.getWorldEnvironmentService()?.applyTime(worldData.uuid)
                MyWorldManagerApi.getWorldEnvironmentService()?.applyAttributes(worldData.uuid)
                MyWorldManagerApi.getWorldEnvironmentService()?.applyFlight(worldData.uuid)
                buildPermissionService.sync(worldData)
            },
        ),
    )

    fun registerPublication(
        registry: MenuReversibleStateProviderRegistry,
        storage: ChanponWorldStorage,
    ) = registry.register(
        MenuReversibleStateProviderDefinition(OWNER, "world-publication", PublicationProvider(storage)),
    )

    fun registerNotification(registry: MenuReversibleStateProviderRegistry) = registry.register(
        MenuReversibleStateProviderDefinition(OWNER, "world-notification", NotificationProvider),
    )

    fun registerSubmission(
        registry: MenuReversibleStateProviderRegistry,
        plugin: MWMChanponPlugin,
        storage: ChanponWorldStorage,
        protectionService: SubmittedWorldProtectionService,
        toolPermissionController: ToolPermissionController,
    ) = registry.register(
        MenuReversibleStateProviderDefinition(
            OWNER,
            "submission",
            SubmissionProvider(
                PluginSubmissionGateway(plugin, protectionService, toolPermissionController),
                storage,
            ),
        ),
    )

    fun registerToolPermission(
        registry: MenuReversibleStateProviderRegistry,
        storage: ChanponWorldStorage,
        controller: ToolPermissionController,
    ) = registry.register(
        MenuReversibleStateProviderDefinition(OWNER, "tool-permission", ToolPermissionProvider(storage, ControllerToolPermissionGateway(controller))),
    )
}

private data class AutomationSettingsState(
    val configPath: String,
    val before: Boolean,
    val expectedAfter: Boolean,
) : MenuReversibleProviderState

internal interface AutomationSettingsGateway {
    fun getBoolean(path: String): Boolean
    fun restoreBoolean(path: String, value: Boolean)
}

private class PluginAutomationSettingsGateway(
    private val plugin: MWMChanponPlugin,
    private val onRestored: () -> Unit,
) : AutomationSettingsGateway {
    override fun getBoolean(path: String): Boolean = plugin.config.getBoolean(path, false)

    override fun restoreBoolean(path: String, value: Boolean) {
        plugin.config.set(path, value)
        plugin.saveConfig()
        onRestored()
    }
}

internal class AutomationSettingsProvider(
    private val gateway: AutomationSettingsGateway,
) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val path = context.interaction.contract.arguments[ChanponReversibleContracts.TARGET_ID]
            ?: return rejectCapture("automation config path is missing")
        if (path !in AUTOMATION_PATHS) return rejectCapture("unsupported automation config path: $path")
        val before = gateway.getBoolean(path)
        return MenuReversibleProviderCaptureResult.Captured(AutomationSettingsState(path, before, !before))
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = context.state as? AutomationSettingsState
            ?: return rejectRestore("invalid automation settings state type")
        val current = gateway.getBoolean(state.configPath)
        if (current != state.expectedAfter) return concurrent("automation setting ${state.configPath}", state.expectedAfter, current)
        gateway.restoreBoolean(state.configPath, state.before)
        return MenuReversibleProviderRestoreResult.Restored
    }

    private companion object {
        val AUTOMATION_PATHS = setOf("backup.auto_backup.enabled", "backup.auto_rollback.enabled")
    }
}

private data class AdminWorldControlsState(
    val before: ChanponGlobalStorage.AdminWorldControlsSnapshot,
    val expectedAfter: ChanponGlobalStorage.AdminWorldControlsSnapshot,
    val spawnDefaultsBefore: SpawnWorldProtectionService.DefaultsSnapshot? = null,
    val spawnDefaultsExpectedAfter: SpawnWorldProtectionService.DefaultsSnapshot? = null,
) : MenuReversibleProviderState

internal class AdminWorldControlsProvider(
    private val storage: ChanponGlobalStorage,
    private val spawnProtection: SpawnWorldProtectionService,
    private val onRestored: () -> Unit,
) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val operation = context.interaction.contract.arguments[ChanponReversibleContracts.OPERATION]
            ?: return rejectCapture("admin world operation is missing")
        val before = storage.captureAdminWorldControls()
        var defaultsBefore: SpawnWorldProtectionService.DefaultsSnapshot? = null
        var defaultsExpectedAfter: SpawnWorldProtectionService.DefaultsSnapshot? = null
        val expectedAfter = when (operation) {
            "building" -> before.copy(buildingAllowed = !before.buildingAllowed)
            "creation" -> before.copy(creationAvailability = nextCreationAvailability(before.creationAvailability))
            "spawn" -> {
                val target = context.interaction.contract.arguments[ChanponReversibleContracts.TARGET_ID]
                    ?.let(::parseUuid)
                    ?: return rejectCapture("spawn target id is invalid")
                val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(target)
                    ?: return rejectCapture("spawn target world no longer exists: $target")
                defaultsBefore = spawnProtection.captureDefaults(worldData)
                    ?: return rejectCapture("spawn target protection state is unavailable: $target")
                val appliesDefaults = before.spawnPolicyInitializedUuid != target
                defaultsExpectedAfter = if (appliesDefaults) {
                    spawnProtection.defaultsApplied(requireNotNull(defaultsBefore))
                } else {
                    defaultsBefore
                }
                before.copy(
                    spawnWorldUuid = target,
                    spawnPolicyInitializedUuid = if (appliesDefaults) target else before.spawnPolicyInitializedUuid,
                )
            }
            else -> return rejectCapture("unsupported admin world operation: $operation")
        }
        return MenuReversibleProviderCaptureResult.Captured(
            AdminWorldControlsState(before, expectedAfter, defaultsBefore, defaultsExpectedAfter),
        )
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = context.state as? AdminWorldControlsState
            ?: return rejectRestore("invalid admin world controls state type")
        val current = storage.captureAdminWorldControls()
        if (current != state.expectedAfter) return concurrent("admin world controls", state.expectedAfter, current)
        if (state.spawnDefaultsExpectedAfter != null) {
            val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(state.spawnDefaultsExpectedAfter.worldUuid)
                ?: return rejectRestore("spawn target world no longer exists: ${state.spawnDefaultsExpectedAfter.worldUuid}")
            val currentDefaults = spawnProtection.captureDefaults(worldData)
                ?: return rejectRestore("spawn target protection state is unavailable: ${state.spawnDefaultsExpectedAfter.worldUuid}")
            if (currentDefaults != state.spawnDefaultsExpectedAfter) {
                return concurrent("spawn protection ${worldData.uuid}", state.spawnDefaultsExpectedAfter, currentDefaults)
            }
            if (!spawnProtection.restoreDefaults(worldData, requireNotNull(state.spawnDefaultsBefore))) {
                return rejectRestore("failed to restore spawn protection: ${worldData.uuid}")
            }
        }
        storage.restoreAdminWorldControls(state.before)
        onRestored()
        return MenuReversibleProviderRestoreResult.Restored
    }

    private fun nextCreationAvailability(current: WorldCreationAvailability): WorldCreationAvailability {
        val values = WorldCreationAvailability.entries
        return values[(values.indexOf(current) + 1) % values.size]
    }
}

private data class EnvironmentSnapshot(
    val fixedWeather: String?,
    val fixedTime: Long?,
    val gravity: Double?,
    val scale: Double?,
    val allowFlight: Boolean,
    val freecamAllowed: Boolean,
    val protectionMode: ChanponWorldProtectionMode,
)

private data class EnvironmentState(
    val worldUuid: UUID,
    val before: EnvironmentSnapshot,
    val expectedAfter: EnvironmentSnapshot,
) : MenuReversibleProviderState

internal class EnvironmentProvider(
    private val storage: ChanponWorldStorage,
    private val onApplied: (WorldData) -> Unit,
) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val worldUuid = context.interaction.contract.arguments[ChanponReversibleContracts.TARGET_ID]
            ?.let(::parseUuid)
            ?: return rejectCapture("environment target id is invalid")
        val worldData = world(worldUuid) ?: return rejectCapture("environment world no longer exists: $worldUuid")
        val setting = context.interaction.arguments[SETTING]
            ?: return rejectCapture("environment setting is missing")
        val before = snapshot(worldData)
        val expectedAfter = nextSnapshot(before, setting, context) ?: return rejectCapture(
            "unsupported environment interaction: setting=$setting click=${context.interaction.click}",
        )
        return MenuReversibleProviderCaptureResult.Captured(EnvironmentState(worldUuid, before, expectedAfter))
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = context.state as? EnvironmentState
            ?: return rejectRestore("invalid environment state type")
        val worldData = world(state.worldUuid) ?: return rejectRestore("environment world no longer exists: ${state.worldUuid}")
        val current = snapshot(worldData)
        if (current != state.expectedAfter) return concurrent("environment ${state.worldUuid}", state.expectedAfter, current)
        apply(worldData, state.before)
        return MenuReversibleProviderRestoreResult.Restored
    }

    private fun snapshot(worldData: WorldData) = EnvironmentSnapshot(
        fixedWeather = worldData.fixedWeather,
        fixedTime = worldData.fixedTime,
        gravity = worldData.gravityValue,
        scale = worldData.fixedScale,
        allowFlight = worldData.allowFlight,
        freecamAllowed = storage.isFreecamAllowed(worldData.uuid),
        protectionMode = storage.getProtectionMode(worldData.uuid),
    )

    private fun nextSnapshot(
        before: EnvironmentSnapshot,
        setting: String,
        context: MenuReversibleStateCaptureContext,
    ): EnvironmentSnapshot? {
        val next = direction(context) ?: return null
        return when (setting) {
            "weather" -> before.copy(fixedWeather = cycleNullable(before.fixedWeather, WEATHER_VALUES, next))
            "time" -> before.copy(fixedTime = cycleNullable(before.fixedTime.takeIf { it in TIME_VALUES }, TIME_VALUES, next))
            "gravity" -> before.copy(gravity = cycle(before.gravity ?: DEFAULT_GRAVITY, GRAVITY_VALUES, next))
            "scale" -> before.copy(scale = cycle(before.scale ?: DEFAULT_SCALE, SCALE_VALUES, next))
            "flight" -> before.copy(allowFlight = !before.allowFlight)
            "freecam" -> before.copy(freecamAllowed = !before.freecamAllowed)
            "protection" -> before.copy(protectionMode = cycle(before.protectionMode, PROTECTION_VALUES, next))
            else -> null
        }
    }

    private fun apply(worldData: WorldData, state: EnvironmentSnapshot) {
        worldData.fixedWeather = state.fixedWeather
        worldData.fixedTime = state.fixedTime
        worldData.gravityValue = state.gravity
        worldData.fixedScale = state.scale
        worldData.allowFlight = state.allowFlight
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        storage.setFreecamAllowed(worldData.uuid, state.freecamAllowed)
        storage.setProtectionMode(worldData.uuid, state.protectionMode)
        onApplied(worldData)
    }

    private fun direction(context: MenuReversibleStateCaptureContext): Int? = when {
        context.interaction.click.isLeftClick -> 1
        context.interaction.click.isRightClick -> -1
        else -> null
    }

    private fun <T> cycle(current: T, values: List<T>, direction: Int): T {
        val index = values.indexOf(current).takeIf { it >= 0 } ?: 0
        return values[Math.floorMod(index + direction, values.size)]
    }

    private fun <T> cycleNullable(current: T?, values: List<T?>, direction: Int): T? {
        val index = values.indexOf(current).takeIf { it >= 0 } ?: 0
        return values[Math.floorMod(index + direction, values.size)]
    }

    private fun world(worldUuid: UUID): WorldData? = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)

    private companion object {
        const val SETTING = "setting"
        const val DEFAULT_GRAVITY = 0.08
        const val DEFAULT_SCALE = 1.0
        val WEATHER_VALUES = listOf<String?>(null, "CLEAR", "RAIN", "THUNDER")
        val TIME_VALUES = listOf<Long?>(null, 0L, 6000L, 12500L, 18000L)
        val GRAVITY_VALUES = listOf(0.08, 0.02, 0.01)
        val SCALE_VALUES = listOf(1.0, 0.5, 2.0)
        val PROTECTION_VALUES = listOf(
            ChanponWorldProtectionMode.OWNER_ONLY,
            ChanponWorldProtectionMode.MEMBERS,
            ChanponWorldProtectionMode.PASSWORD,
            ChanponWorldProtectionMode.PUBLIC,
        )
    }
}

private data class PublicationState(
    val worldUuid: UUID,
    val before: ChanponWorldVisibility,
    val expectedAfter: ChanponWorldVisibility,
) : MenuReversibleProviderState

internal class PublicationProvider(private val storage: ChanponWorldStorage) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val worldUuid = argumentTarget(context) ?: return rejectCapture("publication world target is invalid")
        val before = storage.getVisibility(worldUuid)
        val values = listOf(ChanponWorldVisibility.PUBLIC, ChanponWorldVisibility.DEVELOPMENT, ChanponWorldVisibility.PRIVATE)
        val expectedAfter = values[(values.indexOf(before) + 1) % values.size]
        return MenuReversibleProviderCaptureResult.Captured(PublicationState(worldUuid, before, expectedAfter))
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = context.state as? PublicationState ?: return rejectRestore("invalid publication state type")
        val current = storage.getVisibility(state.worldUuid)
        if (current != state.expectedAfter) return concurrent("publication ${state.worldUuid}", state.expectedAfter, current)
        storage.setVisibility(state.worldUuid, state.before)
        return MenuReversibleProviderRestoreResult.Restored
    }
}

private data class NotificationState(val worldUuid: UUID, val before: Boolean, val expectedAfter: Boolean) : MenuReversibleProviderState

internal object NotificationProvider : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val worldUuid = argumentTarget(context) ?: return rejectCapture("notification world target is invalid")
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: return rejectCapture("notification world no longer exists: $worldUuid")
        return MenuReversibleProviderCaptureResult.Captured(
            NotificationState(worldUuid, worldData.notificationEnabled, !worldData.notificationEnabled),
        )
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = context.state as? NotificationState ?: return rejectRestore("invalid notification state type")
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(state.worldUuid)
            ?: return rejectRestore("notification world no longer exists: ${state.worldUuid}")
        if (worldData.notificationEnabled != state.expectedAfter) {
            return concurrent("notification ${state.worldUuid}", state.expectedAfter, worldData.notificationEnabled)
        }
        worldData.notificationEnabled = state.before
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        return MenuReversibleProviderRestoreResult.Restored
    }
}

private sealed interface SubmissionProviderState : MenuReversibleProviderState {
    data class Closure(val before: Boolean, val expectedAfter: Boolean) : SubmissionProviderState
    data class World(
        val worldUuid: UUID,
        val before: ChanponWorldStorage.SubmissionSnapshot,
        val expectedState: SubmissionState,
        val expectedRevision: Int,
    ) : SubmissionProviderState
}

internal interface SubmissionGateway {
    fun isClosed(): Boolean
    fun restoreClosed(value: Boolean)
    fun syncWorld(worldData: WorldData)
}

private class PluginSubmissionGateway(
    private val plugin: MWMChanponPlugin,
    private val protectionService: SubmittedWorldProtectionService,
    private val toolPermissionController: ToolPermissionController,
) : SubmissionGateway {
    override fun isClosed(): Boolean = plugin.isSubmissionClosed()

    override fun restoreClosed(value: Boolean) {
        plugin.config.set("submission.closed", value)
        plugin.saveConfig()
    }

    override fun syncWorld(worldData: WorldData) {
        protectionService.sync(worldData)
        toolPermissionController.syncWorld(worldData)
    }
}

internal class SubmissionProvider(
    private val gateway: SubmissionGateway,
    private val storage: ChanponWorldStorage,
) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val operation = context.interaction.contract.arguments[ChanponReversibleContracts.OPERATION]
            ?: return rejectCapture("submission operation is missing")
        val target = context.interaction.contract.arguments[ChanponReversibleContracts.TARGET_ID]
            ?: return rejectCapture("submission target id is missing")
        if (target == "global") {
            val before = gateway.isClosed()
            val expectedAfter = when (operation) {
                "close" -> true
                "reopen" -> false
                else -> return rejectCapture("unsupported global submission operation: $operation")
            }
            return MenuReversibleProviderCaptureResult.Captured(SubmissionProviderState.Closure(before, expectedAfter))
        }
        val worldUuid = parseUuid(target) ?: return rejectCapture("submission target id is invalid")
        val before = try {
            storage.captureSubmissionSnapshot(worldUuid)
        } catch (exception: IllegalStateException) {
            return rejectCapture(exception.message ?: "submission state is unavailable")
        }
        val expectedState = when (operation) {
            "complete" -> SubmissionState.COMPLETED
            "uncomplete" -> SubmissionState.SUBMITTED
            else -> return rejectCapture("unsupported world submission operation: $operation")
        }
        return MenuReversibleProviderCaptureResult.Captured(
            SubmissionProviderState.World(worldUuid, before, expectedState, before.revision + 1),
        )
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult = when (val state = context.state) {
        is SubmissionProviderState.Closure -> restoreClosure(state)
        is SubmissionProviderState.World -> restoreWorld(state)
        else -> rejectRestore("invalid submission state type")
    }

    private fun restoreClosure(state: SubmissionProviderState.Closure): MenuReversibleProviderRestoreResult {
        val current = gateway.isClosed()
        if (current != state.expectedAfter) return concurrent("submission closure", state.expectedAfter, current)
        gateway.restoreClosed(state.before)
        return MenuReversibleProviderRestoreResult.Restored
    }

    private fun restoreWorld(state: SubmissionProviderState.World): MenuReversibleProviderRestoreResult {
        val current = try {
            storage.captureSubmissionSnapshot(state.worldUuid)
        } catch (exception: IllegalStateException) {
            return rejectRestore(exception.message ?: "submission state is unavailable")
        }
        if (current.state != state.expectedState || current.revision != state.expectedRevision) {
            return concurrent(
                "submission ${state.worldUuid}",
                "${state.expectedState}/${state.expectedRevision}",
                "${current.state}/${current.revision}",
            )
        }
        return try {
            storage.restoreSubmissionSnapshot(state.worldUuid, state.before)
            MyWorldManagerApi.getWorldRepository()?.findByUuid(state.worldUuid)?.let(gateway::syncWorld)
            MenuReversibleProviderRestoreResult.Restored
        } catch (exception: RuntimeException) {
            rejectRestore("failed to restore submission ${state.worldUuid}: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }
}

private sealed interface ToolPermissionState : MenuReversibleProviderState {
    data class Toggle(
        val worldUuid: UUID,
        val targetPlayerUuid: UUID,
        val toolId: String,
        val before: Boolean?,
        val expectedAfter: Boolean,
    ) : ToolPermissionState

    data class Role(
        val worldUuid: UUID,
        val targetPlayerUuid: UUID,
        val members: List<UUID>,
        val moderators: List<UUID>,
        val expectedMembers: List<UUID>,
        val expectedModerators: List<UUID>,
    ) : ToolPermissionState
}

internal interface ToolPermissionGateway {
    fun isEnabled(worldData: WorldData, playerUuid: UUID, toolId: String): Boolean
    fun storedEnabledAfterSet(worldData: WorldData, enabled: Boolean): Boolean
    fun syncWorld(worldData: WorldData)
}

private class ControllerToolPermissionGateway(
    private val controller: ToolPermissionController,
) : ToolPermissionGateway {
    override fun isEnabled(worldData: WorldData, playerUuid: UUID, toolId: String): Boolean =
        controller.isEnabled(worldData, playerUuid, toolId)

    override fun storedEnabledAfterSet(worldData: WorldData, enabled: Boolean): Boolean =
        controller.storedEnabledAfterSet(worldData, enabled)

    override fun syncWorld(worldData: WorldData) = controller.syncWorld(worldData)
}

internal class ToolPermissionProvider(
    private val storage: ChanponWorldStorage,
    private val gateway: ToolPermissionGateway,
) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val (worldUuid, targetPlayerUuid) = toolTarget(context)
            ?: return rejectCapture("tool permission target id is invalid")
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
            ?: return rejectCapture("tool permission world no longer exists: $worldUuid")
        return when (context.interaction.contract.arguments[ChanponReversibleContracts.OPERATION]) {
            "toggle" -> {
                val toolId = context.interaction.arguments[TOOL_ID]
                    ?: return rejectCapture("tool permission id is missing")
                val before = storage.getToolEnabledState(worldUuid, targetPlayerUuid, toolId)
                val requested = !gateway.isEnabled(worldData, targetPlayerUuid, toolId)
                MenuReversibleProviderCaptureResult.Captured(
                    ToolPermissionState.Toggle(
                        worldUuid,
                        targetPlayerUuid,
                        toolId,
                        before,
                        gateway.storedEnabledAfterSet(worldData, requested),
                    ),
                )
            }
            "role" -> {
                val members = worldData.members.toList()
                val moderators = worldData.moderators.toList()
                val expectedMembers = members.toMutableList()
                val expectedModerators = moderators.toMutableList()
                if (expectedModerators.remove(targetPlayerUuid)) {
                    if (targetPlayerUuid !in expectedMembers) expectedMembers += targetPlayerUuid
                } else {
                    expectedMembers.remove(targetPlayerUuid)
                    if (targetPlayerUuid !in expectedModerators) expectedModerators += targetPlayerUuid
                }
                MenuReversibleProviderCaptureResult.Captured(
                    ToolPermissionState.Role(
                        worldUuid,
                        targetPlayerUuid,
                        members,
                        moderators,
                        expectedMembers,
                        expectedModerators,
                    ),
                )
            }
            else -> rejectCapture("unsupported tool permission operation")
        }
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult = when (val state = context.state) {
        is ToolPermissionState.Toggle -> restoreToggle(state)
        is ToolPermissionState.Role -> restoreRole(state)
        else -> rejectRestore("invalid tool permission state type")
    }

    private fun restoreToggle(state: ToolPermissionState.Toggle): MenuReversibleProviderRestoreResult {
        val current = storage.getToolEnabledState(state.worldUuid, state.targetPlayerUuid, state.toolId)
        if (current != state.expectedAfter) return concurrent("tool permission ${state.worldUuid}/${state.toolId}", state.expectedAfter, current)
        return try {
            storage.restoreToolEnabledState(state.worldUuid, state.targetPlayerUuid, state.toolId, state.before)
            MyWorldManagerApi.getWorldRepository()?.findByUuid(state.worldUuid)?.let(gateway::syncWorld)
            MenuReversibleProviderRestoreResult.Restored
        } catch (exception: RuntimeException) {
            rejectRestore("failed to restore tool permission: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun restoreRole(state: ToolPermissionState.Role): MenuReversibleProviderRestoreResult {
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(state.worldUuid)
            ?: return rejectRestore("tool permission world no longer exists: ${state.worldUuid}")
        if (worldData.members != state.expectedMembers || worldData.moderators != state.expectedModerators) {
            return concurrent("tool role ${state.worldUuid}/${state.targetPlayerUuid}", "expected member role", "changed")
        }
        worldData.members.clear()
        worldData.members.addAll(state.members)
        worldData.moderators.clear()
        worldData.moderators.addAll(state.moderators)
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
        gateway.syncWorld(worldData)
        return MenuReversibleProviderRestoreResult.Restored
    }
}

private fun argumentTarget(context: MenuReversibleStateCaptureContext): UUID? {
    val target = context.interaction.contract.arguments[ChanponReversibleContracts.TARGET_ID] ?: return null
    val argument = target.removePrefix("argument:").takeIf { target.startsWith("argument:") } ?: return parseUuid(target)
    return context.interaction.arguments[argument]?.let(::parseUuid)
}

private fun toolTarget(context: MenuReversibleStateCaptureContext): Pair<UUID, UUID>? {
    val target = context.interaction.contract.arguments[ChanponReversibleContracts.TARGET_ID] ?: return null
    val values = target.split('/', limit = 2)
    if (values.size != 2) return null
    val worldUuid = parseUuid(values[0]) ?: return null
    val playerUuid = parseUuid(values[1]) ?: return null
    return worldUuid to playerUuid
}

private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

private fun rejectCapture(reason: String) = MenuReversibleProviderCaptureResult.Rejected(reason)
private fun rejectRestore(reason: String) = MenuReversibleProviderRestoreResult.Rejected(reason)
private fun concurrent(aggregate: String, expected: Any?, current: Any?) =
    MenuReversibleProviderRestoreResult.Rejected("$aggregate changed concurrently: expected=$expected, actual=$current")

private const val TOOL_ID = "tool_id"
