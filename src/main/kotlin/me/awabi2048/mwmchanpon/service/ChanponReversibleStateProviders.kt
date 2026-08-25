package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleOpaqueState
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
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import java.util.UUID
import java.util.Base64

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
) : MenuReversibleOpaqueState {
    override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
        AUTOMATION_PROVIDER_ID,
        TYPE,
        mapOf("configPath" to configPath, "before" to before, "expectedAfter" to expectedAfter),
    ).snapshot()

    companion object {
        private const val TYPE = "automation-settings"
        fun decode(value: Any?): AutomationSettingsState? {
            val payload = decodeChanponOpaqueState(
                value, AUTOMATION_PROVIDER_ID, TYPE, setOf("configPath", "before", "expectedAfter"),
            )?.payload ?: return null
            return AutomationSettingsState(
                payload.strictString("configPath") ?: return null,
                payload.strictBoolean("before") ?: return null,
                payload.strictBoolean("expectedAfter") ?: return null,
            )
        }
    }
}

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
        check(plugin.isConfigurationMigrationCurrent()) { "configuration migration is required before restoring automation settings" }
        plugin.config.set(path, value)
        plugin.saveConfigIfCurrent()
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
        val state = AutomationSettingsState.decode(context.stateSnapshot.value)
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
) : MenuReversibleOpaqueState {
    override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
        ADMIN_PROVIDER_ID,
        TYPE,
        mapOf(
            "before" to before.toOpaqueMap(),
            "expectedAfter" to expectedAfter.toOpaqueMap(),
            "spawnDefaultsBefore" to spawnDefaultsBefore?.toOpaqueMap(),
            "spawnDefaultsExpectedAfter" to spawnDefaultsExpectedAfter?.toOpaqueMap(),
        ),
    ).snapshot()

    companion object {
        private const val TYPE = "admin-world-controls"
        fun decode(value: Any?): AdminWorldControlsState? {
            val payload = decodeChanponOpaqueState(
                value,
                ADMIN_PROVIDER_ID,
                TYPE,
                setOf("before", "expectedAfter", "spawnDefaultsBefore", "spawnDefaultsExpectedAfter"),
            )?.payload ?: return null
            return AdminWorldControlsState(
                decodeAdminWorldControls(payload["before"]) ?: return null,
                decodeAdminWorldControls(payload["expectedAfter"]) ?: return null,
                (decodeNullableSpawnDefaults(payload["spawnDefaultsBefore"]) ?: return null).value,
                (decodeNullableSpawnDefaults(payload["spawnDefaultsExpectedAfter"]) ?: return null).value,
            )
        }
    }
}

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
        val state = AdminWorldControlsState.decode(context.stateSnapshot.value)
            ?: return rejectRestore("invalid admin world controls state type")
        if (!storage.canWriteSettings()) {
            return rejectRestore("configuration migration is required before restoring admin world controls")
        }
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
    val worldFileBefore: ChanponWorldStorage.WorldFileSnapshot,
) : MenuReversibleOpaqueState {
    override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
        ENVIRONMENT_PROVIDER_ID,
        TYPE,
        mapOf(
            "worldUuid" to worldUuid,
            "before" to before.toOpaqueMap(),
            "expectedAfter" to expectedAfter.toOpaqueMap(),
            "worldFileBefore" to worldFileBefore.toOpaqueMap(),
        ),
    ).snapshot()

    companion object {
        private const val TYPE = "world-environment"
        fun decode(value: Any?): EnvironmentState? {
            val payload = decodeChanponOpaqueState(
                value, ENVIRONMENT_PROVIDER_ID, TYPE, setOf("worldUuid", "before", "expectedAfter", "worldFileBefore"),
            )?.payload ?: return null
            return EnvironmentState(
                payload.strictUuid("worldUuid") ?: return null,
                decodeEnvironmentSnapshot(payload["before"]) ?: return null,
                decodeEnvironmentSnapshot(payload["expectedAfter"]) ?: return null,
                decodeWorldFileSnapshot(payload["worldFileBefore"]) ?: return null,
            )
        }
    }
}

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
        return MenuReversibleProviderCaptureResult.Captured(
            EnvironmentState(worldUuid, before, expectedAfter, storage.captureWorldFileSnapshot(worldUuid)),
        )
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = EnvironmentState.decode(context.stateSnapshot.value)
            ?: return rejectRestore("invalid environment state type")
        val worldData = world(state.worldUuid) ?: return rejectRestore("environment world no longer exists: ${state.worldUuid}")
        val restored = storage.restoreWorldFileSnapshot(state.worldUuid, state.worldFileBefore) {
            snapshot(worldData) == state.expectedAfter
        }
        if (restored == ChanponWorldStorage.WorldFileRestoreResult.CONCURRENT_CHANGE) {
            return concurrent("environment ${state.worldUuid}", state.expectedAfter, snapshot(worldData))
        }
        applyRuntimeState(worldData, state.before)
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

    private fun applyRuntimeState(worldData: WorldData, state: EnvironmentSnapshot) {
        worldData.fixedWeather = state.fixedWeather
        worldData.fixedTime = state.fixedTime
        worldData.gravityValue = state.gravity
        worldData.fixedScale = state.scale
        worldData.allowFlight = state.allowFlight
        MyWorldManagerApi.getWorldRepository()?.save(worldData)
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
            ChanponWorldProtectionMode.PUBLIC,
        )
    }
}

