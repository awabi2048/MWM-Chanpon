package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
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

        // __global__ では PASSTHROUGH の評価が早期終了するため passthrough-group は
        // WorldGuard に評価されません。建築可否は build フラグ＋グループで表現します。
        // __global__ では ALLOW 系フラグが無効化される一方、DENY とグループ指定は
        // 通常通り有効なため、「拒否したい側」を DENY ＋対象グループで書きます。
        when (effectiveProtectionMode(worldData)) {
            ChanponWorldProtectionMode.OWNER_ONLY -> {
                region.setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
                region.setFlag(Flags.BUILD, StateFlag.State.DENY)
                region.setFlag(Flags.BUILD.regionGroupFlag, RegionGroup.NON_OWNERS)
            }
            ChanponWorldProtectionMode.MEMBERS -> {
                region.setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
                region.setFlag(Flags.BUILD, StateFlag.State.DENY)
                region.setFlag(Flags.BUILD.regionGroupFlag, RegionGroup.NON_MEMBERS)
            }
            // 「誰でも」は明示的な passthrough=ALLOW で表現します。
            // 建築は全員へ開放するため build フラグとそのグループ指定をクリアします。
            ChanponWorldProtectionMode.PUBLIC -> {
                region.setFlag(Flags.PASSTHROUGH, StateFlag.State.ALLOW)
                region.setFlag(Flags.BUILD, null)
                region.setFlag(Flags.BUILD.regionGroupFlag, null)
            }
        }
        // 全モードで passthrough-group をクリアします。
        // __global__ では評価されない死んだ設定であり、残すと regions.yml の意図を誤読させます。
        region.setFlag(Flags.PASSTHROUGH.regionGroupFlag, null)

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
