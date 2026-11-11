package me.awabi2048.mwmchanpon.listener

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TourAdjacentPhysicsTest {
    @Test
    fun `直接隣接する六方向だけを対象にする`() {
        val id = UUID.randomUUID()
        val origin = BlockPosition(id, 10, 64, -5)
        listOf(BlockPosition(id, 11, 64, -5), BlockPosition(id, 9, 64, -5),
            BlockPosition(id, 10, 65, -5), BlockPosition(id, 10, 63, -5),
            BlockPosition(id, 10, 64, -4), BlockPosition(id, 10, 64, -6)).forEach {
            assertTrue(origin.isAdjacentTo(it))
        }
        assertFalse(origin.isAdjacentTo(origin))
        assertFalse(origin.isAdjacentTo(BlockPosition(id, 11, 65, -5)))
        assertFalse(origin.isAdjacentTo(BlockPosition(UUID.randomUUID(), 11, 64, -5)))
    }

    @Test
    fun `装飾は保護対象としレッドストーン装置は通常更新を維持する`() {
        listOf(Material.LEVER, Material.STONE_BUTTON, Material.OAK_PRESSURE_PLATE, Material.REDSTONE_WIRE,
            Material.REDSTONE_LAMP, Material.OBSERVER, Material.OAK_TRAPDOOR, Material.PISTON,
            Material.COPPER_BULB, Material.HOPPER).forEach { assertTrue(isRedstoneMaterial(it), it.name) }
        listOf(Material.LANTERN, Material.TORCH, Material.SAND, Material.STONE, Material.WATER).forEach {
            assertFalse(isRedstoneMaterial(it), it.name)
        }
    }
}
