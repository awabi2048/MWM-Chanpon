package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.domains.DefaultDomain
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
    private val autoRollbackEnabled: () -> Boolean = { false },
    private val repairBypassEnabled: () -> Boolean = { false },
    private val tourSimulationEnabled: (java.util.UUID) -> Boolean = { false },
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
        val production = storage.isProductionWorld(worldData.uuid)
        val state = if (production) storage.getSubmissionStateOrNull(worldData.uuid) else null
        if (production && state == null) {
            logger.warning("Cannot determine submission protection because submission state is invalid: ${worldData.uuid}")
        }
        return effectiveProtectionReasons(
            production = production,
            state = state,
            autoRollback = production && autoRollbackEnabled(),
            bypass = repairBypassEnabled(),
            simulation = tourSimulationEnabled(worldData.uuid),
        )
    }

    fun setProtected(worldData: WorldData, protected: Boolean) {
        val bukkitWorld = Bukkit.getWorld(worldData.customWorldName ?: "my_world.${worldData.uuid}") ?: return
        val regionManager = WorldGuard.getInstance()
            .platform
            .regionContainer
            .get(BukkitAdapter.adapt(bukkitWorld))

        if (regionManager == null) {
            throw IllegalStateException("WorldGuard region data is unavailable for ${bukkitWorld.name}")
        }

        if (protected) {
            val region = regionManager.getRegion(REGION_ID) ?: createWorldRegion(bukkitWorld)
            region.priority = PRIORITY
            configureSubmittedLock(region)
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

/** 提出・完成・自動復元の共通ロックを、同期のたびに同じ構成へ収束させます。 */
internal fun configureSubmittedLock(region: com.sk89q.worldguard.protection.regions.ProtectedRegion) {
    // 旧 BUILD deny や手動設定が残ると、許可した装置も再び停止するため全フラグを置換します。
    region.flags = emptyMap()
    // リージョン所属による建築許可の例外を作らず、全員の建築を制限します。
    region.owners = DefaultDomain()
    region.members = DefaultDomain()
    region.setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
    region.setFlag(Flags.PASSTHROUGH.regionGroupFlag, RegionGroup.NON_OWNERS)
    listOf(Flags.OTHER_EXPLOSION, Flags.CREEPER_EXPLOSION, Flags.POTION_SPLASH,
        Flags.PLACE_VEHICLE, Flags.DESTROY_VEHICLE, Flags.RESPAWN_ANCHORS).forEach {
        region.setFlag(it, StateFlag.State.DENY)
    }
    listOf(Flags.USE, Flags.INTERACT, Flags.CHEST_ACCESS, Flags.PISTONS, Flags.RIDE).forEach {
        region.setFlag(it, StateFlag.State.ALLOW)
    }
}

enum class ProtectionReason {
    SUBMITTED,
    COMPLETED,
    AUTO_ROLLBACK,
    INVALID_STATE,
    SIMULATION
}

/** 提出状態と一時操作を合成し、判定不能時は提出保護を維持します。 */
internal fun effectiveProtectionReasons(
    production: Boolean,
    state: SubmissionState?,
    autoRollback: Boolean,
    bypass: Boolean,
    simulation: Boolean,
): Set<ProtectionReason> = buildSet {
    if (simulation) add(ProtectionReason.SIMULATION)
    if (production) {
        if (autoRollback) add(ProtectionReason.AUTO_ROLLBACK)
        when (state) {
            null -> add(ProtectionReason.INVALID_STATE)
            SubmissionState.SUBMITTED -> if (!bypass) add(ProtectionReason.SUBMITTED)
            SubmissionState.COMPLETED -> if (!bypass) add(ProtectionReason.COMPLETED)
            SubmissionState.UNSUBMITTED -> Unit
        }
    }
}
