package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.protection.MyWorldNaturalEntityCleanupPolicy
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.WorldLoadEvent

/**
 * マイワールドの自然湧き由来エンティティを、発生・読み込み・遅延再確認の複数経路で除去します。
 *
 * CreatureSpawnEvent キャンセルだけでは保存済みエンティティへ効かず、EntitiesLoadEvent だけでは
 * 実行時の湧き（行商人等）を止められないため、両経路を組み合わせています。
 */
class MyWorldNaturalEntityCleanupListener(
    private val plugin: MWMChanponPlugin,
) : Listener {

    /** 実行時の自然湧きは発生自体を抑止します。命名・懐柔・永続の個体は NATURAL では発生しません。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.NATURAL) return
        if (!plugin.isConfigurationMigrationCurrent()) return
        if (!isManagedWorld(event.entity.world)) return
        event.isCancelled = true
    }

    /** 保存済みエンティティはスポーンイベントを通らないため、エンティティ部のロード時に削除します。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        if (!isManagedWorld(event.world)) return
        sweep(event.entities)

        // 一部のエンティティはロード完了の直後にワールドへ追加されるため、次tickで再確認します。
        val chunk = event.chunk
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (chunk.isEntitiesLoaded) sweep(chunk.entities.toList())
        }, RECHECK_DELAY_TICKS)
    }

    /** プラグイン有効化より前に読み込まれた既存個体を一括削除し、遅れて追加される個体も拾います。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onWorldLoad(event: WorldLoadEvent) {
        if (!plugin.isConfigurationMigrationCurrent()) return
        if (!isManagedWorld(event.world)) return
        sweep(event.world.entities)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            sweep(event.world.entities)
        }, RECHECK_DELAY_TICKS)
    }

    private fun isManagedWorld(world: World): Boolean =
        MyWorldManagerApi.getWorldRepository()?.findByWorldName(world.name) != null

    private fun sweep(entities: Collection<Entity>) {
        entities.filter(::isRemovalTarget).forEach(Entity::remove)
    }

    private fun isRemovalTarget(entity: Entity): Boolean {
        // 非生物（アーマースタンド等）は spawnReason が NATURAL にならないため、念のため生物へ限定します。
        if (entity !is LivingEntity || entity is Player) return false
        return MyWorldNaturalEntityCleanupPolicy.shouldRemove(
            reason = entity.entitySpawnReason,
            persistent = entity.isPersistent,
            hasCustomName = entity.customName() != null,
            tamed = (entity as? Tameable)?.isTamed == true,
        )
    }

    companion object {
        private const val RECHECK_DELAY_TICKS = 20L
    }
}
