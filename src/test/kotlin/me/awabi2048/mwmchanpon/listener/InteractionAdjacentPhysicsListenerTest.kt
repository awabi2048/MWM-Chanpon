package me.awabi2048.mwmchanpon.listener

import java.lang.reflect.Proxy
import java.util.UUID
import com.destroystokyo.paper.event.block.BlockDestroyEvent
import org.bukkit.Material
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Powerable
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InteractionAdjacentPhysicsListenerTest {
    private fun <T> proxy(type: Class<T>, body: (String) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> body(method.name) },
    )
    private val id = UUID.randomUUID()
    private val world = proxy(World::class.java) { if (it == "getUID") id else error("world.$it") }
    private val player = proxy(Player::class.java) { error("player.$it") }

    private fun block(x: Int, y: Int, material: Material, powered: Boolean = false, tick: () -> Unit = {}): Block {
        val data = if (material == Material.STONE_BUTTON) proxy(Powerable::class.java) {
            when (it) { "isPowered" -> powered; "getMaterial" -> material; else -> error("data.$it") }
        } else proxy(BlockData::class.java) { if (it == "getMaterial") material else error("data.$it") }
        return proxy(Block::class.java) {
            when (it) {
                "getWorld" -> world; "getX" -> x; "getY" -> y; "getZ" -> 0
                "getType" -> material; "getBlockData" -> data; "tick" -> { tick(); null }
                else -> error("block.$it")
            }
        }
    }

    private fun interact(block: Block) = PlayerInteractEvent(
        player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND,
    )

    @Test
    fun `設定が有効な操作の直後だけ隣接装飾を保護し回路は通す`() {
        val pending = mutableListOf<() -> Unit>()
        val listener = InteractionAdjacentPhysicsListener({ true }, { pending.add(it) })
        val lever = block(0, 64, Material.LEVER)
        val lantern = block(1, 64, Material.LANTERN)
        val diagonal = block(1, 65, Material.LANTERN)
        val wire = block(-1, 64, Material.REDSTONE_WIRE)
        listener.onInteract(interact(lever))
        fun physics(block: Block) = BlockPhysicsEvent(block, block.blockData, lever).also(listener::onPhysics)
        assertTrue(physics(lantern).isCancelled)
        val destroy = BlockDestroyEvent(lantern, lantern.blockData, lantern.blockData, 0, false)
        listener.onDestroy(destroy)
        assertTrue(destroy.isCancelled)
        assertFalse(physics(diagonal).isCancelled)
        assertFalse(physics(wire).isCancelled)
        listener.onInteract(interact(lever))
        pending.removeAt(0).invoke()
        assertTrue(physics(lantern).isCancelled)
        pending.removeAt(0).invoke()
        assertFalse(physics(lantern).isCancelled)
        val denied = interact(lever).also { it.setUseInteractedBlock(Event.Result.DENY) }
        listener.onInteract(denied)
        assertFalse(physics(lantern).isCancelled)
        val inactive = InteractionAdjacentPhysicsListener({ false }, { pending.add(it) })
        inactive.onInteract(interact(lever))
        assertFalse(physics(lantern).isCancelled)
    }

    @Test
    fun `観光モード切替はワールド設定に依存せず進行中の保護にも即時反映される`() {
        var enabled = false
        val pending = mutableListOf<() -> Unit>()
        val listener = InteractionAdjacentPhysicsListener({ enabled }, { pending.add(it) })
        val trapdoor = block(0, 64, Material.OAK_TRAPDOOR)
        val grass = block(1, 64, Material.TALL_GRASS)
        fun physics() = BlockPhysicsEvent(grass, grass.blockData, trapdoor).also(listener::onPhysics)
        listener.onInteract(interact(trapdoor))
        assertFalse(physics().isCancelled)
        enabled = true
        listener.onInteract(interact(trapdoor))
        assertTrue(physics().isCancelled)
        enabled = false
        assertFalse(physics().isCancelled)
    }
}
