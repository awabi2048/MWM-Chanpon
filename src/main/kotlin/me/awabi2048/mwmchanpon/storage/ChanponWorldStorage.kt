package me.awabi2048.mwmchanpon.storage

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipant
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantResult
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantResultState
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantState
import me.awabi2048.myworldmanager.api.service.ApiMigrationParticipantStatus

enum class SubmissionState(val storageValue: String, val i18nKey: String) {
    UNSUBMITTED("unsubmitted", "unsubmitted"),
    SUBMITTED("submitted", "submitted"),
    COMPLETED("completed", "completed");

    companion object {
        fun fromStorageValue(value: String?): SubmissionState? =
            entries.firstOrNull { it.storageValue == value }
    }
}

enum class ChanponWorldVisibility(val storageValue: String) {
    PUBLIC("public"),
    DEVELOPMENT("development"),
    PRIVATE("private");

    companion object {
        fun fromStorageValue(value: String?): ChanponWorldVisibility? =
            entries.firstOrNull { it.storageValue == value }
    }
}

enum class ChanponWorldProtectionMode(val storageValue: String, val i18nKey: String) {
    OWNER_ONLY("owner_only", "owner_only"),
    MEMBERS("members", "members"),
    PUBLIC("public", "public");

    companion object {
        fun fromStorageValue(value: String?): ChanponWorldProtectionMode? =
            entries.firstOrNull { it.storageValue == value }
    }
}

