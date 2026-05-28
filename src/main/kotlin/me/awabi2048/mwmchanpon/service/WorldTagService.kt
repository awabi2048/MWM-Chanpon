package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import org.bukkit.entity.Player
import java.util.Locale

class WorldTagService(private val plugin: MWMChanponPlugin) {
    private val configuredTagIds = mutableListOf<String>()
    private val knownTagIds = linkedSetOf<String>()

    fun reload() {
        configuredTagIds.clear()
        knownTagIds.clear()

        plugin.config.getStringList("world_tags")
            .mapNotNull(::normalizeTagId)
            .forEach { tagId ->
                if (knownTagIds.add(tagId)) {
                    configuredTagIds.add(tagId)
                }
            }

        if (configuredTagIds.isEmpty()) {
            listOf("shop", "minigame", "building", "facility", "streaming").forEach { tagId ->
                knownTagIds.add(tagId)
                configuredTagIds.add(tagId)
            }
        }
    }

    fun getEditableTagIds(currentTagIds: List<String>): List<String> {
        val editable = linkedSetOf<String>()
        editable.addAll(configuredTagIds)
        currentTagIds.mapNotNull(::normalizeTagId).forEach(editable::add)
        return editable.toList()
    }

    fun normalizeTagId(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        return legacyTagMap[trimmed.uppercase(Locale.ROOT)] ?: trimmed.lowercase(Locale.ROOT)
    }

    fun getDisplayName(player: Player?, tagId: String): String {
        val normalized = normalizeTagId(tagId) ?: return tagId
        val key = "world_tag.$normalized"
        return if (plugin.localizationService.hasKey(player, key)) {
            plugin.localizationService.getString(player, key)
        } else {
            normalized.replace('_', ' ')
        }
    }

    private val legacyTagMap = mapOf(
        "SHOP" to "shop",
        "MINIGAME" to "minigame",
        "BUILDING" to "building",
        "FACILITY" to "facility",
        "STREAMING" to "streaming",
    )
}
