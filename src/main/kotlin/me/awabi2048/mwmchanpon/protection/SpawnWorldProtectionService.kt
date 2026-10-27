package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit

/** Chanponスポーンの __global__ に、ユーザーが後から変更できる初期値だけを補完する。 */
class SpawnWorldProtectionService {
    data class DefaultsSnapshot(
        val worldUuid: java.util.UUID,
        val regionExisted: Boolean,
        val otherExplosion: StateFlag.State?,
        val chestAccess: StateFlag.State?,
        val pistons: StateFlag.State?,
        val potionSplash: StateFlag.State?,
        val use: StateFlag.State?,
        val creeperExplosion: StateFlag.State?,
        val destroyVehicle: StateFlag.State?,
        val interact: StateFlag.State?,
        val ride: StateFlag.State?,
        val placeVehicle: StateFlag.State?,
    )

    /** スポーン指定で補完するWorldGuard既定値を、復元可能な状態として取得します。 */
    fun captureDefaults(worldData: WorldData): DefaultsSnapshot? {
        val manager = regionManager(worldData) ?: return null
        val region = manager.getRegion(GLOBAL_REGION_ID)
        return DefaultsSnapshot(
            worldData.uuid,
            region != null,
            region?.getFlag(Flags.OTHER_EXPLOSION),
            region?.getFlag(Flags.CHEST_ACCESS),
            region?.getFlag(Flags.PISTONS),
            region?.getFlag(Flags.POTION_SPLASH),
            region?.getFlag(Flags.USE),
            region?.getFlag(Flags.CREEPER_EXPLOSION),
            region?.getFlag(Flags.DESTROY_VEHICLE),
            region?.getFlag(Flags.INTERACT),
            region?.getFlag(Flags.RIDE),
            region?.getFlag(Flags.PLACE_VEHICLE),
        )
    }

    fun defaultsApplied(snapshot: DefaultsSnapshot): DefaultsSnapshot = snapshot.copy(
        regionExisted = true,
        otherExplosion = snapshot.otherExplosion ?: StateFlag.State.DENY,
        chestAccess = snapshot.chestAccess ?: StateFlag.State.ALLOW,
        pistons = snapshot.pistons ?: StateFlag.State.ALLOW,
        potionSplash = snapshot.potionSplash ?: StateFlag.State.DENY,
        use = snapshot.use ?: StateFlag.State.ALLOW,
        creeperExplosion = snapshot.creeperExplosion ?: StateFlag.State.DENY,
        destroyVehicle = snapshot.destroyVehicle ?: StateFlag.State.ALLOW,
        interact = snapshot.interact ?: StateFlag.State.ALLOW,
        ride = snapshot.ride ?: StateFlag.State.ALLOW,
        placeVehicle = snapshot.placeVehicle ?: StateFlag.State.ALLOW,
    )

    fun restoreDefaults(worldData: WorldData, snapshot: DefaultsSnapshot): Boolean {
        if (worldData.uuid != snapshot.worldUuid) return false
        val manager = regionManager(worldData) ?: return false
        if (!snapshot.regionExisted) {
            manager.removeRegion(GLOBAL_REGION_ID)
            manager.saveChanges()
            return true
        }
        val region = manager.getRegion(GLOBAL_REGION_ID) ?: GlobalProtectedRegion(GLOBAL_REGION_ID).also(manager::addRegion)
        region.setFlag(Flags.OTHER_EXPLOSION, snapshot.otherExplosion)
        region.setFlag(Flags.CHEST_ACCESS, snapshot.chestAccess)
        region.setFlag(Flags.PISTONS, snapshot.pistons)
        region.setFlag(Flags.POTION_SPLASH, snapshot.potionSplash)
        region.setFlag(Flags.USE, snapshot.use)
        region.setFlag(Flags.CREEPER_EXPLOSION, snapshot.creeperExplosion)
        region.setFlag(Flags.DESTROY_VEHICLE, snapshot.destroyVehicle)
        region.setFlag(Flags.INTERACT, snapshot.interact)
        region.setFlag(Flags.RIDE, snapshot.ride)
        region.setFlag(Flags.PLACE_VEHICLE, snapshot.placeVehicle)
        manager.saveChanges()
        return true
    }

    fun applyDefaults(worldData: WorldData): Boolean {
        val manager = regionManager(worldData) ?: return false
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
        // 建築許可はWorldBuildPermissionServiceが専用リージョンで管理します。
        setDefault(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY)
        setDefault(Flags.DESTROY_VEHICLE, StateFlag.State.ALLOW)
        setDefault(Flags.INTERACT, StateFlag.State.ALLOW)
        setDefault(Flags.RIDE, StateFlag.State.ALLOW)
        setDefault(Flags.PLACE_VEHICLE, StateFlag.State.ALLOW)

        if (changed) manager.saveChanges()
        return true
    }

    private fun regionManager(worldData: WorldData) = Bukkit.getWorld(
        worldData.customWorldName ?: "my_world.${worldData.uuid}",
    )?.let { world ->
        WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))
    }

    private companion object {
        const val GLOBAL_REGION_ID = "__global__"
    }
}
