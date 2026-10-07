package me.awabi2048.mwmchanpon.listener

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.generated.ChanponProtectionKeys
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
 * キャンセル済みの建築イベントを観測し、拒否理由を通知します。
 *
 * 実際のキャンセルはWorldGuardのリージョンフラグ・PlayerStatePolicy経由の
 * 外部リスナー・MWMスポーンガードが行うため、本リスナーはMONITORで
 * キャンセル済みイベントだけを観測し、状態から理由を再評価します。
 * イベント自体は変更しません。理由が確定できる場合はアクションバー、
 * 説明できない外部由来の拒否だけはチャットで通知します。
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
        // 管理対象のMyWorldだけを対象にし、それ以外のワールドでのキャンセルは
        // 他プラグインの責務として通知しません。
        val worldData = MyWorldManagerApi.getWorldRepository()
            ?.findByWorldName(block.world.name)
            ?: return
        val reason = resolver.resolve(player, worldData, block, placing)
        if (reason != null) {
            player.sendActionBar(Component.text(CCSystem.getAPI().getLocalized(player, reason.messageKey)))
            return
        }
        // Chanpon側の条件で説明できないキャンセル（手動作成のWorldGuardリージョンや
        // 外部保護プラグイン等）は誤判定の余地があるため、原因を断定しない文言を
        // アクションバーではなくチャットで残し、スタッフへの確認を促します。
        player.sendMessage(Component.text(CCSystem.getAPI().getLocalized(player, ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_EXTERNAL)))
    }
}
