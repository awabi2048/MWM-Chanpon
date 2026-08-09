package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import java.util.logging.Level
import java.util.logging.Logger
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.World

/**
 * マイワールドの安全既定値を、ワールドのライフサイクルに合わせて冪等に同期します。
 *
 * 同期失敗を呼び出し元へ投げないことが重要です。特に MwmWorldCreatedEvent は MWM の作成処理中に
 * 発火するため、WorldGuard の保存失敗でワールド作成全体をロールバックさせないようにします。
 */
class MyWorldSafetyDefaultsService(
    private val logger: Logger,
) {
    fun syncAll(worlds: Iterable<WorldData>): Int = worlds.count(::sync)

    fun sync(worldData: WorldData): Boolean {
        val worldName = worldName(worldData)
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            // MWMは未使用ワールドをアンロードするため、起動時の未ロードは異常ではありません。
            // 実際のロード時にはリスナーが再度同期します。
            logger.fine("マイワールドは未ロードのため安全既定値を保留します: $worldName")
            return false
        }

        return try {
            // ゲームルールは既存値を管理者設定として判別できないため、MWM-Chanponの不変条件へ収束させます。
            MyWorldSafetyDefaultsPolicy.applyGameRules(world)
            syncWorldGuard(world)
        } catch (exception: Exception) {
            // 作成イベントのトランザクションへ例外を返さず、次回ロード・起動走査で再試行できる状態を保ちます。
            logger.log(
                Level.WARNING,
                "マイワールドの安全既定値の同期に失敗しました: uuid=${worldData.uuid}, world=$worldName",
                exception,
            )
            false
        }
    }

    private fun syncWorldGuard(world: World): Boolean {
        val regionManager = WorldGuard.getInstance()
            .platform
            .regionContainer
            .get(BukkitAdapter.adapt(world))
            ?: run {
                logger.warning("WorldGuard region data is unavailable for ${world.name}")
                return false
            }

        val region = regionManager.getRegion(GLOBAL_REGION_ID)
            ?: GlobalProtectedRegion(GLOBAL_REGION_ID).also(regionManager::addRegion)
        if (MyWorldSafetyDefaultsPolicy.applyWorldGuardDefaults(region)) {
            // 変更がない場合は不要なファイル書き込みを避けます。
            regionManager.saveChanges()
        }
        return true
    }

    companion object {
        private const val GLOBAL_REGION_ID = "__global__"

        fun worldName(worldData: WorldData): String =
            worldData.customWorldName ?: "my_world.${worldData.uuid}"
    }
}
