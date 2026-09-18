package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID

/**
 * 定時バックアップの対象判定に使う、ワールド単位の入室記録です。
 * サーバー参加時とワールド移動時の両方で入室先を記録し、前回バックアップ以降の訪問有無を保持します。
 */
class WorldVisitListener(
    private val storage: ChanponWorldStorage,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        markVisited(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldChanged(event: PlayerChangedWorldEvent) {
        markVisited(event.player)
    }

    private fun markVisited(player: Player) {
        val worldUuid = resolveWorldUuid(player) ?: return
        if (!storage.isProductionWorld(worldUuid)) return
        // 既に対象化済みの訪問は再記録せず、1バックアップ周期あたりの書き込みを1回に抑える。
        if (storage.isVisitedSinceLastBackup(worldUuid)) return
        runCatching {
            storage.setLastVisitAt(worldUuid, System.currentTimeMillis())
        }
    }

    private fun resolveWorldUuid(player: Player): UUID? {
        MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid?.let { return it }
        if (player.world.name.startsWith("my_world.")) {
            runCatching { UUID.fromString(player.world.name.removePrefix("my_world.")) }.getOrNull()?.let { return it }
        }
        return null
    }
}
