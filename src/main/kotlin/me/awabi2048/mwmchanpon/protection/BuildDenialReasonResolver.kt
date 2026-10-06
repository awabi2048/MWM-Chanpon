package me.awabi2048.mwmchanpon.protection

import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponProtectionKeys
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.block.Block
import org.bukkit.entity.Player

/**
 * キャンセル済みの建築イベントに対して表示する拒否理由です。
 * 順序は「解消に必要な権限の高さ」を表し、複数条件が同時に成立するときは
 * この enum の先に定義された理由だけをアクションバーへ表示します。
 *
 * 権限の階層は、サーバー全体・ライフサイクル操作（管理者）を最上位とし、
 * ワールド単位の保護モード（オーナー・モデレータ）をその下位、
 * 権限を要しない位置制約を最下位としています。
 */
enum class BuildDenialReason(val messageKey: LocalizationKey<String>) {
    /** 設定移行中、またはChanpon側の現行ワールドデータが存在しない。管理者による設定修復が必要。 */
    CONFIG_UNAVAILABLE(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_MIGRATION),
    /** 本番ワールドが完成済み（COMPLETED）。管理者によるライフサイクル操作が必要。 */
    COMPLETED(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_COMPLETED),
    /** 本番ワールドが提出済み（SUBMITTED）。管理者・オーナーによる提出取り下げが必要。 */
    SUBMITTED(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_SUBMITTED),
    /** インポート済みアーカイブ。管理者によるアーカイブ解除が必要。 */
    IMPORTED_ARCHIVE(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_ARCHIVE),
    /** 本番ワールドが自動ロールバック対象。管理者による設定変更が必要。 */
    AUTO_ROLLBACK(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_AUTO_ROLLBACK),
    /** 全体の建築が停止中。管理者による許可、または個人バイパスの付与が必要。 */
    GLOBAL_BUILD_DISABLED(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_GLOBAL),
    /** 保護モードがオーナー限定。オーナー本人またはモード変更が必要。 */
    OWNER_ONLY(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_OWNER_ONLY),
    /** 保護モードがメンバー限定。オーナー・モデレータによるメンバー追加が必要。 */
    MEMBERS_ONLY(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_MEMBERS_ONLY),
    /** スポーン位置への不透明ブロック配置。位置をずらすだけでよく、権限を要しない。 */
    SPAWN_POSITION(ChanponProtectionKeys.CHANPON_PROTECTION_BUILD_DENIED_SPAWN),
}

/**
 * 建築イベントがキャンセルされた原因を、現在のワールド・プレイヤー状態から
 * 再評価して最上位の拒否理由へ解決します。
 *
 * キャンセル実施側（WorldGuardリージョン、PlayerStatePolicy、MWMスポーンガード）は
 * 理由をイベントへ残さないため、ここでは各拒否経路と同じ条件を
 * 権限の高い順に評価します。
 */
class BuildDenialReasonResolver(
    private val worldStorage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val submittedProtectionService: SubmittedWorldProtectionService,
    private val buildPermissionService: WorldBuildPermissionService,
    private val configurationAvailable: () -> Boolean,
) {
    /**
     * 拒否理由を返します。管理対象外の状態や、Chanpon側の条件で説明できない
     * キャンセル（外部プラグインの保護等）の場合はnullを返します。
     */
    fun resolve(player: Player, worldData: WorldData, block: Block, placing: Boolean): BuildDenialReason? {
        // 設定移行中・現行データ不在は他の条件を確定できないため、必ず先に評価します。
        if (!configurationAvailable() || !worldStorage.hasCurrentWorldData(worldData.uuid)) {
            return BuildDenialReason.CONFIG_UNAVAILABLE
        }
        // protectionReasonsは本番ワールドのみ非空を返すため、提出保護の適用範囲と一致します。
        val lockReasons = submittedProtectionService.protectionReasons(worldData)
        if (ProtectionReason.COMPLETED in lockReasons) return BuildDenialReason.COMPLETED
        if (ProtectionReason.SUBMITTED in lockReasons) return BuildDenialReason.SUBMITTED
        if (worldStorage.isImportedArchive(worldData.uuid)) return BuildDenialReason.IMPORTED_ARCHIVE
        if (ProtectionReason.AUTO_ROLLBACK in lockReasons) return BuildDenialReason.AUTO_ROLLBACK
        // PlayerStatePolicyの拒否条件と同じ評価です。管理者・個人バイパス所持者は
        // この経路では拒否されないため、理由から除外します。
        if (!globalStorage.isBuildingAllowed() &&
            !worldStorage.isGlobalBypassAllowed(worldData.uuid, player.uniqueId) &&
            !isAdmin(player)
        ) {
            return BuildDenialReason.GLOBAL_BUILD_DISABLED
        }
        // WorldGuard __global__ のpassthrough拒否と同じ実効モード・メンバー範囲で評価します。
        // リージョンのオーナーはworldData.owner、メンバーはmembers+moderatorsです。
        when (buildPermissionService.effectiveProtectionMode(worldData)) {
            ChanponWorldProtectionMode.OWNER_ONLY -> {
                if (player.uniqueId != worldData.owner) return BuildDenialReason.OWNER_ONLY
            }
            ChanponWorldProtectionMode.MEMBERS -> {
                if (player.uniqueId != worldData.owner &&
                    player.uniqueId !in worldData.members &&
                    player.uniqueId !in worldData.moderators
                ) {
                    return BuildDenialReason.MEMBERS_ONLY
                }
            }
            ChanponWorldProtectionMode.PUBLIC -> Unit
        }
        // MWMスポーンガードと同じ条件（足・頭の2ブロックへの不透明ブロック配置）です。
        if (placing && block.type.isOccluding && isSpawnProtectedPosition(worldData, block)) {
            return BuildDenialReason.SPAWN_POSITION
        }
        return null
    }

    private fun isSpawnProtectedPosition(worldData: WorldData, block: Block): Boolean =
        listOfNotNull(worldData.spawnPosMember, worldData.spawnPosGuest).any { spawn ->
            block.x == spawn.blockX && block.z == spawn.blockZ &&
                (block.y == spawn.blockY || block.y == spawn.blockY + 1)
        }

    private fun isAdmin(player: Player): Boolean =
        player.hasPermission("myworldmanager.admin") || player.hasPermission("mwmchanpon.admin")
}
