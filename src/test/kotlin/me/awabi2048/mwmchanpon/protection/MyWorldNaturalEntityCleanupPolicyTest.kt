package me.awabi2048.mwmchanpon.protection

import org.bukkit.event.entity.CreatureSpawnEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyWorldNaturalEntityCleanupPolicyTest {
    @Test
    fun `natural non-persistent entities are removed`() {
        assertTrue(
            MyWorldNaturalEntityCleanupPolicy.shouldRemove(
                reason = CreatureSpawnEvent.SpawnReason.NATURAL,
                persistent = false,
                hasCustomName = false,
                tamed = false,
            )
        )
    }

    @Test
    fun `player-involved individuals are preserved`() {
        // 命名・懐柔・永続化された個体はプレイヤー関与物として残します。
        listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
        ).forEach { (persistent, named, tamed) ->
            assertFalse(
                MyWorldNaturalEntityCleanupPolicy.shouldRemove(
                    CreatureSpawnEvent.SpawnReason.NATURAL, persistent, named, tamed,
                )
            )
        }
    }

    @Test
    fun `non-natural reasons are preserved`() {
        // 村人(DEFAULT)・鉄ゴーレム(VILLAGE_DEFENSE)・繁殖(BREEDING)・卵・コマンド等は残します。
        listOf(
            CreatureSpawnEvent.SpawnReason.DEFAULT,
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            null,
        ).forEach { reason ->
            assertFalse(
                MyWorldNaturalEntityCleanupPolicy.shouldRemove(
                    reason, persistent = false, hasCustomName = false, tamed = false,
                ),
                "reason=$reason",
            )
        }
    }
}
