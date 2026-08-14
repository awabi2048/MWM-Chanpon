package me.awabi2048.mwmchanpon.service

import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.RestoreOperationResult
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

class ManualRollbackService(
    private val plugin: MWMChanponPlugin
) {
    fun schedule(
        actor: Player,
        operation: () -> CompletableFuture<RestoreOperationResult>,
        onComplete: (RestoreOperationResult) -> Unit
    ) {
        val delaySeconds = plugin.config.getLong("backup.manual_rollback.delay_seconds", 15L).coerceAtLeast(0L)
        broadcastAnnouncement(actor.name, delaySeconds)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val actorName = actor.name
            try {
                operation().whenComplete { result, throwable ->
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (throwable != null) {
                            plugin.logger.warning("Manual rollback failed: actor=$actorName, error=${throwable.message}")
                            return@Runnable
                        }
                        if (result.status != BackupOperationStatus.SUCCESS && result.status != BackupOperationStatus.RECOVERED) {
                            plugin.logger.warning("Manual rollback completed with failure: actor=$actorName, status=${result.status}, message=${result.message}")
                        }
                        onComplete(result)
                    })
                }
            } catch (ex: Exception) {
                plugin.logger.warning("Manual rollback failed to start: actor=$actorName, error=${ex.message}")
            }
        }, delaySeconds * 20L)
    }

    private fun broadcastAnnouncement(actorName: String, delaySeconds: Long) {
        val lines = CCSystem.getAPI().getI18nComponentList(
            null,
            ChanponBackupKeys.CHANPON_BACKUP_MANUAL_ROLLBACK_ANNOUNCEMENT,
            mapOf("actor" to actorName, "delay" to delaySeconds)
        )
        lines.forEach(Bukkit::broadcast)
    }
}
