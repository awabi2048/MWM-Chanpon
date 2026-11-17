package me.awabi2048.mwmchanpon.service

import java.util.UUID
import me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StandaloneExportPlanTest {
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `統合方式では選択順を保持し個別方式ではセーブを分離する`() {
        val worlds = listOf(ExportWorldIdentity(first, "代表"), ExportWorldIdentity(second, "追加"))
        assertEquals(listOf(first, second), StandaloneExportPlan.create(2026, worlds, false).archives.single().worldUuids)
        val individual = StandaloneExportPlan.create(2026, worlds, true)
        assertEquals(listOf(listOf(first), listOf(second)), individual.archives.map { it.worldUuids })
        assertTrue(individual.archives.all { it.fileName.endsWith(".zip") })
    }

    @Test
    fun `同名ワールドも展開時に衝突させない`() {
        val plan = StandaloneExportPlan.create(2026, listOf(ExportWorldIdentity(first, "Test"), ExportWorldIdentity(second, "test")), true)
        assertEquals(2, plan.archives.map { it.fileName.lowercase() }.distinct().size)
        assertTrue(plan.archives[0].fileName.contains(first.toString()))
    }

    @Test
    fun `不正な出力計画を公開前に拒否する`() {
        assertThrows(IllegalArgumentException::class.java) { StandaloneExportPlan.create(2026, emptyList(), true) }
        assertThrows(IllegalArgumentException::class.java) {
            StandaloneExportPlan.create(2026, listOf(ExportWorldIdentity(first, "a"), ExportWorldIdentity(first, "b")), true)
        }
    }

    private fun portal(target: UUID?, targetKey: String?) = ApiPortalSnapshot(
        UUID.randomUUID(), "minecraft:first", 0, 64, 0, target, targetKey, false, 0, first, "", null,
        "portal", null, null, null, null, null, null,
    )

    @Test
    fun `同じセーブ内のUUIDと外部キーだけを移動先へ解決する`() {
        val combined = mapOf(first to "minecraft:first", second to "minecraft:second")
        assertEquals(second, StandalonePortalTargets.resolve(portal(second, null), combined))
        assertEquals(second, StandalonePortalTargets.resolve(portal(null, "minecraft:second"), combined))
        assertNull(StandalonePortalTargets.resolve(portal(null, "minecraft:missing"), combined))
        val individual = mapOf(first to "minecraft:first")
        assertNull(StandalonePortalTargets.resolve(portal(second, null), individual))
        assertNull(StandalonePortalTargets.resolve(portal(null, "minecraft:second"), individual))
        assertEquals(first, StandalonePortalTargets.resolve(portal(first, null), individual))
    }
}
