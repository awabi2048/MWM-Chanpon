package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.managers.RemovalStrategy
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit

class SubmittedWorldProtectionService(
    private val storage: ChanponWorldStorage,
    private val logger: Logger,
    private val autoRollbackEnabled: () -> Boolean = { false }
) {
    private var configurationAvailable: () -> Boolean = { true }

    fun setConfigurationAvailability(gate: () -> Boolean) {
        configurationAvailable = gate
    }

    fun syncAll(worlds: Iterable<WorldData>) {
        worlds.forEach { sync(it) }
    }

    fun sync(worldData: WorldData) {
        if (!configurationAvailable()) return
        // 破損・旧形式をproduction=falseとして扱い、保護を解除しないようにします。
        if (!storage.hasCurrentWorldData(worldData.uuid)) return
        if (!storage.isProductionWorld(worldData.uuid)) {
            setProtected(worldData, false)
            return
        }

        val reasons = protectionReasons(worldData)
        setProtected(worldData, reasons.isNotEmpty())
    }

    /**
     * 設定の移行中は保護状態を確定できないため、変更操作側が安全側に倒れるよう保護扱いにする。
     * sync() は移行前の設定を上書きしないよう停止しているため、既存の保護状態も維持される。
     */
    fun isProtected(worldData: WorldData): Boolean =
        !configurationAvailable() ||
            !storage.hasCurrentWorldData(worldData.uuid) ||
            protectionReasons(worldData).isNotEmpty()

    fun protectionReasons(worldData: WorldData): Set<ProtectionReason> {
        if (!storage.hasCurrentWorldData(worldData.uuid)) return emptySet()
        if (!storage.isProductionWorld(worldData.uuid)) return emptySet()
        val reasons = linkedSetOf<ProtectionReason>()
        if (autoRollbackEnabled()) {
            reasons.add(ProtectionReason.AUTO_ROLLBACK)
        }
        val state = try {
            storage.getSubmissionState(worldData.uuid)
        } catch (ex: IllegalStateException) {
            if (reasons.isEmpty()) {
                logger.warning("Cannot sync submitted protection because submission state is invalid: ${worldData.uuid}")
            }
            return reasons
        }
        when (state) {
            SubmissionState.SUBMITTED -> reasons.add(ProtectionReason.SUBMITTED)
            SubmissionState.COMPLETED -> reasons.add(ProtectionReason.COMPLETED)
            SubmissionState.UNSUBMITTED -> Unit
        }
        return reasons
    }

    fun setProtected(worldData: WorldData, protected: Boolean) {
        val bukkitWorld = Bukkit.getWorld(worldData.customWorldName ?: "my_world.${worldData.uuid}") ?: return
        val regionManager = WorldGuard.getInstance()
            .platform
            .regionContainer
            .get(BukkitAdapter.adapt(bukkitWorld))

        if (regionManager == null) {
            logger.warning("WorldGuard region data is unavailable for ${bukkitWorld.name}")
            return
        }

        if (protected) {
            val region = regionManager.getRegion(REGION_ID) ?: createWorldRegion(bukkitWorld)
            region.priority = PRIORITY
            region.setFlag(Flags.BUILD, StateFlag.State.DENY)
            regionManager.addRegion(region)
        } else if (regionManager.hasRegion(REGION_ID)) {
            regionManager.removeRegion(REGION_ID, RemovalStrategy.UNSET_PARENT_IN_CHILDREN)
        }

        regionManager.saveChanges()
    }

    private fun createWorldRegion(world: org.bukkit.World): ProtectedCuboidRegion {
        val min = BlockVector3.at(WORLD_MIN, world.minHeight, WORLD_MIN)
        val max = BlockVector3.at(WORLD_MAX, world.maxHeight - 1, WORLD_MAX)
        return ProtectedCuboidRegion(REGION_ID, min, max)
    }

    companion object {
        private const val REGION_ID = "chanpon_submitted_lock"
        private const val PRIORITY = 1_000_000
        private const val WORLD_MIN = -30_000_000
        private const val WORLD_MAX = 30_000_000
    }
}

enum class ProtectionReason {
    SUBMITTED,
    COMPLETED,
    AUTO_ROLLBACK
}
