package me.awabi2048.mwmchanpon.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChanponAdminMenuLayoutTest {
    @Test
    fun `フッターの指定位置に管理項目を配置する`() {
        assertEquals(37, ChanponAdminMenuLayout.FOOTER_BUILDING_PERMISSION)
        assertEquals(38, ChanponAdminMenuLayout.FOOTER_WORLD_CREATION)
        assertEquals(40, ChanponAdminMenuLayout.FOOTER_MAIN_SWITCH)
        assertEquals(43, ChanponAdminMenuLayout.FOOTER_SPAWN_WORLD)
    }
}
