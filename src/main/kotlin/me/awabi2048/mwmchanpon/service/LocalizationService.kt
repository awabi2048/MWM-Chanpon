package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

class LocalizationService(
    private val plugin: MWMChanponPlugin,
    private val playerSettingsService: PlayerSettingsService,
) {
    private val cache = linkedMapOf<String, YamlConfiguration>()

    fun clearCache() {
        cache.clear()
    }

    fun getString(player: Player?, key: String, placeholders: Map<String, String> = emptyMap()): String {
        val locale = resolveLocale(player)
        val fallback = plugin.config.getString("fallback_language", "ja_jp") ?: "ja_jp"
        val raw = load(locale).getString(key)
            ?: load(fallback).getString(key)
            ?: key
        return applyPlaceholders(raw, placeholders)
    }

    fun getStringList(player: Player?, key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val locale = resolveLocale(player)
        val fallback = plugin.config.getString("fallback_language", "ja_jp") ?: "ja_jp"
        val raw = load(locale).getStringList(key).ifEmpty { load(fallback).getStringList(key) }
        return raw.map { applyPlaceholders(it, placeholders) }
    }

    private fun resolveLocale(player: Player?): String {
        if (player == null) {
            return plugin.config.getString("fallback_language", "ja_jp") ?: "ja_jp"
        }
        playerSettingsService.getLanguage(player.uniqueId)?.let { return it }
        return player.locale().toLanguageTag().replace('-', '_').lowercase()
    }

    private fun load(locale: String): YamlConfiguration {
        return cache.getOrPut(locale) {
            val file = File(plugin.dataFolder, "lang/$locale.yml")
            YamlConfiguration.loadConfiguration(file)
        }
    }

    private fun applyPlaceholders(text: String, placeholders: Map<String, String>): String {
        var result = text
        for ((key, value) in placeholders) {
            result = result.replace("{$key}", value)
        }
        return result
    }
}
