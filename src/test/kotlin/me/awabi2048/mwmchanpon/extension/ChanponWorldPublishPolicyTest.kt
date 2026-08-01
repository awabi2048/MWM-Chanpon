package me.awabi2048.mwmchanpon.extension

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.extension.WorldPublishReversibleRestoreResult
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Material
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
                UUID.randomUUID(), "test", "", Material.STONE, "test", "", UUID.randomUUID(),
            )
            val before = policy.capturePublishCycleState(player, world)
            assertEquals(true, policy.cyclePublishLevel(player, world))
            val actualAfter = policy.capturePublishCycleState(player, world)
            assertEquals(
                WorldPublishReversibleRestoreResult.Restored,
                policy.restorePublishCycleState(player, world, before, actualAfter),
            )
            assertEquals(ChanponWorldVisibility.PRIVATE, storage.getVisibility(world.uuid))

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
