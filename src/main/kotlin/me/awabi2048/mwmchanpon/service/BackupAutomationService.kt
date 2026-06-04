package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.backup.BackupKind
import me.awabi2048.mwmchanpon.backup.BackupOperationStatus
import me.awabi2048.mwmchanpon.backup.BackupService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.scheduler.BukkitTask
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class BackupAutomationService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val backupService: BackupService
) {
    private var task: BukkitTask? = null
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun start() {
        stop()
        task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { tick() },
            20L * 30L,
            plugin.config.getLong("backup.automation.check_interval_ticks", 20L * 60L).coerceAtLeast(20L)
        )
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        val now = LocalTime.now()
        val today = LocalDate.now().toString()
        if (plugin.config.getBoolean("backup.auto_backup.enabled", false) &&
            isDue(now, plugin.config.getString("backup.auto_backup.time", "04:00")) &&
            plugin.config.getString("backup.auto_backup.last_run_date") != today
        ) {
            plugin.config.set("backup.auto_backup.last_run_date", today)
            plugin.saveConfig()
            backupService.createChangedScheduledBackup().thenAccept {
                if (it.status != BackupOperationStatus.SUCCESS) {
                    plugin.logger.warning("Scheduled backup failed: ${it.message}")
                }
            }
        }

        if (plugin.config.getBoolean("backup.auto_rollback.enabled", false) &&
            isDue(now, plugin.config.getString("backup.auto_rollback.time", "05:00")) &&
            plugin.config.getString("backup.auto_rollback.last_run_date") != today
        ) {
            val restorePoint = backupService.latestRestorePoint()
            if (restorePoint == null) {
                plugin.config.set("backup.auto_rollback.last_run_date", today)
                plugin.saveConfig()
                plugin.logger.warning("Auto rollback skipped because restore point is missing.")
                return
            }
            plugin.config.set("backup.auto_rollback.last_run_date", today)
            plugin.saveConfig()
            backupService.restoreBackup(restorePoint).thenAccept {
                if (it.status != BackupOperationStatus.SUCCESS && it.status != BackupOperationStatus.RECOVERED) {
                    plugin.logger.warning("Auto rollback failed: ${it.message}")
                }
            }
        }
    }

    fun productionWorldUuids(): List<java.util.UUID> {
        return MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
            .map { it.uuid }
    }

    private fun isDue(now: LocalTime, configured: String?): Boolean {
        val target = runCatching { LocalTime.parse(configured ?: return false, timeFormatter) }.getOrNull() ?: return false
        return now.hour == target.hour && now.minute == target.minute
    }
}
