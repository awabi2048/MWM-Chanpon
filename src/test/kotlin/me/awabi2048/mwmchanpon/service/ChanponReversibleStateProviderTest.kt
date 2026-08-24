package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.gui.MenuReversibleInteractionContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderCaptureResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleProviderRestoreResult
import com.awabi2048.ccsystem.api.gui.MenuReversibleOpaqueState
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateCaptureContext
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateProvider
import com.awabi2048.ccsystem.api.gui.MenuReversibleStateRestoreContext
import com.awabi2048.ccsystem.api.gui.MenuRoute
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import me.awabi2048.mwmchanpon.restoreContextForTest
import me.awabi2048.mwmchanpon.snapshotForTest
import me.awabi2048.mwmchanpon.protection.SpawnWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.mwmchanpon.storage.WorldCreationAvailability
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiWorldRepository
import me.awabi2048.myworldmanager.model.WorldData
import me.awabi2048.myworldmanager.model.ManagedDimension
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponReversibleStateProviderTest {
    @Test
    fun `opaque snapshot detaches mutable provider payload`() {
        val mutableHistory = mutableListOf<Map<String, Any?>>(mapOf("value" to "before"))
        val state = ChanponReversibleOpaqueState.of(
            "mwm-chanpon:test",
            "mutable",
            mapOf("history" to mutableHistory),
        )
        val snapshot = snapshotForTest(state)
        mutableHistory.clear()
        val envelope = snapshot.value as Map<*, *>
        val payload = envelope["payload"] as Map<*, *>
        assertEquals(listOf(mapOf("value" to "before")), payload["history"])
    }

    @Test
    fun `automation provider restores only its expected toggle state`() = withWorldStorage { storage, _ ->
        val gateway = MutableAutomationGateway(false)
        val provider = AutomationSettingsProvider(gateway)
        val player = player()
        val context = captureContext(player, ChanponReversibleContracts.automation("backup.auto_backup.enabled"))
        val state = captured(provider, context)

        gateway.value = true
        assertRestored(provider, player, context, state)
        assertEquals(false, gateway.value)
        val rejected = captured(provider, context)
        assertRejected(provider, player, context, rejected)
    }

    @Test
    fun `admin provider restores building and creation controls with CAS`() = withWorldStorage { _, root ->
        val global = ChanponGlobalStorage(root.resolve("global"))
        val provider = AdminWorldControlsProvider(global, SpawnWorldProtectionService()) { }
        val player = player()

        val building = captureContext(player, ChanponReversibleContracts.adminWorldControls("building"))
        val buildingState = captured(provider, building)
        global.setBuildingAllowed(false)
        assertRestored(provider, player, building, buildingState)
        assertEquals(true, global.isBuildingAllowed())
        assertRejected(provider, player, building, captured(provider, building))

        val creation = captureContext(player, ChanponReversibleContracts.adminWorldControls("creation"))
        val creationState = captured(provider, creation)
        global.setWorldCreationAvailability(WorldCreationAvailability.DENIED)
        assertRestored(provider, player, creation, creationState)
        assertEquals(WorldCreationAvailability.PRODUCTION, global.getWorldCreationAvailability())
        assertRejected(provider, player, creation, captured(provider, creation))
    }

    @Test
    fun `environment publication and notification providers restore and reject stale state`() = withWorldStorage { storage, _ ->
        val world = world()
        registerRepository(world)
        val player = player()
        val applied = mutableListOf<UUID>()
        val environment = EnvironmentProvider(storage) { applied += it.uuid }
        val environmentContext = captureContext(
            player,
            ChanponReversibleContracts.environment(world.uuid),
            arguments = mapOf("setting" to "flight"),
        )
        val environmentState = captured(environment, environmentContext)
        world.allowFlight = true
        assertRestored(environment, player, environmentContext, environmentState)
        assertEquals(false, world.allowFlight)
        assertEquals(listOf(world.uuid), applied)
        assertRejected(environment, player, environmentContext, captured(environment, environmentContext))

        val publication = PublicationProvider(storage)
        val publicationContext = captureContext(
            player,
            ChanponReversibleContracts.publication(),
            arguments = mapOf("world_uuid" to world.uuid.toString()),
        )
        val publicationState = captured(publication, publicationContext)
        storage.setVisibility(world.uuid, ChanponWorldVisibility.PUBLIC)
        assertRestored(publication, player, publicationContext, publicationState)
        assertEquals(ChanponWorldVisibility.PRIVATE, storage.getVisibility(world.uuid))
        assertRejected(publication, player, publicationContext, captured(publication, publicationContext))

        val notificationContext = captureContext(
            player,
            ChanponReversibleContracts.notification(),
            arguments = mapOf("world_uuid" to world.uuid.toString()),
        )
        val notificationState = captured(NotificationProvider, notificationContext)
        world.notificationEnabled = false
        assertRestored(NotificationProvider, player, notificationContext, notificationState)
        assertEquals(true, world.notificationEnabled)
        assertRejected(NotificationProvider, player, notificationContext, captured(NotificationProvider, notificationContext))
    }

    @Test
    fun `publication restore rejects a deleted target explicitly`() = withWorldStorage { storage, _ ->
        val world = world()
        registerRepository(world)
        val player = player()
        val provider = PublicationProvider(storage)
        val context = captureContext(
            player,
            ChanponReversibleContracts.publication(),
            arguments = mapOf("world_uuid" to world.uuid.toString()),
        )
        val state = captured(provider, context)
        storage.setVisibility(world.uuid, ChanponWorldVisibility.PUBLIC)
        registerRepository()
        val rejected = assertInstanceOf(
            MenuReversibleProviderRestoreResult.Rejected::class.java,
            provider.restore(restoreContextForTest(player, context, state)),
        )
        assertTrue(rejected.reason.contains("TARGET_MISSING"))
    }

    @Test
    fun `world file providers restore exact preimage bytes`() = withWorldStorage { storage, _ ->
        val world = world()
        val target = UUID.randomUUID()
        registerRepository(world)
        val player = player()
        val original = """
            submission:
              state: unsubmitted
            visibility: private
            visibility_set_at: 'original-visibility-time'
            protection:
              mode: members
              updated_at: 'original-protection-time'
            tool_permissions:
              preserved-player:
                worldedit: false
        """.trimIndent() + "\n"
        storage.worldFile(world.uuid).apply { parentFile.mkdirs(); writeText(original) }

        val publication = PublicationProvider(storage)
        val publicationContext = captureContext(
            player, ChanponReversibleContracts.publication(),
            arguments = mapOf("world_uuid" to world.uuid.toString()),
        )
        val publicationState = captured(publication, publicationContext)
        storage.setVisibility(world.uuid, ChanponWorldVisibility.PUBLIC)
        assertRestored(publication, player, publicationContext, publicationState)
        assertEquals(original, storage.worldFile(world.uuid).readText())

        val environment = EnvironmentProvider(storage) { }
        val environmentContext = captureContext(
            player, ChanponReversibleContracts.environment(world.uuid),
            arguments = mapOf("setting" to "protection"),
        )
        val environmentState = captured(environment, environmentContext)
        storage.setProtectionMode(world.uuid, me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode.PUBLIC)
        assertRestored(environment, player, environmentContext, environmentState)
        assertEquals(original, storage.worldFile(world.uuid).readText())

        val tool = ToolPermissionProvider(storage, RecordingToolGateway())
        val toolContext = captureContext(
            player, ChanponReversibleContracts.toolPermission(world.uuid, target, "toggle"),
            arguments = mapOf("tool_id" to "worldedit"),
        )
        val toolState = captured(tool, toolContext)
        storage.setToolEnabled(world.uuid, target, "worldedit", true)
        assertRestored(tool, player, toolContext, toolState)
        assertEquals(original, storage.worldFile(world.uuid).readText())
    }

    @Test
    fun `submission and tool providers restore domain state and synchronize exactly after CAS`() = withWorldStorage { storage, _ ->
        val world = world()
        val actor = UUID(0L, 1L)
        val target = UUID.randomUUID()
        world.moderators += target
        registerRepository(world)
        storage.initializeSubmissionState(world.uuid)
        val player = player()
        val submissionGateway = RecordingSubmissionGateway()
        val submission = SubmissionProvider(submissionGateway, storage)
        val completionContext = captureContext(player, ChanponReversibleContracts.submission(world.uuid.toString(), "complete"))
        val completionState = captured(submission, completionContext)
        storage.setSubmissionState(world.uuid, SubmissionState.COMPLETED, actor, "tester", "mark_completed")
        assertRestored(submission, player, completionContext, completionState)
        assertEquals(SubmissionState.UNSUBMITTED, storage.getSubmissionState(world.uuid))
        assertEquals(listOf(world.uuid), submissionGateway.syncedWorlds)
        assertRejected(submission, player, completionContext, captured(submission, completionContext))

        val closureGateway = RecordingSubmissionGateway()
        val closure = SubmissionProvider(closureGateway, storage)
        val closureContext = captureContext(player, ChanponReversibleContracts.submission("global", "close"))
        val closureState = captured(closure, closureContext)
        closureGateway.closed = true
        assertRestored(closure, player, closureContext, closureState)
        assertEquals(false, closureGateway.closed)
        assertRejected(closure, player, closureContext, captured(closure, closureContext))

        val toolGateway = RecordingToolGateway()
        val tool = ToolPermissionProvider(storage, toolGateway)
        val toggleContext = captureContext(
            player,
            ChanponReversibleContracts.toolPermission(world.uuid, target, "toggle"),
            arguments = mapOf("tool_id" to "worldedit"),
        )
        val toggleState = captured(tool, toggleContext)
        storage.setToolEnabled(world.uuid, target, "worldedit", true)
        assertRestored(tool, player, toggleContext, toggleState)
        assertEquals(null, storage.getToolEnabledState(world.uuid, target, "worldedit"))
        assertEquals(listOf(world.uuid), toolGateway.syncedWorlds)
        assertRejected(tool, player, toggleContext, captured(tool, toggleContext))

        val roleContext = captureContext(player, ChanponReversibleContracts.toolPermission(world.uuid, target, "role"))
        val roleState = captured(tool, roleContext)
        world.moderators.remove(target)
        world.members += target
        assertRestored(tool, player, roleContext, roleState)
        assertEquals(listOf(target), world.moderators)
        assertEquals(emptyList<UUID>(), world.members)
        assertRejected(tool, player, roleContext, captured(tool, roleContext))
    }

    @Test
    fun `submission restore rejects same length history content mutation`() = withWorldStorage { storage, _ ->
        val world = world()
        registerRepository(world)
        storage.initializeSubmissionState(world.uuid)
        val player = player()
        val provider = SubmissionProvider(RecordingSubmissionGateway(), storage)
        val context = captureContext(player, ChanponReversibleContracts.submission(world.uuid.toString(), "complete"))
        val state = captured(provider, context)
        storage.setSubmissionState(world.uuid, SubmissionState.COMPLETED, player.uniqueId, player.name, "mark_completed")
        val actual = storage.captureSubmissionSnapshot(world.uuid)
        val changedEvent = actual.history.single().toMutableMap().apply { put("actor_name", "changed") }
        storage.restoreSubmissionSnapshot(worldUuid = world.uuid, snapshot = actual.copy(history = listOf(changedEvent)))
        assertRejected(provider, player, context, state)
    }

    private fun captured(
        provider: MenuReversibleStateProvider,
        context: MenuReversibleStateCaptureContext,
    ): MenuReversibleOpaqueState =
        assertInstanceOf(MenuReversibleOpaqueState::class.java,
            assertInstanceOf(MenuReversibleProviderCaptureResult.Captured::class.java, provider.capture(context)).state)

    private fun assertRestored(
        provider: MenuReversibleStateProvider,
        player: Player,
        capture: MenuReversibleStateCaptureContext,
        state: MenuReversibleOpaqueState,
    ) {
        assertEquals(
            MenuReversibleProviderRestoreResult.Restored,
            provider.restore(restoreContext(player, capture, state)),
        )
    }

    private fun assertRejected(
        provider: MenuReversibleStateProvider,
        player: Player,
        capture: MenuReversibleStateCaptureContext,
        state: MenuReversibleOpaqueState,
    ) {
        assertInstanceOf(
            MenuReversibleProviderRestoreResult.Rejected::class.java,
            provider.restore(restoreContext(player, capture, state)),
        )
    }

    private fun captureContext(
        player: Player,
        contract: com.awabi2048.ccsystem.api.gui.MenuReversibleContract,
        arguments: Map<String, String> = emptyMap(),
    ): MenuReversibleStateCaptureContext {
        val route = MenuRoute("mwm-chanpon", "test")
        val interaction = MenuReversibleInteractionContext(
            0, ClickType.LEFT, "test", "test-capability", contract, 1L, arguments, emptyMap(), route.payload,
        )
        return MenuReversibleStateCaptureContext(player, route, "run", interaction)
    }

    private fun restoreContext(
        player: Player,
        capture: MenuReversibleStateCaptureContext,
        state: MenuReversibleOpaqueState,
    ) = restoreContextForTest(player, capture, state)

    private fun world(): WorldData = WorldData(
        UUID.randomUUID(), ManagedDimension.OVERWORLD, "test", "", Material.STONE, "test", "", UUID.randomUUID(),
    )

    private fun registerRepository(vararg worlds: WorldData) {
        MyWorldManagerApi.registerWorldRepository(InMemoryWorldRepository(worlds.associateBy { it.uuid }.toMutableMap()))
    }

    private fun player(): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getUniqueId" -> UUID(0L, 1L)
            "getName" -> "tester"
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Short::class.javaPrimitiveType -> 0.toShort()
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0F
                Double::class.javaPrimitiveType -> 0.0
                Char::class.javaPrimitiveType -> '\u0000'
                else -> null
            }
        }
    } as Player

    private fun withWorldStorage(block: (ChanponWorldStorage, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("chanpon-reversible-provider").toFile()
        try {
            block(ChanponWorldStorage(root), root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class MutableAutomationGateway(var value: Boolean) : AutomationSettingsGateway {
        override fun getBoolean(path: String): Boolean = value
        override fun restoreBoolean(path: String, value: Boolean) {
            this.value = value
        }
    }

    private class RecordingSubmissionGateway : SubmissionGateway {
        var closed = false
        val syncedWorlds = mutableListOf<UUID>()
        override fun isClosed(): Boolean = closed
        override fun restoreClosed(value: Boolean) {
            closed = value
        }
        override fun syncWorld(worldData: WorldData) {
            syncedWorlds += worldData.uuid
        }
    }

    private class RecordingToolGateway : ToolPermissionGateway {
        val syncedWorlds = mutableListOf<UUID>()
        override fun isEnabled(worldData: WorldData, playerUuid: UUID, toolId: String): Boolean = false
        override fun storedEnabledAfterSet(worldData: WorldData, enabled: Boolean): Boolean = enabled
        override fun syncWorld(worldData: WorldData) {
            syncedWorlds += worldData.uuid
        }
    }

    private class InMemoryWorldRepository(
        private val worlds: MutableMap<UUID, WorldData>,
    ) : ApiWorldRepository {
        override fun findByUuid(uuid: UUID): WorldData? = worlds[uuid]
        override fun findByOwner(ownerUuid: UUID): List<WorldData> = worlds.values.filter { it.owner == ownerUuid }
        override fun findByOwnerAndDisplayName(ownerUuid: UUID, worldName: String, excludingUuid: UUID?): WorldData? =
            worlds.values.firstOrNull { it.owner == ownerUuid && it.name == worldName && it.uuid != excludingUuid }
        override fun findByWorldName(worldName: String): WorldData? = worlds.values.firstOrNull { it.name == worldName }
        override fun findByWorldKey(worldKey: String): WorldData? = worlds.values.firstOrNull { it.worldKey == worldKey }
        override fun findAll(): List<WorldData> = worlds.values.toList()
        override fun save(worldData: WorldData) {
            worlds[worldData.uuid] = worldData
        }
        override fun delete(uuid: UUID) {
            worlds.remove(uuid)
        }
    }
}
