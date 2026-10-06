package me.awabi2048.mwmchanpon.listener

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.BuildDenialReasonResolver
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import net.kyori.adventure.text.Component
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent

/**
 * キャンセル済みの建築イベントを観測し、拒否理由をアクションバーへ通知します。
 *
 * 実際のキャンセルはWorldGuardのリージョンフラグ・PlayerStatePolicy経由の
 * 外部リスナー・MWMスポーンガードが行うため、本リスナーはMONITORで
 * キャンセル済みイベントだけを観測し、状態から理由を再評価します。
 * イベント自体は変更しません。
 */
class BuildDenialReasonListener(
    private val plugin: MWMChanponPlugin,
    private val resolver: BuildDenialReasonResolver,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!event.isCancelled) return
        notify(event.player, event.block, placing = false)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!event.isCancelled) return
        notify(event.player, event.block, placing = true)
    }

    private fun notify(player: Player, block: Block, placing: Boolean) {
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(block.world.name)
            ?: return
        val reason = resolver.resolve(player, worldData, block, placing) ?: return
        player.sendActionBar(Component.text(CCSystem.getAPI().getLocalized(player, reason.messageKey)))
    }
}
