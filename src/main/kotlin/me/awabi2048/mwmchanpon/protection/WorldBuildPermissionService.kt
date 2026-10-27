package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import java.util.UUID
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit

class WorldBuildPermissionService(
    private val storage: ChanponWorldStorage,
    private val logger: Logger,
    private val isSpawnWorld: (UUID) -> Boolean = { false },
    private val publicWorldFawePermissionService: PublicWorldFawePermissionService? = null,
) {
    private var configurationAvailable: () -> Boolean = { true }

    fun setConfigurationAvailability(gate: () -> Boolean) {
        configurationAvailable = gate
    }

    /**
     * 保護モードの実効値です。
     * スポーンワールドは指定時に WorldGuard 側へオーナー限定の既定が補完されるため、
     * protection.mode が未記録の間は OWNER_ONLY として扱い、表示・同期・権限判定を
     * 同じ実効値へ揃えます。
     */
    fun effectiveProtectionMode(worldData: WorldData): ChanponWorldProtectionMode =
        storage.getProtectionModeOrNull(worldData.uuid)
            ?: if (isSpawnWorld(worldData.uuid)) {
                ChanponWorldProtectionMode.OWNER_ONLY
            } else {
                ChanponWorldProtectionMode.MEMBERS
            }

    fun sync(worldData: WorldData) {
        if (!configurationAvailable()) return
        if (!storage.hasCurrentWorldData(worldData.uuid)) return
        val worldName = worldData.customWorldName ?: "my_world.${worldData.uuid}"
        val mode = effectiveProtectionMode(worldData)
        // Sync the world-context permission even when its chunks are not loaded. LuckPerms is
        // independent from WorldGuard's RegionManager availability and must converge on the same
        // persisted protection mode during startup, mode changes, and restore.
        publicWorldFawePermissionService?.sync(worldName, mode)
        val bukkitWorld = Bukkit.getWorld(worldName) ?: return
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

        val accessRegion = WorldBuildRegionPolicy.configure(
            region, worldData, mode, bukkitWorld.minHeight, bukkitWorld.maxHeight,
        )
        if (accessRegion == null) {
            regionManager.removeRegion(
                WorldBuildRegionPolicy.REGION_ID,
                com.sk89q.worldguard.protection.managers.RemovalStrategy.UNSET_PARENT_IN_CHILDREN,
            )
        } else {
            // 所属は追加だけでなく完全置換し、脱退・降格・所有権移譲の残留権限を除去します。
            regionManager.addRegion(accessRegion)
        }

        regionManager.saveChanges()
        recordSpawnProtectionMode(worldData)
    }

    /**
     * スポーン既定でオーナー限定となっているのに protection.mode が未記録のワールドへ
     * 実効値を記録し、設定ファイル・画面表示・WorldGuard フラグを同じ意味へ収束させます。
     * 記録は一度だけ行われ、以後は明示値として扱われます。
     */
    private fun recordSpawnProtectionMode(worldData: WorldData) {
        if (storage.getProtectionModeOrNull(worldData.uuid) != null) return
        if (!isSpawnWorld(worldData.uuid)) return
        runCatching {
            storage.setProtectionMode(worldData.uuid, ChanponWorldProtectionMode.OWNER_ONLY)
        }.onFailure { error ->
            logger.warning(
                "Failed to record spawn world protection mode for ${worldData.uuid}: ${error.message}"
            )
        }
    }

    companion object {
        private const val GLOBAL_REGION_ID = "__global__"
    }
}