class ChanponWorldStorage(
    dataFolder: File,
    private val logger: Logger = Logger.getLogger("MWM-Chanpon"),
) {
    private val worldsDir = File(dataFolder, "my_worlds")
    private var visibilitySyncHandler: ((UUID) -> Unit)? = null
    private var worldWriteGate: ((UUID) -> Boolean)? = null
    private val warnedLoadFailures = ConcurrentHashMap.newKeySet<UUID>()

    private val migrationExecutor = ChanponWorldDataMigrationParticipant(this, logger)
    val migrationParticipant: ApiMigrationParticipant = migrationExecutor

    init {
        worldsDir.mkdirs()
        MyWorldManagerApi.registerMigrationParticipant(migrationParticipant, migrationExecutor::executeMigration)
    }

    fun worldFile(worldUuid: UUID): File = File(worldsDir, "${worldUuid}.yml")

    data class WorldFileSnapshot(val exists: Boolean, val bytes: ByteArray, val sha256: String)

    enum class WorldFileRestoreResult { RESTORED, CONCURRENT_CHANGE }

    @Synchronized
    fun captureWorldFileSnapshot(worldUuid: UUID): WorldFileSnapshot {
        val target = worldFile(worldUuid)
        if (!target.exists()) return WorldFileSnapshot(false, byteArrayOf(), sha256(byteArrayOf()))
        val bytes = target.readBytes()
        return WorldFileSnapshot(true, bytes, sha256(bytes))
    }

    @Synchronized
    fun matchesWorldFileSnapshot(worldUuid: UUID, snapshot: WorldFileSnapshot): Boolean {
        val target = worldFile(worldUuid)
        if (target.exists() != snapshot.exists) return false
        return !snapshot.exists || sha256(target.readBytes()) == snapshot.sha256
    }

    @Synchronized
    fun restoreWorldFileSnapshot(
        worldUuid: UUID,
        snapshot: WorldFileSnapshot,
        expectedCurrentState: () -> Boolean,
    ): WorldFileRestoreResult {
        ensureWritable(worldUuid)
        if (!expectedCurrentState()) return WorldFileRestoreResult.CONCURRENT_CHANGE
        val target = worldFile(worldUuid)
        if (!snapshot.exists) {
            Files.deleteIfExists(target.toPath())
            return WorldFileRestoreResult.RESTORED
        }
        check(sha256(snapshot.bytes) == snapshot.sha256) { "Chanpon world snapshot hash mismatch: $worldUuid" }
        writeAtomicBytes(target, snapshot.bytes, snapshot.sha256)
        return WorldFileRestoreResult.RESTORED
    }

    /**
     * バックアップ／アーカイブからのワールド別データ復元を一つの入口へ集約します。
     * 外部ファイルは現行形式であることを検証してから、通常保存と同じatomic置換を行います。
     * 旧形式をここで変換することは許可せず、変換は /mwm migration に限定します。
     */
    @Synchronized
    fun restoreExternalWorldFile(source: File, worldUuid: UUID) {
        validateExternalWorldFile(source, worldUuid)
        val restored = YamlConfiguration().also { it.load(source) }
        val current = worldFile(worldUuid).takeIf(File::isFile)?.let { YamlConfiguration().also { config -> config.load(it) } }
        restored.set("backup.last_hash", null)
        restored.set(
            "backup.owner_manual.last_created_at",
            current?.getLong("backup.owner_manual.last_created_at", 0L)
                ?.takeIf { it > 0L },
        )
        saveAtomic(worldUuid, restored)
    }

    /**
     * バックアップに対象ファイルが存在しない状態を、そのまま復元するための削除入口です。
     * 通常の削除とは異なり、移行状態と書込みゲートを必ず確認してから一時名へ移動します。
     * 復元元にないデータを現在のファイルに残したまま成功扱いにしないために使用します。
     */
    @Synchronized
    fun removeWorldFileForRestore(worldUuid: UUID) {
        ensureWritable(worldUuid)
        val target = worldFile(worldUuid)
        if (!target.isFile) {
            require(!target.exists()) { "Chanpon world data target is not a regular file: ${target.name}" }
            return
        }
        val tombstone = File(target.parentFile, "${target.name}.restore-delete-${UUID.randomUUID()}.tmp")
        try {
            try {
                Files.move(
                    target.toPath(),
                    tombstone.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(target.toPath(), tombstone.toPath())
            }
        } finally {
            Files.deleteIfExists(tombstone.toPath())
        }
    }

    /**
     * 外部復元の書き込み前検証です。バックアップ処理はワールドディレクトリを移動する前に
     * この検証だけを実行し、旧形式を復元経路で暗黙変換しないようにします。
     */
    @Synchronized
    fun validateExternalWorldFile(source: File, worldUuid: UUID) {
        require(source.isFile) { "Chanpon world data source is missing: ${source.name}" }
        ensureWritable(worldUuid)
        ChanponWorldDataMigrationParticipant.validateCurrentExternalFile(source, worldUuid)
    }

    fun setVisibilitySyncHandler(handler: (UUID) -> Unit) {
        visibilitySyncHandler = handler
    }

    /** ワールド単位で、旧形式・破損ファイルへの通常書込みを止めます。 */
    fun setWorldWriteGate(gate: (UUID) -> Boolean) {
        worldWriteGate = gate
    }

    internal fun worldFiles(): List<File> = worldsDir.listFiles { file -> file.extension == "yml" }.orEmpty().toList()

    internal fun migrateWorldFile(file: File) = ChanponWorldDataMigrationParticipant.migrateFile(file, logger)

    internal fun inspectWorldFile(file: File): ChanponWorldFileInspection =
        ChanponWorldDataMigrationParticipant.inspectFile(file)

    fun isWorldDataCurrent(worldUuid: UUID): Boolean {
        val file = worldFile(worldUuid)
        return !file.exists() || runCatching { inspectWorldFile(file).state == ChanponWorldFileState.CURRENT }.getOrDefault(false)
    }

    /** 既存ワールドの運用判定では、ファイル不存在も利用不能として扱います。 */
    fun hasCurrentWorldData(worldUuid: UUID): Boolean {
        val file = worldFile(worldUuid)
        // 既存ワールドにChanponファイルがまだない状態は「未初期化」であり、
        // 旧形式・破損ファイルとは異なります。読み取りは安全な既定値を使い、
        // 最初の設定変更時にsaveAtomic()が現行形式を生成します。
        return !file.exists() || runCatching {
            inspectWorldFile(file).state == ChanponWorldFileState.CURRENT
        }.getOrDefault(false)
    }

    /** バックアップ復元など、storage外からの上書き前にも同じゲートを適用します。 */
    fun ensureWritableForExternalWrite(worldUuid: UUID) {
        ensureWritable(worldUuid)
    }

    fun isWorldDataFileCurrent(file: File): Boolean =
        !file.exists() || runCatching { inspectWorldFile(file).state == ChanponWorldFileState.CURRENT }.getOrDefault(false)

    fun syncVisibility(worldUuid: UUID) {
        visibilitySyncHandler?.invoke(worldUuid)
    }

    fun isProductionWorld(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("production", false)
    }

    @Synchronized
    fun setProductionWorld(worldUuid: UUID, production: Boolean) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        val wasProduction = config.getBoolean("production", false)
        if (production) {
            config.set("production", true)
            config.set("production_set_at", LocalDateTime.now().toString())
            if (!wasProduction || !config.contains("submission.state")) {
                config.set("submission.state", SubmissionState.UNSUBMITTED.storageValue)
            }
        } else {
            config.set("production", null)
            config.set("production_set_at", null)
            config.set("submission", null)
        }
        saveAtomic(worldUuid, config)
    }

    fun getProductionSetAt(worldUuid: UUID): LocalDateTime? {
        val raw = load(worldUuid)?.getString("production_set_at") ?: return null
        return runCatching { LocalDateTime.parse(raw) }.getOrNull()
    }

    fun getVisibility(worldUuid: UUID): ChanponWorldVisibility {
        val config = load(worldUuid) ?: return ChanponWorldVisibility.PRIVATE
        return ChanponWorldVisibility.fromStorageValue(config.getString("visibility"))
            ?: when {
                config.getBoolean("development", false) -> ChanponWorldVisibility.DEVELOPMENT
                else -> ChanponWorldVisibility.PRIVATE
            }
    }

    @Synchronized
    fun setVisibility(worldUuid: UUID, visibility: ChanponWorldVisibility) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("visibility", visibility.storageValue)
        config.set("development", null)
        config.set("development_set_at", null)
        config.set("visibility_set_at", LocalDateTime.now().toString())
        saveAtomic(worldUuid, config)
        visibilitySyncHandler?.invoke(worldUuid)
    }

    fun getProtectionMode(worldUuid: UUID): ChanponWorldProtectionMode {
        val config = load(worldUuid) ?: return ChanponWorldProtectionMode.MEMBERS
        val value = config.getString("protection.mode") ?: return ChanponWorldProtectionMode.MEMBERS
        return ChanponWorldProtectionMode.fromStorageValue(value)
            ?: error("Protection mode is invalid: $worldUuid mode=$value")
    }

    @Synchronized
    fun setProtectionMode(worldUuid: UUID, mode: ChanponWorldProtectionMode) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("protection.mode", mode.storageValue)
        config.set("protection.updated_at", LocalDateTime.now().toString())
        saveAtomic(worldUuid, config)
    }

    fun hasProtectionPassword(worldUuid: UUID): Boolean {
        return getProtectionPassword(worldUuid) != null
    }

    fun getProtectionPassword(worldUuid: UUID): String? {
        return load(worldUuid)?.getString("protection.password")?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun setProtectionPassword(worldUuid: UUID, password: String) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("protection.password", password)
        config.set("protection.password_hash", null)
        config.set("protection.password_updated_at", LocalDateTime.now().toString())
        saveAtomic(worldUuid, config)
    }

    /** メンバー参加パスワードを削除します。未設定の間は非メンバーのパスワード参加が機能しません。 */
    @Synchronized
    fun clearProtectionPassword(worldUuid: UUID) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("protection.password", null)
        config.set("protection.password_hash", null)
        config.set("protection.password_updated_at", null)
        saveAtomic(worldUuid, config)
    }

    fun matchesProtectionPassword(worldUuid: UUID, password: String): Boolean {
        return getProtectionPassword(worldUuid) == password
    }

    @Synchronized
    fun initializeSubmissionState(worldUuid: UUID) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("submission.state", SubmissionState.UNSUBMITTED.storageValue)
        saveAtomic(worldUuid, config)
    }

    fun getSubmissionState(worldUuid: UUID): SubmissionState {
        val config = load(worldUuid)
            ?: error("MWM-Chanpon world data is missing: $worldUuid")
        val value = config.getString("submission.state")
        return SubmissionState.fromStorageValue(value)
            ?: error("Submission state is missing or invalid: $worldUuid")
    }

    /**
     * 提出状態を任意値として読み取ります。
     *
     * 現行スキーマでも、提出機能を使っていない既存ワールドには
     * `submission.state` が存在しないことがあります。現行ファイルかどうかは
     * hasCurrentWorldData() で先に検証し、ここでは未設定を nullable として
     * 扱えるよう、厳密な getSubmissionState() とは別の読み取り口にします。
     */
    fun getSubmissionStateOrNull(worldUuid: UUID): SubmissionState? {
        val config = load(worldUuid) ?: return null
        return SubmissionState.fromStorageValue(config.getString("submission.state"))
    }

    fun isFreecamAllowed(worldUuid: UUID): Boolean {
        return load(worldUuid)?.getBoolean("environment.freecam_allowed", true) ?: true
    }

    @Synchronized
    fun setFreecamAllowed(worldUuid: UUID, allowed: Boolean) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("environment.freecam_allowed", allowed)
        saveAtomic(worldUuid, config)
    }

    fun isSubmissionCompleted(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return SubmissionState.fromStorageValue(config.getString("submission.state")) == SubmissionState.COMPLETED
    }

    @Synchronized
    fun setSubmissionState(
        worldUuid: UUID,
        state: SubmissionState,
        actorUuid: UUID,
        actorName: String,
        action: String
    ) {
        ensureWritable(worldUuid)
        val config = load(worldUuid)
            ?: error("MWM-Chanpon world data is missing: $worldUuid")
        if (!config.contains("submission.state")) {
            error("Submission state is missing: $worldUuid")
        }
        config.set("submission.state", state.storageValue)
        val history = config.getMapList("submission.history").toMutableList()
        history.add(
            linkedMapOf(
                "at" to LocalDateTime.now().toString(),
                "action" to action,
                "state" to state.storageValue,
                "actor_uuid" to actorUuid.toString(),
                "actor_name" to actorName
            )
        )
        config.set("submission.history", history)
        saveAtomic(worldUuid, config)
    }

    /** 提出状態と履歴件数をCASに利用する、提出aggregateの不変スナップショットです。 */
    data class SubmissionSnapshot(
        val state: SubmissionState,
        val history: List<Map<String, Any?>>,
    ) {
        val revision: Int
            get() = history.size
    }

    @Synchronized
    fun captureSubmissionSnapshot(worldUuid: UUID): SubmissionSnapshot {
        val config = load(worldUuid)
            ?: error("MWM-Chanpon world data is missing: $worldUuid")
        val state = SubmissionState.fromStorageValue(config.getString("submission.state"))
            ?: error("Submission state is missing or invalid: $worldUuid")
        return SubmissionSnapshot(
            state,
            config.getMapList("submission.history").map { entry ->
                entry.entries.associate { (key, value) -> key.toString() to value }
            },
        )
    }

    @Synchronized
    fun restoreSubmissionSnapshot(worldUuid: UUID, snapshot: SubmissionSnapshot) {
        ensureWritable(worldUuid)
        val config = load(worldUuid)
            ?: error("MWM-Chanpon world data is missing: $worldUuid")
        config.set("submission.state", snapshot.state.storageValue)
        config.set("submission.history", snapshot.history.map { LinkedHashMap(it) })
        saveAtomic(worldUuid, config)
    }

    fun isToolEnabled(worldUuid: UUID, playerUuid: UUID, toolId: String): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("tool_permissions.${playerUuid}.${toolId}", false)
    }

    fun getToolEnabledState(worldUuid: UUID, playerUuid: UUID, toolId: String): Boolean? {
        val config = load(worldUuid) ?: return null
        val path = "tool_permissions.${playerUuid}.${toolId}"
        return if (config.contains(path)) config.getBoolean(path, false) else null
    }

    @Synchronized
    fun setToolEnabled(worldUuid: UUID, playerUuid: UUID, toolId: String, enabled: Boolean) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("tool_permissions.${playerUuid}.${toolId}", enabled)
        saveAtomic(worldUuid, config)
    }

    @Synchronized
    fun restoreToolEnabledState(worldUuid: UUID, playerUuid: UUID, toolId: String, enabled: Boolean?) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("tool_permissions.${playerUuid}.${toolId}", enabled)
        saveAtomic(worldUuid, config)
    }

    fun getMemberToolStates(worldUuid: UUID, playerUuid: UUID): Map<String, Boolean> {
        val config = load(worldUuid) ?: return emptyMap()
        val section = config.getConfigurationSection("tool_permissions.${playerUuid}") ?: return emptyMap()
        return section.getKeys(false).associateWith { section.getBoolean(it, false) }
    }

    @Synchronized
    fun removeMember(worldUuid: UUID, playerUuid: UUID) {
        ensureWritable(worldUuid)
        val config = load(worldUuid) ?: return
        config.set("tool_permissions.${playerUuid}", null)
        saveAtomic(worldUuid, config)
    }

    fun getLastBackupHash(worldUuid: UUID): String? {
        return load(worldUuid)?.getString("backup.last_hash")
    }

    @Synchronized
    fun setLastBackupHash(worldUuid: UUID, hash: String) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("backup.last_hash", hash)
        saveAtomic(worldUuid, config)
    }

    fun getOwnerManualBackupLastCreatedAt(worldUuid: UUID): Long {
        return load(worldUuid)?.getLong("backup.owner_manual.last_created_at", 0L) ?: 0L
    }

    @Synchronized
    fun setOwnerManualBackupLastCreatedAt(worldUuid: UUID, value: Long) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("backup.owner_manual.last_created_at", value)
        saveAtomic(worldUuid, config)
    }

    fun getManualRollbackLastExecutedAt(worldUuid: UUID): Long {
        return load(worldUuid)?.getLong("backup.manual_rollback.last_executed_at", 0L) ?: 0L
    }

    @Synchronized
    fun setManualRollbackLastExecutedAt(worldUuid: UUID, value: Long) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("backup.manual_rollback.last_executed_at", value)
        saveAtomic(worldUuid, config)
    }

    @Synchronized
    fun deleteWorld(worldUuid: UUID) {
        worldFile(worldUuid).delete()
    }

    fun isImportedArchive(worldUuid: UUID): Boolean {
        val config = load(worldUuid) ?: return false
        return config.getBoolean("imported_archive", false)
    }

    @Synchronized
    fun setImportedArchive(worldUuid: UUID, value: Boolean) {
        ensureWritable(worldUuid)
        val config = loadOrCreate(worldUuid)
        config.set("imported_archive", value)
        saveAtomic(worldUuid, config)
    }

    fun getCompletedAt(worldUuid: UUID): java.time.LocalDateTime? {
        val config = load(worldUuid) ?: return null
        // 既に読み込んだ設定から状態も判定し、一覧描画時に同じYAMLを再読込しないようにします。
        if (SubmissionState.fromStorageValue(config.getString("submission.state")) != SubmissionState.COMPLETED) {
            return null
        }
        val history = config.getMapList("submission.history")
        // 不正な履歴1件が混ざっていても、有効な再完成日時まで隠さないよう、
        // 文字列比較ではなく各履歴を先にLocalDateTimeへ変換してから最新値を選びます。
        return history.asSequence()
            .filter { (it["action"] as? String) == "mark_completed" }
            .mapNotNull { entry ->
                (entry["at"] as? String)?.let { raw ->
                    runCatching { java.time.LocalDateTime.parse(raw) }.getOrNull()
                }
            }
            .maxOrNull()
    }

    private fun load(worldUuid: UUID): YamlConfiguration? {
        val file = worldFile(worldUuid)
        if (!file.exists()) return null
        return try {
            YamlConfiguration.loadConfiguration(file).also {
                warnedLoadFailures.remove(worldUuid)
            }
        } catch (failure: RuntimeException) {
            // 破損したワールド別データは読み取り系呼出しから例外を広げず、既定値へフォールバックします。
            // 同じファイルが描画のたびに読まれるため、ログはファイル単位で一度だけ出します。
            if (warnedLoadFailures.add(worldUuid)) {
                logger.log(
                    Level.WARNING,
                    "MWM-Chanpon world data could not be loaded; using defaults: $file",
                    failure,
                )
            }
            null
        }
    }

    private fun loadOrCreate(worldUuid: UUID): YamlConfiguration {
        val file = worldFile(worldUuid)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun saveAtomic(worldUuid: UUID, config: YamlConfiguration) {
        config.set(
            "schema_version",
            ChanponWorldDataMigrationParticipant.CURRENT_SCHEMA_VERSION,
        )
        val target = worldFile(worldUuid)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        config.save(temporary)
        FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        val verified = YamlConfiguration.loadConfiguration(temporary)
        check(verified.getKeys(true) == config.getKeys(true)) {
            "Temporary Chanpon world data verification failed: $worldUuid"
        }
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun ensureWritable(worldUuid: UUID) {
        worldWriteGate?.let { gate ->
            check(gate(worldUuid)) {
                "Chanpon world data requires /mwm migration before it can be modified: $worldUuid"
            }
        }
    }

    private fun writeAtomicBytes(target: File, bytes: ByteArray, expectedSha256: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.restore.tmp")
        try {
            FileChannel.open(
                temporary.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            check(sha256(temporary.readBytes()) == expectedSha256) {
                "Temporary Chanpon world snapshot verification failed: ${target.name}"
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

}
