package me.awabi2048.mwmchanpon.extension

import me.awabi2048.myworldmanager.api.extension.WorldRuntimePolicy

class ChanponWorldRuntimePolicy : WorldRuntimePolicy {
    override fun getId(): String = "mwmchanpon.world_runtime"

    override fun isWorldPointEconomyEnabled(): Boolean = false

    override fun isWorldSlotSystemEnabled(): Boolean = false

    override fun isExpirationArchiveEnabled(): Boolean = false

}