private data class PublicationState(
    val worldUuid: UUID,
    val before: ChanponWorldVisibility,
    val expectedAfter: ChanponWorldVisibility,
    val worldFileBefore: ChanponWorldStorage.WorldFileSnapshot,
) : MenuReversibleOpaqueState {
    override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
        PUBLICATION_PROVIDER_ID,
        TYPE,
        mapOf(
            "worldUuid" to worldUuid,
            "before" to before.name,
            "expectedAfter" to expectedAfter.name,
            "worldFileBefore" to worldFileBefore.toOpaqueMap(),
        ),
    ).snapshot()

    companion object {
        private const val TYPE = "world-publication"
        fun decode(value: Any?): PublicationState? {
            val payload = decodeChanponOpaqueState(
                value, PUBLICATION_PROVIDER_ID, TYPE, setOf("worldUuid", "before", "expectedAfter", "worldFileBefore"),
            )?.payload ?: return null
            return PublicationState(
                payload.strictUuid("worldUuid") ?: return null,
                payload.strictString("before")?.let(::visibilityOrNull) ?: return null,
                payload.strictString("expectedAfter")?.let(::visibilityOrNull) ?: return null,
                decodeWorldFileSnapshot(payload["worldFileBefore"]) ?: return null,
            )
        }
    }
}

internal class PublicationProvider(private val storage: ChanponWorldStorage) : MenuReversibleStateProvider {
    override fun capture(context: MenuReversibleStateCaptureContext): MenuReversibleProviderCaptureResult {
        val worldUuid = argumentTarget(context) ?: return rejectCapture("publication world target is invalid")
        val before = storage.getVisibility(worldUuid)
        val values = listOf(ChanponWorldVisibility.PUBLIC, ChanponWorldVisibility.DEVELOPMENT, ChanponWorldVisibility.PRIVATE)
        val expectedAfter = values[(values.indexOf(before) + 1) % values.size]
        return MenuReversibleProviderCaptureResult.Captured(
            PublicationState(worldUuid, before, expectedAfter, storage.captureWorldFileSnapshot(worldUuid)),
        )
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        val state = PublicationState.decode(context.stateSnapshot.value) ?: return rejectRestore("invalid publication state type")
        if (MyWorldManagerApi.getWorldRepository()?.findByUuid(state.worldUuid) == null) {
            return rejectRestore("TARGET_MISSING: publication world no longer exists: ${state.worldUuid}")
        }
        val restored = storage.restoreWorldFileSnapshot(state.worldUuid, state.worldFileBefore) {
            storage.getVisibility(state.worldUuid) == state.expectedAfter
        }
        if (restored == ChanponWorldStorage.WorldFileRestoreResult.CONCURRENT_CHANGE) {
            return concurrent("publication ${state.worldUuid}", state.expectedAfter, storage.getVisibility(state.worldUuid))
        }
        storage.syncVisibility(state.worldUuid)
        return MenuReversibleProviderRestoreResult.Restored
    }
}

