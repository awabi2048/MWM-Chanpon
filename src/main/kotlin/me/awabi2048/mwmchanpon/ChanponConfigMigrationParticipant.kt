package me.awabi2048.mwmchanpon

import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipant
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantResult
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantResultState
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantState
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantStatus
import me.awabi2048.mwmchanpon.storage.WorldCreationAvailability
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Chanpon が所有する旧設定を /mwm migration の管理下へ置きます。
 * 起動時は検査と読み取り時の安全な既定値解釈だけを行い、既存ファイルの変更はこの参加者の
 * migrate() からのみ実行します。
 */
internal class ChanponConfigMigrationParticipant(
    private val plugin: MWMChanponPlugin,
) : ApiMigrationParticipant {
    private val configFile = plugin.dataFolder.resolve("config.yml")
    private val settingsFile = plugin.dataFolder.resolve("settings.yml")

    override fun getId(): String = ID

    override fun status(): ApiMigrationParticipantStatus {
        val configStatus = configStatus()
        if (configStatus.state == ApiMigrationParticipantState.FAILED) return configStatus
        val settingsStatus = settingsStatus()
        if (settingsStatus.state == ApiMigrationParticipantState.FAILED) return settingsStatus
        return when {
            configStatus.state == ApiMigrationParticipantState.PENDING -> configStatus
            settingsStatus.state == ApiMigrationParticipantState.PENDING -> settingsStatus
            else -> ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.CURRENT)
        }
    }

    fun executeMigration(): ApiMigrationParticipantResult {
        val before = status()
        if (before.state == ApiMigrationParticipantState.CURRENT) {
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.ALREADY_CURRENT,
                "already current: $ID",
            )
        }
        if (before.state == ApiMigrationParticipantState.FAILED) {
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                before.message ?: "Chanpon data cannot be migrated automatically",
            )
        }

        var migrated = false
        if (configStatus().state == ApiMigrationParticipantState.PENDING) {
            val result = migrateConfig()
            if (result.state == ApiMigrationParticipantResultState.FAILED) return result
            migrated = true
        }
        if (settingsStatus().state == ApiMigrationParticipantState.PENDING) {
            val result = migrateSettings()
            if (result.state == ApiMigrationParticipantResultState.FAILED) return result
            migrated = true
        }

        val after = status()
        if (after.state == ApiMigrationParticipantState.FAILED) {
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                after.message ?: "Chanpon data remains invalid after migration",
            )
        }
        return ApiMigrationParticipantResult(
            if (migrated) ApiMigrationParticipantResultState.MIGRATED
            else ApiMigrationParticipantResultState.ALREADY_CURRENT,
            if (migrated) "migrated: $ID" else "already current: $ID",
        )
    }

    private fun configStatus(): ApiMigrationParticipantStatus {
        if (!Files.isRegularFile(configFile.toPath())) {
            return ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.PENDING, "config.yml is missing")
        }
        return runCatching {
            val config = loadConfig()
            val version = version(config)
            when {
                version > CURRENT_VERSION -> ApiMigrationParticipantStatus(
                    ID,
                    ApiMigrationParticipantState.FAILED,
                    "config.yml is newer than this plugin: $version",
                )
                version < CURRENT_VERSION -> ApiMigrationParticipantStatus(
                    ID,
                    ApiMigrationParticipantState.PENDING,
                    "config.yml requires migration: $version->$CURRENT_VERSION",
                )
                else -> {
                    validateConfig(config)
                    ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.CURRENT)
                }
            }
        }.getOrElse { error ->
            ApiMigrationParticipantStatus(
                ID,
                ApiMigrationParticipantState.FAILED,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun settingsStatus(): ApiMigrationParticipantStatus {
        if (!Files.isRegularFile(settingsFile.toPath())) {
            return ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.CURRENT)
        }
        return runCatching {
            val settings = loadSettings()
            val phase = settings.getString("phase")
            when {
                phase != null &&
                    (settings.contains("building_allowed") || settings.contains("world_creation_availability")) ->
                    ApiMigrationParticipantStatus(
                        ID,
                        ApiMigrationParticipantState.FAILED,
                        "settings.yml contains both legacy phase and current controls",
                    )
                phase != null && phase !in SUPPORTED_PHASES -> ApiMigrationParticipantStatus(
                    ID,
                    ApiMigrationParticipantState.FAILED,
                    "settings.yml contains an unsupported legacy phase: $phase",
                )
                phase != null -> ApiMigrationParticipantStatus(
                    ID,
                    ApiMigrationParticipantState.PENDING,
                    "settings.yml requires migration: phase=$phase",
                )
                !settings.contains("building_allowed") &&
                    !settings.contains("world_creation_availability") ->
                    ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.CURRENT)
                !settings.contains("building_allowed") ||
                    !settings.contains("world_creation_availability") -> ApiMigrationParticipantStatus(
                    ID,
                    ApiMigrationParticipantState.PENDING,
                    "settings.yml requires migration: current controls are incomplete",
                )
                else -> {
                    if (settings.contains("building_allowed")) {
                        check(settings.isBoolean("building_allowed")) {
                            "settings.yml building_allowed must be boolean"
                        }
                    }
                    if (settings.contains("world_creation_availability")) {
                        parseCreationAvailability(settings)
                    }
                    ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.CURRENT)
                }
            }
        }.getOrElse { error ->
            ApiMigrationParticipantStatus(
                ID,
                ApiMigrationParticipantState.FAILED,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun migrateConfig(): ApiMigrationParticipantResult {
        val existed = Files.isRegularFile(configFile.toPath())
        val original = runCatching {
            if (existed) loadConfig() else YamlConfiguration()
        }.getOrElse { error ->
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                "cannot read config.yml: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        val detectedVersion = runCatching { version(original) }.getOrElse { error ->
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                error.message ?: error.javaClass.simpleName,
            )
        }
        if (detectedVersion > CURRENT_VERSION) {
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                "config.yml is newer than this plugin: $detectedVersion",
            )
        }

        val backup = configFile.toPath().resolveSibling(
            "${configFile.name}.pre-migration-${LocalDateTime.now().format(BACKUP_FORMATTER)}.bak",
        )
        val temporary = configFile.toPath().resolveSibling("${configFile.name}.migration.tmp")
        return try {
            if (existed) Files.copy(configFile.toPath(), backup)
            var next = detectedVersion
            while (next < CURRENT_VERSION) {
                when (next) {
                    0 -> mergeBundledDefaults(original)
                    1 -> if (!original.contains("creation.max_worlds_per_player")) {
                        original.set("creation.max_worlds_per_player", 50)
                    }
                    2 -> if (!original.contains("standalone_export.fiscal_year")) {
                        original.set("standalone_export.fiscal_year", 2026)
                    }
                    3 -> if (!original.contains("creation.easy.platform_material")) {
                        original.set("creation.easy.platform_material", "OBSIDIAN")
                    }
                    else -> error("Missing Chanpon config migration: $next->${next + 1}")
                }
                next++
                original.set("config_version", next)
            }
            validateConfig(original)
            original.save(temporary.toFile())
            val verified = YamlConfiguration.loadConfiguration(temporary.toFile())
            check(version(verified) == CURRENT_VERSION)
            validateConfig(verified)
            moveAtomically(temporary, configFile.toPath())
            plugin.reloadConfig()
            // 移行成功後のバックアップは復元に不要なため削除する。失敗時は従来どおりリストア用に残す。
            runCatching { Files.deleteIfExists(backup) }
            ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.MIGRATED,
                "migrated: $ID config.yml ($detectedVersion->$CURRENT_VERSION)",
            )
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            if (Files.isRegularFile(backup)) {
                runCatching { Files.copy(backup, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
            plugin.reloadConfig()
            ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                "config.yml migration failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun migrateSettings(): ApiMigrationParticipantResult {
        val settings = runCatching { loadSettings() }.getOrElse { error ->
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                "cannot read settings.yml: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        val phase = settings.getString("phase")
        if (phase != null && phase !in SUPPORTED_PHASES) {
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                "settings.yml contains an unsupported legacy phase: $phase",
            )
        }
        if (phase != null &&
            (settings.contains("building_allowed") || settings.contains("world_creation_availability"))
        ) {
            return ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                "settings.yml contains both legacy phase and current controls",
            )
        }
        val buildingAllowed = phase?.let { it != "BUILD_LOCKED" }
            ?: settings.getBoolean("building_allowed", true)
        val creationAvailability = phase?.let {
            when (it) {
                "BUILD_LOCKED" -> WorldCreationAvailability.DENIED
                "CREATION_ALLOWED" -> WorldCreationAvailability.NON_PRODUCTION
                else -> WorldCreationAvailability.PRODUCTION
            }
        } ?: if (settings.contains("world_creation_availability")) {
            runCatching { parseCreationAvailability(settings) }.getOrElse { error ->
                return ApiMigrationParticipantResult(
                    ApiMigrationParticipantResultState.FAILED,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        } else {
            WorldCreationAvailability.PRODUCTION
        }
        val backup = settingsFile.toPath().resolveSibling(
            "${settingsFile.name}.pre-migration-${LocalDateTime.now().format(BACKUP_FORMATTER)}.bak",
        )
        val temporary = settingsFile.toPath().resolveSibling("${settingsFile.name}.migration.tmp")
        return try {
            Files.copy(settingsFile.toPath(), backup)
            settings.set("building_allowed", buildingAllowed)
            settings.set("world_creation_availability", creationAvailability.name)
            settings.set("phase", null)
            settings.save(temporary.toFile())
            val verified = YamlConfiguration.loadConfiguration(temporary.toFile())
            check(!verified.contains("phase"))
            check(verified.isBoolean("building_allowed"))
            check(verified.getBoolean("building_allowed") == buildingAllowed)
            check(parseCreationAvailability(verified) == creationAvailability)
            moveAtomically(temporary, settingsFile.toPath())
            // 移行成功後のバックアップは復元に不要なため削除する。失敗時は従来どおりリストア用に残す。
            runCatching { Files.deleteIfExists(backup) }
            ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.MIGRATED,
                "migrated: $ID settings.yml${phase?.let { " (phase=$it)" } ?: ""}",
            )
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            runCatching { Files.copy(backup, settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                "settings.yml migration failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun loadConfig(): YamlConfiguration = loadStrict(configFile)

    private fun loadSettings(): YamlConfiguration = loadStrict(settingsFile)

    private fun loadStrict(file: java.io.File): YamlConfiguration =
        YamlConfiguration().also { it.load(file) }

    private fun version(config: YamlConfiguration): Int {
        val raw = config.get("config_version") ?: return 0
        require(raw is Number) { "config_version must be an integer" }
        val value = raw.toInt()
        require(value >= 0 && raw.toDouble() == value.toDouble()) {
            "config_version must be a non-negative integer"
        }
        return value
    }

    private fun parseCreationAvailability(config: YamlConfiguration): WorldCreationAvailability {
        val raw = config.getString("world_creation_availability")
            ?: error("settings.yml world_creation_availability is missing")
        return runCatching { WorldCreationAvailability.valueOf(raw) }
            .getOrElse { error("settings.yml has unsupported world_creation_availability: $raw") }
    }

    private fun mergeBundledDefaults(config: YamlConfiguration) {
        val defaults = YamlConfiguration()
        plugin.getResource("config.yml")?.use { defaults.load(it.reader(Charsets.UTF_8)) }
            ?: error("Bundled config resource is missing: config.yml")
        defaults.getKeys(true)
            .filter { it != "config_version" }
            .filterNot(defaults::isConfigurationSection)
            .filterNot(config::contains)
            .forEach { config.set(it, defaults.get(it)) }
    }

    private fun validateConfig(config: YamlConfiguration) {
        require(config.getInt("creation.max_worlds_per_player", 50) >= 1) {
            "creation.max_worlds_per_player must be at least 1"
        }
        require(config.getInt("world_expansion.max_level", 3) >= 0) {
            "world_expansion.max_level must not be negative"
        }
        require(config.isInt("standalone_export.fiscal_year")) {
            "standalone_export.fiscal_year must be an integer"
        }
        require(config.getInt("standalone_export.fiscal_year") in 2000..9999) {
            "standalone_export.fiscal_year must be between 2000 and 9999"
        }
        require(
            me.awabi2048.mwmchanpon.service.EasyPlatformMaterialPolicy.resolve(
                config.getString("creation.easy.platform_material").orEmpty(),
            ) != null,
        ) {
            "creation.easy.platform_material must be a placeable solid block material"
        }
    }

    private fun moveAtomically(source: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val ID = "mwm-chanpon-data"
        const val CURRENT_VERSION = 4
        val SUPPORTED_PHASES = setOf("BUILD_LOCKED", "CREATION_ALLOWED", "PRODUCTION")
        val BACKUP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}
