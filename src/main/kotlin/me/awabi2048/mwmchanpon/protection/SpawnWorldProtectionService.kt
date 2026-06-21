package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit

/** Chanponスポーンの __global__ に、ユーザーが後から変更できる初期値だけを補完する。 */
class SpawnWorldProtectionService {
    fun applyDefaults(worldData: WorldData): Boolean {
        val world = Bukkit.getWorld(worldData.customWorldName ?: "my_world.${worldData.uuid}") ?: return false
        val manager = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world)) ?: return false
        val region = manager.getRegion(GLOBAL_REGION_ID) ?: GlobalProtectedRegion(GLOBAL_REGION_ID).also(manager::addRegion)
        var changed = false

        fun setDefault(flag: com.sk89q.worldguard.protection.flags.StateFlag, value: StateFlag.State) {
            if (region.getFlag(flag) == null) {
                region.setFlag(flag, value)
                changed = true
            }
        }

        setDefault(Flags.OTHER_EXPLOSION, StateFlag.State.DENY)
        setDefault(Flags.CHEST_ACCESS, StateFlag.State.ALLOW)
        setDefault(Flags.PISTONS, StateFlag.State.ALLOW)
        setDefault(Flags.POTION_SPLASH, StateFlag.State.DENY)
        setDefault(Flags.USE, StateFlag.State.ALLOW)
        setDefault(Flags.PASSTHROUGH, StateFlag.State.DENY)
        if (region.getFlag(Flags.PASSTHROUGH.regionGroupFlag) == null) {
            region.setFlag(Flags.PASSTHROUGH.regionGroupFlag, RegionGroup.NON_OWNERS)
            changed = true
        }
        setDefault(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY)
        setDefault(Flags.DESTROY_VEHICLE, StateFlag.State.ALLOW)
        setDefault(Flags.INTERACT, StateFlag.State.ALLOW)
        setDefault(Flags.RIDE, StateFlag.State.ALLOW)
        setDefault(Flags.PLACE_VEHICLE, StateFlag.State.ALLOW)

        if (changed) manager.saveChanges()
        return true
    }

    private companion object {
        const val GLOBAL_REGION_ID = "__global__"
    }
}
