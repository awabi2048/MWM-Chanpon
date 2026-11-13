package me.awabi2048.mwmchanpon.tool

import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPermissionModePolicyTest {
    @Test
    fun `オーナー限定では個別ツール設定があっても非オーナーを許可しない`() {
        assertTrue(ToolPermissionModePolicy.canUse(ChanponWorldProtectionMode.OWNER_ONLY, true))
        assertFalse(ToolPermissionModePolicy.canUse(ChanponWorldProtectionMode.OWNER_ONLY, false))
        assertTrue(ToolPermissionModePolicy.canUse(ChanponWorldProtectionMode.MEMBERS, false))
        assertTrue(ToolPermissionModePolicy.canUse(ChanponWorldProtectionMode.PUBLIC, false))
    }
}
