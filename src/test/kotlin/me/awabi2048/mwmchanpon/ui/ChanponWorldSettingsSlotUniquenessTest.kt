package me.awabi2048.mwmchanpon.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 設定メニュー完成時の slot 一意性を保証するための回帰テスト。
 *
 * 背景：非オーナー閲覧時にツアー項目（slot 47）とフッター左操作が同じ 47 になり、
 * `InventoryMenuView` の `menu element slots must be unique` で描画全体が失敗した。
 * helper 単体の値検証だけでは組み合わせ衝突を検出できないため、画面モードごとの
 * 完成 slot 集合で重複がないことを検証する。
 */
class ChanponWorldSettingsSlotUniquenessTest {
    // 内容領域の固定 slot（ChanponWorldSettingsMenu.renderMain の定義と対応させる）。
    private val infoSlot = 19
    private val iconSlot = 20
    private val spawnSlot = 21
    private val announcementSlot = 28
    private val notificationSlot = 29
    private val tourSlot = 47
    private val expansionSlot = 23
    private val publishSlot = 24
    private val memberSlot = 25
    private val environmentSlot = 32
    private val criticalSlot = 33
    private val worldInfoSlot = 4

    // 戻るボタンは CC-System の GuiLayoutService に準拠する（backSlot54=49、backSlot45=40）。
    // ChanponGuiElements.backSlot は実行時に CCSystem API を必要とするため、 unit test では期待値を直接扱う。
    private val backSlot54 = 49
    private val backSlot45 = 40

    @Test
    fun `非オーナーでもツアーとフッター左操作は衝突しない`() {
        val linked = ChanponWorldSettingsLayout.linkedPortalSlot(54, isProtected = false)
        assertEquals(46, linked)
        assertTrue(linked != tourSlot, "フッター左操作がツアー項目と衝突してはならない")
    }

    @Test
    fun `オーナー非保護54枠の完成slotは一意である`() {
        val slots = mutableListOf(
            infoSlot, iconSlot, spawnSlot, announcementSlot, notificationSlot, tourSlot,
            expansionSlot, publishSlot, memberSlot, environmentSlot, criticalSlot,
            ChanponWorldSettingsLayout.PORTAL_MANAGEMENT_SLOT,
            worldInfoSlot,
            ChanponWorldSettingsLayout.linkedPortalSlot(54, isProtected = false),
            ChanponWorldSettingsLayout.productionSubmissionSlot(54),
            ChanponWorldSettingsLayout.backupSlot(54),
            backSlot54,
        )
        assertDistinct(slots, "オーナー非保護54枠")
    }

    @Test
    fun `非オーナー非保護54枠の完成slotは一意である`() {
        // 不具合の再現条件：メンバー閲覧時にツアー 47 とフッター左 47 が重複した。
        val slots = mutableListOf(
            infoSlot, iconSlot, spawnSlot, announcementSlot, notificationSlot, tourSlot,
            expansionSlot, publishSlot, memberSlot, environmentSlot, criticalSlot,
            ChanponWorldSettingsLayout.PORTAL_MANAGEMENT_SLOT,
            worldInfoSlot,
            ChanponWorldSettingsLayout.linkedPortalSlot(54, isProtected = false),
            backSlot54,
        )
        assertDistinct(slots, "非オーナー非保護54枠")
    }

    @Test
    fun `非オーナー保護54枠の完成slotは一意である`() {
        // 保護時は拡張ブロック（23,24,25,32,33）を追加しないため、フッター左の 23 は空いている。
        val slots = mutableListOf(
            infoSlot, iconSlot, spawnSlot, announcementSlot, notificationSlot, tourSlot,
            worldInfoSlot,
            ChanponWorldSettingsLayout.linkedPortalSlot(54, isProtected = true),
            backSlot54,
        )
        assertDistinct(slots, "非オーナー保護54枠")
    }

    @Test
    fun `オーナー保護45枠の完成slotは一意である`() {
        val slots = mutableListOf(
            21, 22,
            worldInfoSlot,
            ChanponWorldSettingsLayout.productionSubmissionSlot(45),
            ChanponWorldSettingsLayout.backupSlot(45),
            ChanponWorldSettingsLayout.linkedPortalSlot(45, isProtected = true),
            backSlot45,
        )
        assertDistinct(slots, "オーナー保護45枠")
    }

    private fun assertDistinct(slots: List<Int>, context: String) {
        val duplicates = slots.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue(duplicates.isEmpty(), "$context で slot が重複しています: $duplicates（一覧=$slots）")
        assertEquals(slots.size, slots.distinct().size)
    }
}
