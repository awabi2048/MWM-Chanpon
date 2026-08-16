package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit

class WorldBuildPermissionService(
    private val storage: ChanponWorldStorage,
    private val logger: Logger
) {
    private var configurationAvailable: () -> Boolean = { true }

    fun setConfigurationAvailability(gate: () -> Boolean) {
        configurationAvailable = gate
    }

    fun sync(worldData: WorldData) {
        if (!configurationAvailable()) return
        if (!storage.hasCurrentWorldData(worldData.uuid)) return
        val bukkitWorld = Bukkit.getWorld(worldData.customWorldName ?: "my_world.${worldData.uuid}") ?: return
        val regionManager = WorldGuard.getInstance()
            .platform
            .regionContainer
            .get(BukkitAdapter.adapt(bukkitWorld))

        if (regionManager == null) {
            logger.warning("WorldGuard region data is unavailable for ${bukkitWorld.name}")
            return
        }

        val region = regionManager.getRegion(GLOBAL_REGION_ID)
            ?: GlobalProtectedRegion(GLOBAL_REGION_ID).also(regionManager::addRegion)

        when (storage.getProtectionMode(worldData.uuid)) {
            ChanponWorldProtectionMode.OWNER_ONLY -> {
                region.setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
                region.setFlag(Flags.PASSTHROUGH.regionGroupFlag, RegionGroup.NON_OWNERS)
            }
            ChanponWorldProtectionMode.MEMBERS,
            ChanponWorldProtectionMode.PASSWORD -> {
                region.setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
                region.setFlag(Flags.PASSTHROUGH.regionGroupFlag, RegionGroup.NON_MEMBERS)
            }
            ChanponWorldProtectionMode.PUBLIC -> {
                region.setFlag(Flags.PASSTHROUGH, null)
                region.setFlag(Flags.PASSTHROUGH.regionGroupFlag, null)
            }
        }

        regionManager.saveChanges()
    }

    companion object {
        private const val GLOBAL_REGION_ID = "__global__"
    }
}
