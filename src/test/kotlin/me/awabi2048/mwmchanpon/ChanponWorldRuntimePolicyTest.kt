package me.awabi2048.mwmchanpon

import me.awabi2048.mwmchanpon.extension.ChanponWorldRuntimePolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ChanponWorldRuntimePolicyTest {
    @Test
    fun `capabilityを数値へ符号化せず無効化する`() {
        val policy = ChanponWorldRuntimePolicy()

        assertFalse(policy.isWorldPointEconomyEnabled())
        assertFalse(policy.isWorldSlotSystemEnabled())
        val methodNames = policy.javaClass.methods.map { it.name }.toSet()
        assertFalse("getCreationCost" in methodNames)
        assertFalse("getExpansionCost" in methodNames)
        assertFalse("getEnvironmentCost" in methodNames)
        assertFalse("getPortalWorldGatePointCostPerBlock" in methodNames)
    }
}
