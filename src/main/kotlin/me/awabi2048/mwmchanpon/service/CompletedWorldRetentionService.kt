package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmAutomaticWorldUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

/** 観光中は完成済みワールドを常駐させ、明示的な保守操作は妨げません。 */
class CompletedWorldRetentionService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
) : Listener {
    private val warned = mutableSetOf<UUID>()

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        // 保守終了後や観光モードの有効化後にも常駐状態へ戻します。
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { synchronize() }, 1L, 1200L)
    }

    @EventHandler(ignoreCancelled = true)
    fun onAutomaticUnload(event: MwmAutomaticWorldUnloadEvent) {
        if (TourModeState.isEnabled() && storage.isSubmissionCompleted(event.worldData.uuid)) {
            event.isCancelled = true
        }
    }

    private fun synchronize() {
        if (!TourModeState.isEnabled()) return
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        val worlds = MyWorldManagerApi.getWorldService() ?: return
        for (world in repository.findAll()) {
            if (!storage.isSubmissionCompleted(world.uuid)) continue
            if (Bukkit.getWorld(worlds.getWorldFolderName(world)) != null) {
                warned.remove(world.uuid)
                continue
            }
            // 保守中のディスク状態をロードしないよう、MWMの作業ロックを尊重します。
            if (MyWorldManagerApi.getActiveWorldOperation(world.uuid) != null) continue
            if (!worlds.loadWorld(world.uuid) && warned.add(world.uuid)) {
                plugin.logger.warning("観光用ワールドを読み込めませんでした: ${world.uuid}")
            }
        }
    }

}
