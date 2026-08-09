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
    /** テスト可能なゲームルール契約です。実適用時は Paper の型付き API を使います。 */
    val gameRuleAssignments: List<GameRuleAssignment> = listOf(
        GameRuleAssignment("keepInventory", "true"),
        GameRuleAssignment("randomTickSpeed", "0"),
        GameRuleAssignment("doVinesSpread", "false"),
        GameRuleAssignment("tntExplodes", "false"),
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

    fun applyGameRules(world: World) {
        // ゲームルールは管理対象の不変条件として、同期時に必ず指定値へ戻します。
        world.setGameRule(GameRules.KEEP_INVENTORY, true)
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0)
        world.setGameRule(GameRules.SPREAD_VINES, false)
        world.setGameRule(GameRules.TNT_EXPLODES, false)
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
