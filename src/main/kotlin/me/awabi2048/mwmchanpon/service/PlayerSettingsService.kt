package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.model.PlayerSettingsData
import me.awabi2048.mwmchanpon.repository.PlayerSettingsRepository
import java.util.UUID

class PlayerSettingsService(private val repository: PlayerSettingsRepository) {
    private val supportedLanguages = listOf("ja_jp", "en_us")

    fun getLanguage(playerUuid: UUID): String? {
        return repository.load(playerUuid)?.language?.takeIf { supportedLanguages.contains(it) }
    }

    fun setLanguage(playerUuid: UUID, language: String) {
        require(supportedLanguages.contains(language)) { "Unsupported language: $language" }
        repository.save(PlayerSettingsData(playerUuid = playerUuid, language = language))
    }

    fun getSupportedLanguages(): List<String> = supportedLanguages
}
