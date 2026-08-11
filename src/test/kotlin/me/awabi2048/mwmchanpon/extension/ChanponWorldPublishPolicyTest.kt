package me.awabi2048.mwmchanpon.extension

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.extension.WorldPublishReversibleRestoreResult
import me.awabi2048.myworldmanager.model.ManagedDimension
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Material
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponWorldPublishPolicyTest {
    @Test
    fun `custom publish state restores only after the expected visibility transition`() {
        val folder = Files.createTempDirectory("chanpon-publish-policy").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val policy = ChanponWorldPublishPolicy(storage) { _, _ -> }
            val player = player()
            val world = WorldData(
                UUID.randomUUID(), ManagedDimension.OVERWORLD, "test", "", Material.STONE, "test", "", UUID.randomUUID(),
            )
            val original = "# preserve formatting\nvisibility: private\ncustom:\n  untouched: 'yes'\n"
            storage.worldFile(world.uuid).writeText(original)
            val before = policy.capturePublishCycleState(player, world)
            assertEquals(true, policy.cyclePublishLevel(player, world))
            val actualAfter = policy.capturePublishCycleState(player, world)
            assertEquals(
                WorldPublishReversibleRestoreResult.Restored,
                policy.restorePublishCycleState(player, world, before, actualAfter),
            )
            assertEquals(ChanponWorldVisibility.PRIVATE, storage.getVisibility(world.uuid))
            assertEquals(original, storage.worldFile(world.uuid).readText())

            val staleBefore = policy.capturePublishCycleState(player, world)
            assertEquals(true, policy.cyclePublishLevel(player, world))
            val staleActualAfter = policy.capturePublishCycleState(player, world)
            storage.setVisibility(world.uuid, ChanponWorldVisibility.DEVELOPMENT)
            assertInstanceOf(
                WorldPublishReversibleRestoreResult.Rejected::class.java,
                policy.restorePublishCycleState(player, world, staleBefore, staleActualAfter),
            )
            assertEquals(ChanponWorldVisibility.DEVELOPMENT, storage.getVisibility(world.uuid))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `publish restore removes world file created by the operation when it was absent before`() {
        val folder = Files.createTempDirectory("chanpon-publish-policy-absent").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            var callbackVisibility: ChanponWorldVisibility? = null
            val policy = ChanponWorldPublishPolicy(storage) { _, visibility -> callbackVisibility = visibility }
            val player = player()
            val world = WorldData(
                UUID.randomUUID(), ManagedDimension.OVERWORLD, "test", "", Material.STONE, "test", "", UUID.randomUUID(),
            )
            assertFalse(storage.worldFile(world.uuid).exists())

            val before = policy.capturePublishCycleState(player, world)
            assertTrue(policy.cyclePublishLevel(player, world))
            assertEquals(ChanponWorldVisibility.PUBLIC, callbackVisibility)
            assertTrue(storage.worldFile(world.uuid).exists())
            val actualAfter = policy.capturePublishCycleState(player, world)

            assertEquals(
                WorldPublishReversibleRestoreResult.Restored,
                policy.restorePublishCycleState(player, world, before, actualAfter),
            )
            assertFalse(storage.worldFile(world.uuid).exists())
            assertEquals(ChanponWorldVisibility.PRIVATE, storage.getVisibility(world.uuid))
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun player(): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
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
    } as Player
}
