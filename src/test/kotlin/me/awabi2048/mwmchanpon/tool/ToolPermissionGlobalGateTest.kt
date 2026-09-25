package me.awabi2048.mwmchanpon.tool

import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.model.ManagedDimension
import me.awabi2048.myworldmanager.model.WorldData
import net.luckperms.api.LuckPerms
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 全体の建築許可をツール活動の許可としても扱う契約です。
 * 迂回するのはグローバル設定だけであり、提出ロック等の個別判定は残ります。
 */
class ToolPermissionGlobalGateTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `suspended global disables tools unless bypassed`() {
        val storage = ChanponWorldStorage(tempDir.toFile())
        val owner = UUID.randomUUID()
        val member = UUID.randomUUID()
        val world = world(owner, member)
        var globalAllowed = false
        val controller = controller(storage) { globalAllowed }

        storage.setToolEnabled(world.uuid, member, "worldedit", true)

        // 全体停止中かつバイパスなしでは無効になります。
        assertFalse(controller.isEnabled(world, member, "worldedit"))
        assertFalse(controller.storedEnabledAfterSet(world, member, true))
        assertEquals(
            mapOf("worldedit" to false),
            controller.getMemberToolStates(world, member),
        )

        // 個人バイパスがある場合は全体許可時と同じ状態で判定します。
        storage.setGlobalBypassAllowed(world.uuid, member, true)
        assertTrue(controller.isEnabled(world, member, "worldedit"))
        assertTrue(controller.storedEnabledAfterSet(world, member, true))
        assertEquals(
            mapOf("worldedit" to true),
            controller.getMemberToolStates(world, member),
        )

        // 全体許可時はバイパスなしでも有効です。
        globalAllowed = true
        storage.setGlobalBypassAllowed(world.uuid, member, false)
        assertTrue(controller.isEnabled(world, member, "worldedit"))
    }

    private fun world(owner: UUID, member: UUID): WorldData =
        WorldData(
            UUID.randomUUID(), ManagedDimension.OVERWORLD, "test", "", Material.STONE, "test", "",
            owner,
        ).also { it.members.add(member) }

    private fun controller(
        storage: ChanponWorldStorage,
        globalAllowed: () -> Boolean,
    ): ToolPermissionController {
        val luckPerms = Proxy.newProxyInstance(
            LuckPerms::class.java.classLoader,
            arrayOf(LuckPerms::class.java),
        ) { _, _, _ -> null } as LuckPerms
        val definitions = listOf(
            ToolPermissionDefinition("worldedit", "WorldEdit", Material.WOODEN_AXE, "worldeditor"),
        )
        return ToolPermissionController(
            storage,
            LuckPermsToolPermissionService(luckPerms, Logger.getAnonymousLogger()),
            definitions,
            { false },
            globalAllowed,
            storage::isGlobalBypassAllowed,
        )
    }
}
