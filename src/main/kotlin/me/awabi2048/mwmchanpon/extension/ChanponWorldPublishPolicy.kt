package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldVisibility
import me.awabi2048.myworldmanager.api.extension.WorldPublishPolicy
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.entity.Player

class ChanponWorldPublishPolicy(
    private val storage: ChanponWorldStorage
) : WorldPublishPolicy {
    override fun getId(): String = "mwmchanpon.world_publish"

    override fun getPublishDisplayName(
        player: Player,
        worldData: WorldData,
        defaultDisplayName: String
    ): String {
        return displayName(player, storage.getVisibility(worldData.uuid))
    }

    override fun cyclePublishLevel(player: Player, worldData: WorldData): Boolean {
        val next = when (storage.getVisibility(worldData.uuid)) {
            ChanponWorldVisibility.PUBLIC -> ChanponWorldVisibility.DEVELOPMENT
            ChanponWorldVisibility.DEVELOPMENT -> ChanponWorldVisibility.PRIVATE
            ChanponWorldVisibility.PRIVATE -> ChanponWorldVisibility.PUBLIC
        }
        storage.setVisibility(worldData.uuid, next)
        player.sendMessage(
            text(player, "chanpon.publish.updated", mapOf("level" to displayName(player, next)))
        )
        return true
    }

    private fun displayName(player: Player?, visibility: ChanponWorldVisibility): String {
        val key = when (visibility) {
            ChanponWorldVisibility.PUBLIC -> "public"
            ChanponWorldVisibility.DEVELOPMENT -> "development"
            ChanponWorldVisibility.PRIVATE -> "private"
        }
        return text(player, "chanpon.publish.$key")
    }

    private fun text(player: Player?, key: String, placeholders: Map<String, Any> = emptyMap()): String {
        return CCSystem.getAPI().getI18nString(player, key, placeholders)
    }
}
