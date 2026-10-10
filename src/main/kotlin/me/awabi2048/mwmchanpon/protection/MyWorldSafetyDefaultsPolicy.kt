package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import org.bukkit.GameRules
import org.bukkit.World

/**
 * MWM-Chanpon がマイワールドへ適用する安全既定値を一か所へ集約します。
 *
 * ゲームルールには「未設定」が存在しないため、対象の値は同期のたびに指定値へ収束させます。
 * WorldGuard は既存の明示値を尊重し、未設定の対象だけを DENY で補完します。
 */
internal object MyWorldSafetyDefaultsPolicy {
    /**
     * ランダムティック停止がオンのときに適用するティック速度です。
     * 環境設定でオフにした場合は vanilla 既定値へ戻します。
     */
    const val RANDOM_TICK_SPEED_STOPPED = 0
    const val RANDOM_TICK_SPEED_RUNNING = 3

    /** 停止設定から適用するティック速度を導出します。 */
    fun randomTickSpeedFor(stopped: Boolean): Int =
        if (stopped) RANDOM_TICK_SPEED_STOPPED else RANDOM_TICK_SPEED_RUNNING

    /** テスト可能なゲームルール契約です。実適用時は Paper の型付き API を使います。 */
    val gameRuleAssignments: List<GameRuleAssignment> = listOf(
        GameRuleAssignment("keepInventory", "true"),
        GameRuleAssignment("doVinesSpread", "false"),
        GameRuleAssignment("tntExplodes", "false"),
        GameRuleAssignment("fire_spread_radius_around_player", "0"),
    )

    /** WorldGuard 7.0.14 で利用できる爆発・破壊・成長抑制フラグです。 */
    val worldGuardDenyFlags: List<StateFlag> = listOf(
        Flags.TNT,
        Flags.CREEPER_EXPLOSION,
        Flags.GHAST_FIREBALL,
        Flags.OTHER_EXPLOSION,
        Flags.FIREWORK_DAMAGE,
        Flags.ENDERDRAGON_BLOCK_DAMAGE,
        Flags.WITHER_DAMAGE,
        Flags.WIND_CHARGE_BURST,
        Flags.BREEZE_WIND_CHARGE,
        Flags.VINE_GROWTH,
        Flags.CORAL_FADE,
    )

    /**
     * @param randomTickSpeed ワールド単位の設定から導出したティック速度。
     *        固定の不変条件ではなくワールド環境設定として扱います。
     */
    fun applyGameRules(world: World, randomTickSpeed: Int) {
        // ゲームルールは管理対象の不変条件として、同期時に必ず指定値へ戻します。
        world.setGameRule(GameRules.KEEP_INVENTORY, true)
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, randomTickSpeed)
        world.setGameRule(GameRules.SPREAD_VINES, false)
        world.setGameRule(GameRules.TNT_EXPLODES, false)
        // 1.21.11 以降 doFireTick は削除済みで、延焼はプレイヤー周辺の半径指定へ置き換わっている。
        // 0 は延焼・燃え尽き双方を停止する（点火済みの炎は消えずに残る）。
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0)
    }

    /**
     * 各マイワールドの __global__ だけを対象にします。
     *
     * passthrough、所有者・メンバー、既存リージョン、既に明示されたフラグ値には触れません。
     */
    fun applyWorldGuardDefaults(region: ProtectedRegion): Boolean {
        var changed = false
        worldGuardDenyFlags.forEach { flag ->
            if (region.getFlag(flag) == null) {
                region.setFlag(flag, StateFlag.State.DENY)
                changed = true
            }
        }
        return changed
    }
}

internal data class GameRuleAssignment(
    val key: String,
    val value: String,
)