private data class NotificationState(
    val worldUuid: UUID,
    val before: Boolean,
    val expectedAfter: Boolean,
) : MenuReversibleOpaqueState {
    override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
        NOTIFICATION_PROVIDER_ID,
        TYPE,
        mapOf("worldUuid" to worldUuid, "before" to before, "expectedAfter" to expectedAfter),
    ).snapshot()

    companion object {
        private const val TYPE = "world-notification"
        fun decode(value: Any?): NotificationState? {
            val payload = decodeChanponOpaqueState(
                value, NOTIFICATION_PROVIDER_ID, TYPE, setOf("worldUuid", "before", "expectedAfter"),
            )?.payload ?: return null
            return NotificationState(
                payload.strictUuid("worldUuid") ?: return null,
                payload.strictBoolean("before") ?: return null,
                payload.strictBoolean("expectedAfter") ?: return null,
            )
        }
    }
}

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
        val state = NotificationState.decode(context.stateSnapshot.value) ?: return rejectRestore("invalid notification state type")
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

private sealed interface SubmissionProviderState : MenuReversibleOpaqueState {
    data class Closure(val before: Boolean, val expectedAfter: Boolean) : SubmissionProviderState {
        override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
            SUBMISSION_PROVIDER_ID,
            TYPE,
            mapOf("before" to before, "expectedAfter" to expectedAfter),
        ).snapshot()

