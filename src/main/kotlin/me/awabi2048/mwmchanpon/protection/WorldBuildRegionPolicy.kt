package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.myworldmanager.model.WorldData

/**
 * 建築モードをWorldGuardの通常リージョンへ変換します。
 *
 * __global__ のBUILDはグループ絞り込みより先に評価されるため使用しません。
 * MWMが所有するグローバル所属や環境フラグは維持し、建築だけを専用領域へ分離します。
 */
internal object WorldBuildRegionPolicy {
    const val REGION_ID = "chanpon_build_access"
    // 提出ロック（1,000,000）を必ず優先し、通常の建築許可で提出保護を解除しません。
    const val PRIORITY = 100_000

    fun configure(
        global: ProtectedRegion,
        worldData: WorldData,
        mode: ChanponWorldProtectionMode,
        minHeight: Int,
        maxHeight: Int,
    ): ProtectedCuboidRegion? {
        // 旧方式の残留拒否と、所属によるグローバルの暗黙拒否を取り除きます。
        // 爆発・PvP等の環境設定と、MWMのオーナー・メンバー領域には触れません。
        global.setFlag(Flags.BUILD, null)
        global.setFlag(Flags.BUILD.regionGroupFlag, null)
        global.setFlag(Flags.PASSTHROUGH, StateFlag.State.ALLOW)
        global.setFlag(Flags.PASSTHROUGH.regionGroupFlag, null)
        if (mode == ChanponWorldProtectionMode.PUBLIC) return null

        return ProtectedCuboidRegion(
            REGION_ID,
            BlockVector3.at(-30_000_000, minHeight, -30_000_000),
            BlockVector3.at(30_000_000, maxHeight - 1, 30_000_000),
        ).apply {
            priority = PRIORITY
            owners.addPlayer(worldData.owner)
            (worldData.members + worldData.moderators).distinct()
                .filter { it != worldData.owner }.forEach(members::addPlayer)
            setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
            setFlag(Flags.BUILD, StateFlag.State.DENY)
            setFlag(Flags.BUILD.regionGroupFlag,
                if (mode == ChanponWorldProtectionMode.OWNER_ONLY) RegionGroup.NON_OWNERS
                else RegionGroup.NON_MEMBERS)
        }
    }
}
