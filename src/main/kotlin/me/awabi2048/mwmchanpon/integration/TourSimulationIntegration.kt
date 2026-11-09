package me.awabi2048.mwmchanpon.integration

import me.awabi2048.chanponutilities.api.TourSimulationChangedEvent
import me.awabi2048.chanponutilities.api.TourSimulationProvider
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.plugin.ServicePriority
import java.util.UUID

/** Chanponの保存状態をUtilitiesの公開サービスへ渡し、設定変更時にだけ更新します。 */
class TourSimulationIntegration(private val plugin: MWMChanponPlugin, private val storage: ChanponGlobalStorage) : AutoCloseable {
    @Volatile private var activeWorlds: Set<UUID> = emptySet()
    private val provider = TourSimulationProvider { world ->
        MyWorldManagerApi.getWorldRepository()?.findByWorldKey(world.key.toString())?.uuid in activeWorlds
    }

    fun start() {
        activeWorlds = storage.simulatedWorldUuids()
        plugin.server.servicesManager.register(TourSimulationProvider::class.java, provider, plugin, ServicePriority.Normal)
        plugin.server.pluginManager.callEvent(TourSimulationChangedEvent())
    }

    fun refresh() {
        activeWorlds = storage.simulatedWorldUuids()
        plugin.server.pluginManager.callEvent(TourSimulationChangedEvent())
    }

    override fun close() {
        plugin.server.servicesManager.unregister(TourSimulationProvider::class.java, provider)
        plugin.server.pluginManager.callEvent(TourSimulationChangedEvent())
    }
}
