package me.awabi2048.mwmchanpon.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChanponWorldSettingsLayoutTest {
    @Test
    fun `ポータル管理は設定画面の固定スロットを使用する`() {
        assertEquals(6, ChanponWorldSettingsLayout.PORTAL_MANAGEMENT_SLOT)
        assertEquals(51, ChanponWorldSettingsLayout.backupSlot(54))
        assertEquals(52, ChanponWorldSettingsLayout.productionSubmissionSlot(54))
        assertEquals(42, ChanponWorldSettingsLayout.backupSlot(45))
        assertEquals(43, ChanponWorldSettingsLayout.productionSubmissionSlot(45))
    }

    @Test
    fun `ポータル取得は実際の画面サイズからフッター位置を決める`() {
        // フッター左操作はオーナー判定によらず1マス目へ統一する。
        // 非オーナーで 47 を返すとツアー項目（slot 47）と衝突する不具合があったため、46 へ統一した。
        assertEquals(46, ChanponWorldSettingsLayout.linkedPortalSlot(54, isProtected = false))
        assertEquals(23, ChanponWorldSettingsLayout.linkedPortalSlot(54, isProtected = true))
        assertEquals(23, ChanponWorldSettingsLayout.linkedPortalSlot(45, isProtected = true))
    }
}
