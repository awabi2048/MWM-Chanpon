package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.MenuClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChanponEnvironmentFeedbackTest {
    @Test
    fun `環境設定操作を意味に対応するクリック種別へ割り当てる`() {
        assertEquals(MenuClickType.DEFAULT, ChanponEnvironmentFeedback.SETTING_CHANGED)
        assertEquals(MenuClickType.NAVIGATION, ChanponEnvironmentFeedback.OPEN_DIALOG)
        assertEquals(MenuClickType.CANCEL, ChanponEnvironmentFeedback.BACK)
    }
}
