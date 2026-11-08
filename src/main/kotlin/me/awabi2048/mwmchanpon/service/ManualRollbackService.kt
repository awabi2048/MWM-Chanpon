package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.RestoreOperationResult
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CompletableFuture

class ManualRollbackService(
    private val plugin: MWMChanponPlugin
) {
    private val notices = mutableMapOf<BossBar, BukkitTask>()

    fun schedule(
        actor: Player,
        operation: () -> CompletableFuture<RestoreOperationResult>,
        onComplete: (RestoreOperationResult) -> Unit
    ) {
        val delaySeconds = plugin.config.getLong("backup.manual_rollback.delay_seconds", 15L).coerceAtLeast(0L)
        broadcastAnnouncement(actor.name, delaySeconds)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val actorName = actor.name
            broadcastResult(ChanponBackupKeys.CHANPON_BACKUP_MANUAL_ROLLBACK_STARTED)
            val notice = startNotice()
            try {
                operation().whenComplete { result, throwable ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        finishNotice(notice)
                        if (throwable != null) {
                            plugin.logger.warning("Manual rollback failed: actor=$actorName, error=${throwable.message}")
                            broadcastResult(ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_FAILED, actor)
                            onComplete(failure(throwable))
                            return@Runnable
                        }
                        if (result.status != BackupOperationStatus.SUCCESS && result.status != BackupOperationStatus.RECOVERED) {
                            plugin.logger.warning("Manual rollback completed with failure: actor=$actorName, status=${result.status}, message=${result.message}")
                        }
                        broadcastResult(resultKey(result.status), actor)
                        onComplete(result)
                    })
                }
            } catch (ex: Exception) {
                finishNotice(notice)
                plugin.logger.warning("Manual rollback failed to start: actor=$actorName, error=${ex.message}")
                broadcastResult(ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_FAILED, actor)
                onComplete(failure(ex))
            }
        }, delaySeconds * 20L)
    }

    /** 処理中に参加したプレイヤーにも同じ状態を表示し、完了時には必ず解除します。 */
    private fun startNotice(): BossBar {
        val title = CCSystem.getAPI().getLocalized(null, ChanponBackupKeys.CHANPON_BACKUP_MANUAL_ROLLBACK_RUNNING, emptyMap())
        val bar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID)
        fun sync() {
            val players = Bukkit.getOnlinePlayers().toSet()
            players.filterNot { it in bar.players }.forEach(bar::addPlayer)
            bar.players.filterNot { it in players }.forEach(bar::removePlayer)
        }
        sync()
        notices[bar] = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sync() }, 20L, 20L)
        return bar
    }

    private fun finishNotice(bar: BossBar) {
        notices.remove(bar)?.cancel()
        bar.removeAll()
    }

    fun stop() {
        notices.keys.toList().forEach(::finishNotice)
    }

    private fun broadcastAnnouncement(actorName: String, delaySeconds: Long) {
        Bukkit.getOnlinePlayers().forEach { player ->
            CCSystem.getAPI().getI18nComponentList(
                player,
                ChanponBackupKeys.CHANPON_BACKUP_MANUAL_ROLLBACK_ANNOUNCEMENT,
                mapOf("actor" to actorName, "delay" to delaySeconds)
            ).forEach(player::sendMessage)
        }
    }

    private fun resultKey(status: BackupOperationStatus) = when (status) {
        BackupOperationStatus.SUCCESS -> ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_SUCCESS
        BackupOperationStatus.FAILED -> ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_FAILED
        BackupOperationStatus.RECOVERED -> ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_RECOVERED
        BackupOperationStatus.RECOVERY_FAILED -> ChanponBackupKeys.CHANPON_BACKUP_MESSAGE_RESTORE_RECOVERY_FAILED
    }

    private fun broadcastResult(key: com.awabi2048.ccsystem.api.localization.LocalizationKey<String>, except: Player? = null) {
        Bukkit.getOnlinePlayers().filter { it.uniqueId != except?.uniqueId }.forEach { player ->
            player.sendMessage(CCSystem.getAPI().getI18nComponent(player, key, emptyMap()))
        }
    }

    private fun failure(error: Throwable) = RestoreOperationResult(
        BackupOperationStatus.FAILED, emptyList(), emptyList(), null, error.message ?: error.javaClass.simpleName
    )
}
