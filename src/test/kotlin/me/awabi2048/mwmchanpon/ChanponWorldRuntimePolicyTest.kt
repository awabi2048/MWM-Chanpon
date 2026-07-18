package me.awabi2048.mwmchanpon

import me.awabi2048.mwmchanpon.extension.ChanponWorldRuntimePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ChanponWorldRuntimePolicyTest {
    @Test
    fun `capabilityを数値へ符号化せず無効化する`() {
        val policy = ChanponWorldRuntimePolicy()

        assertFalse(policy.isWorldPointEconomyEnabled())
        assertFalse(policy.isWorldSlotSystemEnabled())
        assertEquals(7, policy.getMaxCreateCountDefault(7))
        assertEquals(12, policy.getMaxWorldSlotLimit(12))
    }
}
