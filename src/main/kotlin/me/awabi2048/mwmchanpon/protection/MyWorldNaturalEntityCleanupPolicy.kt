package me.awabi2048.mwmchanpon.protection

import org.bukkit.event.entity.CreatureSpawnEvent

/**
 * マイワールドから自然湧き由来のエンティティを除去する判定を一か所へ集約します。
 *
 * 対象は spawnReason=NATURAL の LivingEntity です。命名・懐柔・永続化された個体は
 * プレイヤー関与物として残します。村人・鉄ゴーレム・繁殖個体・スポナー・コマンド由来は
 * NATURAL 以外の理由が記録されるため対象外です。
 */
internal object MyWorldNaturalEntityCleanupPolicy {

    /**
     * 削除対象かを判定します。エンティティ種別ではなく発生理由とプレイヤー関与状態だけで決めます。
     */
    fun shouldRemove(
        reason: CreatureSpawnEvent.SpawnReason?,
        persistent: Boolean,
        hasCustomName: Boolean,
        tamed: Boolean,
    ): Boolean =
        reason == CreatureSpawnEvent.SpawnReason.NATURAL && !persistent && !hasCustomName && !tamed
}
