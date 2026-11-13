package me.awabi2048.mwmchanpon.listener

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.TourModeState
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 観光モード中の操作直後だけ、６方向の装飾ブロックへの物理更新を止めます。 */
internal class InteractionAdjacentPhysicsListener(
    private val isActive: (org.bukkit.World) -> Boolean,
    private val nextTick: (() -> Unit) -> Unit,
) : Listener {
    constructor(plugin: MWMChanponPlugin) : this(
        { TourModeState.isEnabled() },
        { action -> plugin.server.scheduler.runTask(plugin, Runnable { action() }) },
    )
    private val interactions = ConcurrentHashMap<BlockPosition, Long>()
    private val nextToken = AtomicLong()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (event.useInteractedBlock() == Event.Result.DENY) return
        val direct = event.action == Action.RIGHT_CLICK_BLOCK &&
            event.hand != EquipmentSlot.OFF_HAND
        val physical = event.action == Action.PHYSICAL && block.type.name.endsWith("_PRESSURE_PLATE")
        if ((!direct && !physical) || !isActive(block.world)) return
        val position = BlockPosition.of(block)
        val token = nextToken.incrementAndGet()
        interactions[position] = token
        // 同じブロックに別の操作が重なった場合、古い終了タスクで新しい保護を解除しません。
        nextTick { interactions.remove(position, token) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        if (shouldProtect(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDestroy(event: BlockDestroyEvent) {
        // 物理イベントを経由せず支持喪失で破壊されるブロックも同じ短い操作区間だけ保護します。
        if (shouldProtect(event.block)) event.isCancelled = true
    }

    private fun shouldProtect(block: Block): Boolean {
        if (interactions.isEmpty() || !isActive(block.world)) return false
        val target = BlockPosition.of(block)
        // 監視中の操作件数に関係なく、対象の６近傍だけを調べます。
        if (target.adjacent().none(interactions::containsKey)) return false
        return !isRedstoneBlock(block)
    }
}

internal data class BlockPosition(val world: UUID, val x: Int, val y: Int, val z: Int) {
    fun isAdjacentTo(other: BlockPosition): Boolean = world == other.world &&
        kotlin.math.abs(x - other.x) + kotlin.math.abs(y - other.y) + kotlin.math.abs(z - other.z) == 1

    fun adjacent(): List<BlockPosition> = listOf(copy(x = x + 1), copy(x = x - 1), copy(y = y + 1),
        copy(y = y - 1), copy(z = z + 1), copy(z = z - 1))

    companion object { fun of(block: Block) = BlockPosition(block.world.uid, block.x, block.y, block.z) }
}

/** 信号を発生・伝達・受信する装置は隣接していても通常の物理処理に任せます。 */
internal fun isRedstoneBlock(block: Block): Boolean {
    val data = block.blockData
    return data is Powerable || data is AnaloguePowerable || data is RedstoneWire || isRedstoneMaterial(block.type)
}

internal fun isRedstoneMaterial(material: Material): Boolean {
    val name = material.name
    return name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE") ||
        name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE") ||
        name == "COPPER_BULB" || name.endsWith("_COPPER_BULB") || name in REDSTONE_MATERIALS
}

private val REDSTONE_MATERIALS = setOf(
    "LEVER", "REDSTONE_WIRE", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH", "REDSTONE_BLOCK", "REDSTONE_LAMP",
    "REPEATER", "COMPARATOR", "OBSERVER", "PISTON", "STICKY_PISTON", "PISTON_HEAD", "MOVING_PISTON",
    "DISPENSER", "DROPPER", "HOPPER", "CRAFTER", "TARGET", "NOTE_BLOCK", "BELL", "DAYLIGHT_DETECTOR",
    "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL", "TRIPWIRE", "TRIPWIRE_HOOK",
    "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "LIGHTNING_ROD", "TNT",
)
