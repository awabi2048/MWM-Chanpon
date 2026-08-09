package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.CCSystem
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.entity.Player

class OfflineMemberInviteNotifier(
    private val globalStorage: ChanponGlobalStorage,
    private val logger: Logger,
) {
    fun notifyOnJoin(player: Player) {
        // 空の結果も「MWMが正常に保留招待を確認した」結果なので、期限切れ等のIDを安全に整理できます。
        val pendingInvites = runCatching {
            MyWorldManagerApi.getPendingOfflineMemberInvites(player.uniqueId)
        }.getOrElse { error ->
            logger.warning("オフラインメンバー招待の取得に失敗しました: player=${player.uniqueId}, error=${error.message}")
            return
        }
        val pendingInviteIds = pendingInvites.mapTo(mutableSetOf()) { it.id }
        val notifiedInviteIds = globalStorage.retainOfflineMemberInviteIds(player.uniqueId, pendingInviteIds)
        val repository = MyWorldManagerApi.getWorldRepository() ?: return

        val notifications = OfflineMemberInviteNotificationPolicy.selectUnnotified(
            pendingInvites = pendingInvites,
            notifiedInviteIds = notifiedInviteIds,
            worldNameResolver = { worldUuid ->
                runCatching { repository.findByUuid(worldUuid)?.name }.getOrNull()
            },
        )
        notifications.forEach { notification ->
            sendNotification(player, notification)
        }
    }

    private fun sendNotification(player: Player, notification: OfflineMemberInviteNotification) {
        val lines = runCatching {
            CCSystem.getAPI().getI18nComponentList(
                player,
                "chanpon.message.offline_member_invite",
                mapOf("world" to notification.worldName),
            )
        }.getOrElse { error ->
            logger.warning("オフラインメンバー招待通知の生成に失敗しました: invite=${notification.inviteId}, error=${error.message}")
            return
        }
        if (lines.isEmpty()) return

        lines.forEach(player::sendMessage)
        // 生成・送信できた場合だけ記録し、言語キー未反映などで通知を失うことを防ぎます。
        globalStorage.markOfflineMemberInviteNotified(player.uniqueId, notification.inviteId)
    }
}
