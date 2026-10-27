package me.awabi2048.mwmchanpon.storage

import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipant
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantResult
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantResultState
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantState
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantStatus
import me.awabi2048.myworldmanager.api.service.ApiMigrationTargetKind
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

internal enum class ChanponWorldFileState { CURRENT, PENDING, FAILED }

internal data class ChanponWorldFileInspection(
    val file: File,
    val state: ChanponWorldFileState,
    val reason: String? = null,
)

/** Chanponのワールド単位データを /mwm migration の参加者として扱います。 */
internal class ChanponWorldDataMigrationParticipant(
    private val storage: ChanponWorldStorage,
    private val logger: Logger,
) : ApiMigrationParticipant {
    override fun getId(): String = ID

    override fun targetKind(): ApiMigrationTargetKind = ApiMigrationTargetKind.WORLD

    override fun statusFor(targetUuid: UUID): ApiMigrationParticipantStatus {
        val file = storage.worldFile(targetUuid)
        if (!file.exists()) return ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.CURRENT)
        val inspection = runCatching { inspectFile(file) }.getOrElse { error ->
            return ApiMigrationParticipantStatus(
                ID,
                ApiMigrationParticipantState.FAILED,
                "${file.name}: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        return when (inspection.state) {
            ChanponWorldFileState.CURRENT -> ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.CURRENT)
            ChanponWorldFileState.PENDING -> ApiMigrationParticipantStatus(ID, ApiMigrationParticipantState.PENDING, file.name)
            ChanponWorldFileState.FAILED -> ApiMigrationParticipantStatus(
                ID,
                ApiMigrationParticipantState.FAILED,
                "${file.name}: ${inspection.reason ?: "invalid Chanpon world data"}",
            )
        }
    }

    override fun status(): ApiMigrationParticipantStatus {
        val scan = scan()
        return when {
            scan.failed > 0 -> ApiMigrationParticipantStatus(
                ID,
                ApiMigrationParticipantState.FAILED,
                "Chanpon world data contains invalid files: ${scan.failed}",
            )
            scan.pending > 0 -> ApiMigrationParticipantStatus(
                ID,
                ApiMigrationParticipantState.PENDING,
                "Chanpon world data requires migration: ${scan.pending}",
            )
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
                before.message ?: "Chanpon world data contains invalid files",
            )
        }
        var migrated = 0
        scan().files.filter { it.state == ChanponWorldFileState.PENDING }.forEach { entry ->
            runCatching {
                migrateFile(entry.file, logger)
                migrated++
            }.onFailure { error ->
                logger.log(Level.WARNING, "Could not migrate Chanpon world file: ${entry.file.name}", error)
            }
        }
        val after = status()
        return if (after.state == ApiMigrationParticipantState.CURRENT) {
            ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.MIGRATED,
                "migrated: $ID ($migrated files)",
            )
        } else {
            ApiMigrationParticipantResult(
                ApiMigrationParticipantResultState.FAILED,
                after.message ?: "Chanpon world data migration remains incomplete",
            )
        }
    }

    private fun scan(): Scan {
        val entries = storage.worldFiles().map { file ->
            runCatching { inspectFile(file) }.getOrElse { error ->
                ChanponWorldFileInspection(file, ChanponWorldFileState.FAILED, error.message)
            }
        }
        return Scan(
            entries,
            entries.count { it.state == ChanponWorldFileState.PENDING },
            entries.count { it.state == ChanponWorldFileState.FAILED },
        )
    }

    companion object {
        const val ID = "mwm-chanpon-world-data"
        const val CURRENT_SCHEMA_VERSION = 1

        fun inspectFile(file: File): ChanponWorldFileInspection {
            val filenameUuid = runCatching { UUID.fromString(file.nameWithoutExtension) }
                .getOrElse { error("filename is not a UUID: ${file.name}") }
            val config = YamlConfiguration().also { it.load(file) }
            val version = readVersion(config, file)
            require(version <= CURRENT_SCHEMA_VERSION) {
                "world data is newer than this plugin: ${file.name}=$version"
            }
            // PASSWORD は旧来の建築許可モードであり、現行の公開パスワード機能とは別物です。
            // 現行 enum からは廃止済みですが、ここで先に拒否すると migrateFile() の
            // "password" -> "members" 変換へ到達できず、既存ワールドが永久に FAILED になります。
            // 移行前の検査に限って受け入れ、移行後の validate() では引き続き拒否します。
            validate(config, file, allowLegacyPasswordMode = true)
            val legacy = config.contains("development") ||
                config.contains("development_set_at") ||
                config.getString("protection.mode") == LEGACY_PASSWORD_MODE
            return ChanponWorldFileInspection(
                file,
                if (version < CURRENT_SCHEMA_VERSION || legacy) ChanponWorldFileState.PENDING
                else ChanponWorldFileState.CURRENT,
            )
        }

        /** 外部復元元はファイル名にUUIDを持たないため、対象UUIDを別引数で検証します。 */
        fun validateCurrentExternalFile(file: File, @Suppress("UNUSED_PARAMETER") expectedUuid: UUID) {
            // バックアップ内は worlds/<uuid>/chanpon.yml、アーカイブ内は worlds/<uuid>/chanpon_data.yml と、
            // 外部配置ではファイル名がUUIDではない。対象の特定は呼び出し元のディレクトリ経路で行い、
            // ここでは内容が現行形式であることだけを検証する。
            require(file.isFile) { "world data source is not a regular file: ${file.name}" }
            val config = YamlConfiguration().also { it.load(file) }
            val version = readVersion(config, file)
            require(version == CURRENT_SCHEMA_VERSION) {
                "world data source is not current: ${file.name}=$version"
            }
            validate(config, file)
            require(!config.contains("development") && !config.contains("development_set_at")) {
                "world data source still contains legacy development fields: $expectedUuid"
            }
        }

        fun migrateFile(file: File, logger: Logger) {
            val config = YamlConfiguration().also { it.load(file) }
            val version = readVersion(config, file)
            require(version <= CURRENT_SCHEMA_VERSION) { "unsupported world data schema: ${file.name}" }
            if (!config.contains("visibility") && config.contains("development")) {
                val development = config.get("development")
                require(development is Boolean) { "development must be boolean: ${file.name}" }
                config.set(
                    "visibility",
                    if (development) ChanponWorldVisibility.DEVELOPMENT.storageValue
                    else ChanponWorldVisibility.PRIVATE.storageValue,
                )
            }
            config.set("development", null)
            config.set("development_set_at", null)
            // 建築許可モードからPASSWORDを廃止したため、既存の"password"は"members"へ置換します。
            // パスワード機能（メンバー参加パスワード）はモードとは独立して常時有効です。
            if (config.getString("protection.mode") == "password") {
                config.set("protection.mode", "members")
            }
            config.set("schema_version", CURRENT_SCHEMA_VERSION)
            validate(config, file)

            val backup = File(file.parentFile, "${file.name}.pre-migration-${System.currentTimeMillis()}.bak")
            val temporary = File(file.parentFile, "${file.name}.migration.tmp")
            try {
                Files.copy(file.toPath(), backup.toPath())
                config.save(temporary)
                FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                val verified = YamlConfiguration().also { it.load(temporary) }
                check(readVersion(verified, temporary) == CURRENT_SCHEMA_VERSION)
                try {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                // 移行成功後のバックアップは復元に不要なため削除する。失敗時は従来どおりリストア用に残す。
                runCatching { backup.delete() }
            } catch (error: Exception) {
                Files.deleteIfExists(temporary.toPath())
                runCatching { Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                logger.log(Level.WARNING, "Chanpon world data migration failed: ${file.name}", error)
                throw error
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
        }

        private fun readVersion(config: YamlConfiguration, file: File): Int {
            val raw = config.get("schema_version") ?: return 0
            require(raw is Number && raw.toDouble() % 1.0 == 0.0 && raw.toInt() >= 0) {
                "schema_version must be a non-negative integer: ${file.name}"
            }
            return raw.toInt()
        }

        private fun validate(
            config: YamlConfiguration,
            file: File,
            allowLegacyPasswordMode: Boolean = false,
        ) {
            config.get("public_tool_permissions")?.let {
                require(config.isConfigurationSection("public_tool_permissions")) {
                    "非メンバー用ツール権限はセクションで指定してください: ${file.name}"
                }
                config.getConfigurationSection("public_tool_permissions")!!.getKeys(false).forEach { id ->
                    require(me.awabi2048.mwmchanpon.tool.ToolPermissionCatalog.types.any { it.id == id } &&
                        config.isBoolean("public_tool_permissions.$id")) {
                        "非メンバー用ツール権限が不正です: ${file.name}/$id"
                    }
                }
            }
            config.get("production")?.let { require(it is Boolean) { "production must be boolean: ${file.name}" } }
            config.getString("visibility")?.let {
                require(ChanponWorldVisibility.fromStorageValue(it) != null) {
                    "visibility is invalid: ${file.name}"
                }
            }
            config.get("development")?.let { require(it is Boolean) { "development must be boolean: ${file.name}" } }
            config.getString("submission.state")?.let {
                require(SubmissionState.fromStorageValue(it) != null) {
                    "submission.state is invalid: ${file.name}"
                }
            }
            config.getString("protection.mode")?.let {
                require(
                    ChanponWorldProtectionMode.fromStorageValue(it) != null ||
                        (allowLegacyPasswordMode && it == LEGACY_PASSWORD_MODE),
                ) {
                    "protection.mode is invalid: ${file.name}"
                }
            }
        }

        private const val LEGACY_PASSWORD_MODE = "password"
    }

    private data class Scan(
        val files: List<ChanponWorldFileInspection>,
        val pending: Int,
        val failed: Int,
    )
}