        companion object {
            private const val TYPE = "submission-closure"
            fun decode(value: Any?): Closure? {
                val payload = decodeChanponOpaqueState(
                    value, SUBMISSION_PROVIDER_ID, TYPE, setOf("before", "expectedAfter"),
                )?.payload ?: return null
                return Closure(
                    payload.strictBoolean("before") ?: return null,
                    payload.strictBoolean("expectedAfter") ?: return null,
                )
            }
        }
    }

    data class World(
        val worldUuid: UUID,
        val before: ChanponWorldStorage.SubmissionSnapshot,
        val expectedState: SubmissionState,
        val expectedRevision: Int,
        val expectedAction: String,
        val expectedActorUuid: UUID,
        val expectedActorName: String,
    ) : SubmissionProviderState {
        override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
            SUBMISSION_PROVIDER_ID,
            TYPE,
            mapOf(
                "worldUuid" to worldUuid,
                "before" to before.toOpaqueMap(),
                "expectedState" to expectedState.name,
                "expectedRevision" to expectedRevision,
                "expectedAction" to expectedAction,
                "expectedActorUuid" to expectedActorUuid,
                "expectedActorName" to expectedActorName,
            ),
        ).snapshot()

        companion object {
            private const val TYPE = "submission-world"
            fun decode(value: Any?): World? {
                val payload = decodeChanponOpaqueState(
                    value,
                    SUBMISSION_PROVIDER_ID,
                    TYPE,
                    setOf(
                        "worldUuid", "before", "expectedState", "expectedRevision",
                        "expectedAction", "expectedActorUuid", "expectedActorName",
                    ),
                )?.payload ?: return null
                return World(
                    payload.strictUuid("worldUuid") ?: return null,
                    decodeSubmissionSnapshot(payload["before"]) ?: return null,
                    payload.strictString("expectedState")?.let(::submissionStateOrNull) ?: return null,
                    payload.strictInt("expectedRevision") ?: return null,
                    payload.strictString("expectedAction") ?: return null,
                    payload.strictUuid("expectedActorUuid") ?: return null,
                    payload.strictString("expectedActorName") ?: return null,
                )
            }
        }
    }
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
    fun isConfigurationMigrationCurrent(): Boolean = plugin.isConfigurationMigrationCurrent()
    override fun isClosed(): Boolean = plugin.isSubmissionClosed()

    override fun restoreClosed(value: Boolean) {
        check(plugin.isConfigurationMigrationCurrent()) { "configuration migration is required before restoring submission settings" }
        plugin.config.set("submission.closed", value)
        plugin.saveConfigIfCurrent()
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
        val expectedAction = when (operation) {
            "complete" -> "mark_completed"
            "uncomplete" -> "unmark_completed"
            else -> return rejectCapture("unsupported world submission operation: $operation")
        }
        return MenuReversibleProviderCaptureResult.Captured(
            SubmissionProviderState.World(
                worldUuid,
                before,
                expectedState,
                before.revision + 1,
                expectedAction,
                context.player.uniqueId,
                context.player.name,
            ),
        )
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        SubmissionProviderState.Closure.decode(context.stateSnapshot.value)?.let { return restoreClosure(it) }
        SubmissionProviderState.World.decode(context.stateSnapshot.value)?.let { return restoreWorld(it) }
        return rejectRestore("invalid submission state type")
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
        val expectedAfter = expectedSubmissionAfter(state, current)
        if (expectedAfter == null || current != expectedAfter) {
            return concurrent(
                "submission ${state.worldUuid}",
                "full expected transition from ${state.before}",
                current,
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

private sealed interface ToolPermissionState : MenuReversibleOpaqueState {
    data class Toggle(
        val worldUuid: UUID,
        val targetPlayerUuid: UUID,
        val toolId: String,
        val before: Boolean?,
        val expectedAfter: Boolean,
        val worldFileBefore: ChanponWorldStorage.WorldFileSnapshot,
    ) : ToolPermissionState {
        override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
            TOOL_PROVIDER_ID,
            TYPE,
            mapOf(
                "worldUuid" to worldUuid,
                "targetPlayerUuid" to targetPlayerUuid,
                "toolId" to toolId,
                "before" to before,
                "expectedAfter" to expectedAfter,
                "worldFileBefore" to worldFileBefore.toOpaqueMap(),
            ),
        ).snapshot()

        companion object {
            private const val TYPE = "tool-permission-toggle"
            fun decode(value: Any?): Toggle? {
                val payload = decodeChanponOpaqueState(
                    value,
                    TOOL_PROVIDER_ID,
                    TYPE,
                    setOf("worldUuid", "targetPlayerUuid", "toolId", "before", "expectedAfter", "worldFileBefore"),
                )?.payload ?: return null
                val before = decodeNullableBoolean(payload["before"]) ?: return null
                return Toggle(
                    payload.strictUuid("worldUuid") ?: return null,
                    payload.strictUuid("targetPlayerUuid") ?: return null,
                    payload.strictString("toolId") ?: return null,
                    before.value,
                    payload.strictBoolean("expectedAfter") ?: return null,
                    decodeWorldFileSnapshot(payload["worldFileBefore"]) ?: return null,
                )
            }
        }
    }

    data class Role(
        val worldUuid: UUID,
        val targetPlayerUuid: UUID,
        val members: List<UUID>,
        val moderators: List<UUID>,
        val expectedMembers: List<UUID>,
        val expectedModerators: List<UUID>,
        val worldFileBefore: ChanponWorldStorage.WorldFileSnapshot,
    ) : ToolPermissionState {
        override fun snapshot(): Any = ChanponReversibleOpaqueState.of(
            TOOL_PROVIDER_ID,
            TYPE,
            mapOf(
                "worldUuid" to worldUuid,
                "targetPlayerUuid" to targetPlayerUuid,
                "members" to members,
                "moderators" to moderators,
                "expectedMembers" to expectedMembers,
                "expectedModerators" to expectedModerators,
                "worldFileBefore" to worldFileBefore.toOpaqueMap(),
            ),
        ).snapshot()

        companion object {
            private const val TYPE = "tool-permission-role"
            fun decode(value: Any?): Role? {
                val payload = decodeChanponOpaqueState(
                    value,
                    TOOL_PROVIDER_ID,
                    TYPE,
                    setOf(
                        "worldUuid", "targetPlayerUuid", "members", "moderators",
                        "expectedMembers", "expectedModerators",
                        "worldFileBefore",
                    ),
                )?.payload ?: return null
                return Role(
                    payload.strictUuid("worldUuid") ?: return null,
                    payload.strictUuid("targetPlayerUuid") ?: return null,
                    payload.strictUuidList("members") ?: return null,
                    payload.strictUuidList("moderators") ?: return null,
                    payload.strictUuidList("expectedMembers") ?: return null,
                    payload.strictUuidList("expectedModerators") ?: return null,
                    decodeWorldFileSnapshot(payload["worldFileBefore"]) ?: return null,
                )
            }
        }
    }
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
                        storage.captureWorldFileSnapshot(worldUuid),
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
                        storage.captureWorldFileSnapshot(worldUuid),
                    ),
                )
            }
            else -> rejectCapture("unsupported tool permission operation")
        }
    }

    override fun restore(context: MenuReversibleStateRestoreContext): MenuReversibleProviderRestoreResult {
        ToolPermissionState.Toggle.decode(context.stateSnapshot.value)?.let { return restoreToggle(it) }
        ToolPermissionState.Role.decode(context.stateSnapshot.value)?.let { return restoreRole(it) }
        return rejectRestore("invalid tool permission state type")
    }

    private fun restoreToggle(state: ToolPermissionState.Toggle): MenuReversibleProviderRestoreResult {
        return try {
            val restored = storage.restoreWorldFileSnapshot(state.worldUuid, state.worldFileBefore) {
                storage.getToolEnabledState(state.worldUuid, state.targetPlayerUuid, state.toolId) == state.expectedAfter
            }
            if (restored == ChanponWorldStorage.WorldFileRestoreResult.CONCURRENT_CHANGE) {
                return concurrent(
                    "tool permission ${state.worldUuid}/${state.toolId}", state.expectedAfter,
                    storage.getToolEnabledState(state.worldUuid, state.targetPlayerUuid, state.toolId),
                )
            }
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
        val restored = storage.restoreWorldFileSnapshot(state.worldUuid, state.worldFileBefore) {
            storage.matchesWorldFileSnapshot(state.worldUuid, state.worldFileBefore)
        }
        if (restored == ChanponWorldStorage.WorldFileRestoreResult.CONCURRENT_CHANGE) {
            return concurrent("tool role file ${state.worldUuid}/${state.targetPlayerUuid}", state.worldFileBefore.sha256, "changed")
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

private fun ChanponGlobalStorage.AdminWorldControlsSnapshot.toOpaqueMap(): Map<String, Any?> = mapOf(
    "buildingAllowed" to buildingAllowed,
    "creationAvailability" to creationAvailability.name,
    "spawnWorldUuid" to spawnWorldUuid,
    "spawnPolicyInitializedUuid" to spawnPolicyInitializedUuid,
)

private fun decodeAdminWorldControls(value: Any?): ChanponGlobalStorage.AdminWorldControlsSnapshot? {
    val map = strictMap(
        value,
        setOf("buildingAllowed", "creationAvailability", "spawnWorldUuid", "spawnPolicyInitializedUuid"),
    ) ?: return null
    val spawn = decodeNullableUuid(map["spawnWorldUuid"]) ?: return null
    val initialized = decodeNullableUuid(map["spawnPolicyInitializedUuid"]) ?: return null
    return ChanponGlobalStorage.AdminWorldControlsSnapshot(
        map.strictBoolean("buildingAllowed") ?: return null,
        map.strictString("creationAvailability")?.let(::creationAvailabilityOrNull) ?: return null,
        spawn.value,
        initialized.value,
    )
}

private fun SpawnWorldProtectionService.DefaultsSnapshot.toOpaqueMap(): Map<String, Any?> = mapOf(
    "worldUuid" to worldUuid,
    "regionExisted" to regionExisted,
    "otherExplosion" to otherExplosion?.name,
    "chestAccess" to chestAccess?.name,
    "pistons" to pistons?.name,
    "potionSplash" to potionSplash?.name,
    "use" to use?.name,
    "passthrough" to passthrough?.name,
    "passthroughGroup" to passthroughGroup?.name,
    "creeperExplosion" to creeperExplosion?.name,
    "destroyVehicle" to destroyVehicle?.name,
    "interact" to interact?.name,
    "ride" to ride?.name,
    "placeVehicle" to placeVehicle?.name,
)

private fun decodeNullableSpawnDefaults(value: Any?): NullableDecoded<SpawnWorldProtectionService.DefaultsSnapshot>? {
    if (value == null) return NullableDecoded(null)
    val keys = setOf(
        "worldUuid", "regionExisted", "otherExplosion", "chestAccess", "pistons", "potionSplash", "use",
        "passthrough", "passthroughGroup", "creeperExplosion", "destroyVehicle", "interact", "ride", "placeVehicle",
    )
    val map = strictMap(value, keys) ?: return null
    fun flag(key: String): NullableDecoded<StateFlag.State>? = decodeNullableEnum(map[key], StateFlag.State::valueOf)
    val otherExplosion = flag("otherExplosion") ?: return null
    val chestAccess = flag("chestAccess") ?: return null
    val pistons = flag("pistons") ?: return null
    val potionSplash = flag("potionSplash") ?: return null
    val use = flag("use") ?: return null
    val passthrough = flag("passthrough") ?: return null
    val passthroughGroup = decodeNullableEnum(map["passthroughGroup"], RegionGroup::valueOf) ?: return null
    val creeperExplosion = flag("creeperExplosion") ?: return null
    val destroyVehicle = flag("destroyVehicle") ?: return null
    val interact = flag("interact") ?: return null
    val ride = flag("ride") ?: return null
    val placeVehicle = flag("placeVehicle") ?: return null
    return NullableDecoded(
        SpawnWorldProtectionService.DefaultsSnapshot(
            map.strictUuid("worldUuid") ?: return null,
            map.strictBoolean("regionExisted") ?: return null,
            otherExplosion.value,
            chestAccess.value,
            pistons.value,
            potionSplash.value,
            use.value,
            passthrough.value,
            passthroughGroup.value,
            creeperExplosion.value,
            destroyVehicle.value,
            interact.value,
            ride.value,
            placeVehicle.value,
        ),
    )
}

private fun EnvironmentSnapshot.toOpaqueMap(): Map<String, Any?> = mapOf(
    "fixedWeather" to fixedWeather,
    "fixedTime" to fixedTime,
    "gravity" to gravity,
    "scale" to scale,
    "allowFlight" to allowFlight,
    "freecamAllowed" to freecamAllowed,
    "protectionMode" to protectionMode.name,
)

private fun decodeEnvironmentSnapshot(value: Any?): EnvironmentSnapshot? {
    val map = strictMap(
        value,
        setOf("fixedWeather", "fixedTime", "gravity", "scale", "allowFlight", "freecamAllowed", "protectionMode"),
    ) ?: return null
    val weather = decodeNullableTyped<String>(map["fixedWeather"]) ?: return null
    val time = decodeNullableTyped<Long>(map["fixedTime"]) ?: return null
    val gravity = decodeNullableTyped<Double>(map["gravity"]) ?: return null
    val scale = decodeNullableTyped<Double>(map["scale"]) ?: return null
    return EnvironmentSnapshot(
        weather.value,
        time.value,
        gravity.value,
        scale.value,
        map.strictBoolean("allowFlight") ?: return null,
        map.strictBoolean("freecamAllowed") ?: return null,
        map.strictString("protectionMode")?.let(::protectionModeOrNull) ?: return null,
    )
}

private fun ChanponWorldStorage.SubmissionSnapshot.toOpaqueMap(): Map<String, Any?> = mapOf(
    "state" to state.name,
    "history" to history.map { entry -> entry.toSortedMap() },
)

private fun ChanponWorldStorage.WorldFileSnapshot.toOpaqueMap(): Map<String, Any?> = mapOf(
    "exists" to exists,
    "bytesBase64" to Base64.getEncoder().encodeToString(bytes),
    "sha256" to sha256,
)

private fun decodeWorldFileSnapshot(value: Any?): ChanponWorldStorage.WorldFileSnapshot? {
    val map = strictMap(value, setOf("exists", "bytesBase64", "sha256")) ?: return null
    val exists = map.strictBoolean("exists") ?: return null
    val bytes = runCatching {
        Base64.getDecoder().decode(map.strictString("bytesBase64") ?: return null)
    }.getOrNull() ?: return null
    val sha256 = map.strictString("sha256") ?: return null
    return ChanponWorldStorage.WorldFileSnapshot(exists, bytes, sha256)
}

private fun decodeSubmissionSnapshot(value: Any?): ChanponWorldStorage.SubmissionSnapshot? {
    val map = strictMap(value, setOf("state", "history")) ?: return null
    val state = map.strictString("state")?.let(::submissionStateOrNull) ?: return null
    val history = map["history"] as? List<*> ?: return null
    val decodedHistory = history.map { rawEntry ->
        val rawMap = rawEntry as? Map<*, *> ?: return null
        val entry = linkedMapOf<String, Any?>()
        rawMap.forEach { (key, nested) ->
            if (key !is String || (nested != null && nested !is String)) return null
            entry[key] = nested
        }
        entry.toMap()
    }
    return ChanponWorldStorage.SubmissionSnapshot(state, decodedHistory)
}

private fun expectedSubmissionAfter(
    state: SubmissionProviderState.World,
    current: ChanponWorldStorage.SubmissionSnapshot,
): ChanponWorldStorage.SubmissionSnapshot? {
    if (current.state != state.expectedState || current.revision != state.expectedRevision) return null
    if (current.history.dropLast(1) != state.before.history) return null
    val event = current.history.lastOrNull() ?: return null
    if (event.keys != SUBMISSION_HISTORY_KEYS) return null
    if (event["action"] != state.expectedAction) return null
    if (event["state"] != state.expectedState.storageValue) return null
    if (event["actor_uuid"] != state.expectedActorUuid.toString()) return null
    if (event["actor_name"] != state.expectedActorName) return null
    val at = event["at"] as? String ?: return null
    if (runCatching { java.time.LocalDateTime.parse(at) }.isFailure) return null
    return ChanponWorldStorage.SubmissionSnapshot(
        state.expectedState,
        state.before.history.map { it.toMap() } + event.toMap(),
    )
}

private fun visibilityOrNull(value: String): ChanponWorldVisibility? =
    runCatching { ChanponWorldVisibility.valueOf(value) }.getOrNull()

private fun submissionStateOrNull(value: String): SubmissionState? =
    runCatching { SubmissionState.valueOf(value) }.getOrNull()

private fun creationAvailabilityOrNull(value: String): WorldCreationAvailability? =
    runCatching { WorldCreationAvailability.valueOf(value) }.getOrNull()

private fun protectionModeOrNull(value: String): ChanponWorldProtectionMode? =
    runCatching { ChanponWorldProtectionMode.valueOf(value) }.getOrNull()

private fun strictMap(value: Any?, keys: Set<String>): Map<String, Any?>? {
    val raw = value as? Map<*, *> ?: return null
    if (raw.keys != keys) return null
    if (raw.keys.any { it !is String }) return null
    @Suppress("UNCHECKED_CAST")
    return raw as Map<String, Any?>
}

private data class NullableDecoded<T>(val value: T?)

private fun decodeNullableUuid(value: Any?): NullableDecoded<UUID>? = when (value) {
    null -> NullableDecoded(null)
    is UUID -> NullableDecoded(value)
    else -> null
}

private fun decodeNullableBoolean(value: Any?): NullableDecoded<Boolean>? = when (value) {
    null -> NullableDecoded(null)
    is Boolean -> NullableDecoded(value)
    else -> null
}

private inline fun <reified T> decodeNullableTyped(value: Any?): NullableDecoded<T>? = when (value) {
    null -> NullableDecoded(null)
    is T -> NullableDecoded(value)
    else -> null
}

private fun <T> decodeNullableEnum(value: Any?, parse: (String) -> T): NullableDecoded<T>? = when (value) {
    null -> NullableDecoded(null)
    is String -> runCatching { NullableDecoded(parse(value)) }.getOrNull()
    else -> null
}

private const val AUTOMATION_PROVIDER_ID = "mwm-chanpon:automation-settings"
private const val ADMIN_PROVIDER_ID = "mwm-chanpon:admin-world-controls"
private const val ENVIRONMENT_PROVIDER_ID = "mwm-chanpon:world-environment"
private const val PUBLICATION_PROVIDER_ID = "mwm-chanpon:world-publication"
private const val NOTIFICATION_PROVIDER_ID = "mwm-chanpon:world-notification"
private const val SUBMISSION_PROVIDER_ID = "mwm-chanpon:submission"
private const val TOOL_PROVIDER_ID = "mwm-chanpon:tool-permission"
private val SUBMISSION_HISTORY_KEYS = setOf("at", "action", "state", "actor_uuid", "actor_name")

private const val TOOL_ID = "tool_id"
