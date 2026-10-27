package me.awabi2048.mwmchanpon.tool

import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.model.ManagedDimension
import me.awabi2048.myworldmanager.model.WorldData
import net.luckperms.api.LuckPerms
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 非メンバー用設定が個別設定や公開範囲、提出ロックを越えないことを検証します。 */
class PublicToolPermissionTest {
    @TempDir lateinit var folder: Path
    private val owner = UUID.randomUUID()
    private val guest = UUID.randomUUID()
    private val member = UUID.randomUUID()
    private val moderator = UUID.randomUUID()
    private fun world() = WorldData(
        UUID.randomUUID(), ManagedDimension.OVERWORLD, "test", "", Material.STONE, "test", "", owner,
    ).also { it.members.add(member); it.moderators.add(moderator) }

    private fun controller(storage: ChanponWorldStorage, global: () -> Boolean = { true }): ToolPermissionController {
        val lp = Proxy.newProxyInstance(LuckPerms::class.java.classLoader, arrayOf(LuckPerms::class.java)) { _, _, _ -> null } as LuckPerms
        return ToolPermissionController(
            storage, LuckPermsToolPermissionService(lp, Logger.getAnonymousLogger()),
            listOf(ToolPermissionDefinition("worldedit", "WorldEdit", Material.WOODEN_AXE, "worldeditor")),
            globalBuildingAllowed = global,
            isGlobalBypassAllowed = storage::isGlobalBypassAllowed,
        )
    }

    @Test fun `公開中だけ非メンバーへ付与し個人設定とは分離する`() {
        val storage = ChanponWorldStorage(folder.toFile())
        val world = world()
        val controller = controller(storage)
        storage.setProtectionMode(world.uuid, ChanponWorldProtectionMode.PUBLIC)
        assertFalse(controller.isEnabled(world, guest, "worldedit"))
        storage.setPublicToolEnabled(world.uuid, "worldedit", true)
        // 脱退前の個人設定が残っていても、非メンバーには公開設定を適用します。
        storage.setToolEnabled(world.uuid, guest, "worldedit", false)
        assertTrue(controller.isEnabled(world, guest, "worldedit"))
        assertTrue(controller.isEnabled(world, owner, "worldedit"))
        assertFalse(controller.isEnabled(world, member, "worldedit"))
        assertFalse(controller.isEnabled(world, moderator, "worldedit"))
        storage.setToolEnabled(world.uuid, member, "worldedit", true)
        assertTrue(controller.isEnabled(world, member, "worldedit"))
        for (mode in listOf(ChanponWorldProtectionMode.MEMBERS, ChanponWorldProtectionMode.OWNER_ONLY)) {
            storage.setProtectionMode(world.uuid, mode)
            assertFalse(controller.isEnabled(world, guest, "worldedit"))
        }
        storage.setProtectionMode(world.uuid, ChanponWorldProtectionMode.PUBLIC)
        assertTrue(controller.isEnabled(world, guest, "worldedit"))
        world.members.add(guest)
        assertFalse(controller.isEnabled(world, guest, "worldedit"))
        world.members.remove(guest)
        assertTrue(controller.isEnabled(world, guest, "worldedit"))
    }

    @Test fun `全体停止と提出ロックを優先しバイパスは全体停止だけを迂回する`() {
        val storage = ChanponWorldStorage(folder.toFile())
        val world = world()
        storage.setProtectionMode(world.uuid, ChanponWorldProtectionMode.PUBLIC)
        storage.setPublicToolEnabled(world.uuid, "worldedit", true)
        val controller = controller(storage) { false }
        assertFalse(controller.isEnabled(world, guest, "worldedit"))
        storage.setGlobalBypassAllowed(world.uuid, guest, true)
        assertTrue(controller.isEnabled(world, guest, "worldedit"))
        storage.initializeSubmissionState(world.uuid)
        storage.setSubmissionState(world.uuid, SubmissionState.SUBMITTED, owner, "test", "submit")
        assertFalse(controller.isEnabled(world, guest, "worldedit"))
    }

    @Test fun `設定はワールドごとに永続化し既定では全ツール無効にする`() {
        val storage = ChanponWorldStorage(folder.toFile())
        val world = world()
        val otherWorld = world()
        assertTrue(storage.getPublicToolStates(world.uuid).values.none { it })
        storage.setPublicToolEnabled(world.uuid, "worldedit", true)
        val reloaded = ChanponWorldStorage(folder.toFile())
        assertEquals(true, reloaded.getPublicToolStates(world.uuid)["worldedit"])
        assertTrue(reloaded.getPublicToolStates(otherWorld.uuid).values.none { it })
        assertEquals(false, reloaded.getPublicToolStates(world.uuid)["axiom"])
    }
}
