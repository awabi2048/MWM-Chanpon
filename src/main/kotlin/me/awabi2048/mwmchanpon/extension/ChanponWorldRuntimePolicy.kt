package me.awabi2048.mwmchanpon.extension

import me.awabi2048.myworldmanager.api.extension.WorldRuntimePolicy
import me.awabi2048.myworldmanager.api.extension.WorldCreationType

class ChanponWorldRuntimePolicy : WorldRuntimePolicy {
    override fun getId(): String = "mwmchanpon.world_runtime"

    override fun getCreationCost(type: WorldCreationType, configuredCost: Int): Int = 0

    override fun getMaxCreateCountDefault(configuredLimit: Int): Int = configuredLimit.coerceAtLeast(50)

    override fun getMaxWorldSlotLimit(configuredLimit: Int): Int = 0

    override fun shouldReduceOwnerSlotOnDelete(configured: Boolean): Boolean = false

    override fun getExpansionCost(targetLevel: Int, configuredCost: Int): Int = 0

    override fun getEnvironmentCost(type: String, configuredCost: Int): Int = 0

    override fun getPortalWorldGatePointCostPerBlock(configuredCost: Int): Int = 0
}
