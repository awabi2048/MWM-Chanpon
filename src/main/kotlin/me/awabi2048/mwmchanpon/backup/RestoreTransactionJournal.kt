package me.awabi2048.mwmchanpon.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration

/** 復元中断後に、どの世代を採用すべきか判断するための永続フェーズです。 */
internal enum class RestoreJournalPhase {
    PREPARED,
    DATA_COMMITTED,
    PORTALS_COMMITTED,
    ROLLED_BACK,
}

internal data class RestoreJournalWorld(
    val worldUuid: UUID,
    val targetWorldPath: String,
    val temporaryWorldPath: String,
    val previousWorldPath: String,
    val previousWorldExisted: Boolean,
    val targetMetadataPath: String,
    val previousMetadataPath: String,
    val previousMetadataExisted: Boolean,
    val targetChanponPath: String,
    val previousChanponPath: String,
    val previousChanponExisted: Boolean,
    val previousPortalsPath: String,
    var previousWorldMoveStarted: Boolean,
    var previousWorldMoved: Boolean,
    var targetWorldReplaceStarted: Boolean,
    var targetWorldReplaced: Boolean,
    var rollbackWorldMoveStarted: Boolean = false,
    var phase: RestoreJournalPhase,
)

/**
 * ワールド復元のファイル操作とメインスレッド側のポータル反映を結ぶジャーナルです。
 *
 * ジャーナル自体はバックアップデータとは別の作業ディレクトリへ置き、通常完了時に
 * 作業ディレクトリごと削除します。プロセス停止時はこの記録を起動時に読み直し、
 * DATA_COMMITTED以前は原状へ戻し、PORTALS_COMMITTED後は退避物だけを掃除します。
 */
internal class RestoreTransactionJournal private constructor(
    val directory: File,
    val backupId: String,
    private val records: LinkedHashMap<UUID, RestoreJournalWorld>,
    private var operationCommitted: Boolean,
    private var snapshotsReady: Boolean,
) {
    @Synchronized
    fun worlds(): List<RestoreJournalWorld> = records.values.map { it.copy() }

    @Synchronized
    fun record(worldUuid: UUID): RestoreJournalWorld? = records[worldUuid]?.copy()

    @Synchronized
    fun markDataCommitted(worldUuid: UUID) {
        records[worldUuid]?.phase = RestoreJournalPhase.DATA_COMMITTED
        persist()
    }

    @Synchronized
    fun markSnapshotsReady() {
        snapshotsReady = true
        persist()
    }

    @Synchronized
    fun areSnapshotsReady(): Boolean = snapshotsReady

    @Synchronized
    fun markPreviousWorldMoved(worldUuid: UUID) {
        records[worldUuid]?.previousWorldMoved = true
        persist()
    }

    @Synchronized
    fun markPreviousWorldMoveStarted(worldUuid: UUID) {
        records[worldUuid]?.previousWorldMoveStarted = true
        persist()
    }

    @Synchronized
    fun markTargetWorldReplaced(worldUuid: UUID) {
        records[worldUuid]?.targetWorldReplaced = true
        persist()
    }

    @Synchronized
    fun markTargetWorldReplaceStarted(worldUuid: UUID) {
        records[worldUuid]?.targetWorldReplaceStarted = true
        persist()
    }

    @Synchronized
    fun markRollbackWorldMoveStarted(worldUuid: UUID) {
        records[worldUuid]?.rollbackWorldMoveStarted = true
        persist()
    }

    @Synchronized
    fun markPortalsCommitted(worldUuid: UUID) {
        records[worldUuid]?.phase = RestoreJournalPhase.PORTALS_COMMITTED
        persist()
    }

    @Synchronized
    fun markRolledBack(worldUuid: UUID) {
        records[worldUuid]?.phase = RestoreJournalPhase.ROLLED_BACK
        persist()
    }

    @Synchronized
    fun isFinished(): Boolean = records.values.all {
        it.phase == RestoreJournalPhase.PORTALS_COMMITTED ||
            it.phase == RestoreJournalPhase.ROLLED_BACK
    }

    @Synchronized
    fun isOperationCommitted(): Boolean = operationCommitted

    @Synchronized
    fun markOperationCommitted() {
        operationCommitted = true
        persist()
    }

    @Synchronized
    fun delete() {
        if (directory.exists() && !directory.deleteRecursively()) {
            error("Could not delete restore transaction journal: ${directory.absolutePath}")
        }
    }

    @Synchronized
    private fun persist() {
        val config = YamlConfiguration()
        config.set("backup_id", backupId)
        config.set("operation_committed", operationCommitted)
        config.set("snapshots_ready", snapshotsReady)
        config.set(
            "worlds",
            records.values.map { record ->
                linkedMapOf(
                    "uuid" to record.worldUuid.toString(),
                    "target_world_path" to record.targetWorldPath,
                    "temporary_world_path" to record.temporaryWorldPath,
                    "previous_world_path" to record.previousWorldPath,
                    "previous_world_existed" to record.previousWorldExisted,
                    "target_metadata_path" to record.targetMetadataPath,
                    "previous_metadata_path" to record.previousMetadataPath,
                    "previous_metadata_existed" to record.previousMetadataExisted,
                    "target_chanpon_path" to record.targetChanponPath,
                    "previous_chanpon_path" to record.previousChanponPath,
                    "previous_chanpon_existed" to record.previousChanponExisted,
                    "previous_portals_path" to record.previousPortalsPath,
                    "previous_world_move_started" to record.previousWorldMoveStarted,
                    "previous_world_moved" to record.previousWorldMoved,
                    "target_world_replace_started" to record.targetWorldReplaceStarted,
                    "target_world_replaced" to record.targetWorldReplaced,
                    "rollback_world_move_started" to record.rollbackWorldMoveStarted,
                    "phase" to record.phase.name,
                )
            },
        )
        val temporary = File(directory, "journal.yml.tmp")
        val target = File(directory, "journal.yml")
        directory.mkdirs()
        try {
            config.save(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    companion object {
        fun allocateDirectory(rootDir: File): File {
            val directory = File(File(rootDir, "restore-transactions"), UUID.randomUUID().toString())
            require(directory.mkdirs()) { "Could not create restore transaction directory: $directory" }
            return directory
        }

        fun create(directory: File, backupId: String, records: List<RestoreJournalWorld>): RestoreTransactionJournal {
            require(records.isNotEmpty()) { "restore transaction must contain at least one world" }
            val journal = RestoreTransactionJournal(
                directory = directory,
                backupId = backupId,
                records = records.associateByTo(LinkedHashMap()) { it.worldUuid },
                operationCommitted = false,
                snapshotsReady = false,
            )
            journal.persist()
            return journal
        }

        fun load(directory: File): RestoreTransactionJournal? {
            val file = File(directory, "journal.yml")
            if (!file.isFile) return null
            return runCatching {
                val config = YamlConfiguration.loadConfiguration(file)
                val backupId = config.getString("backup_id")?.takeIf { it.isNotBlank() }
                    ?: error("backup_id is missing")
                val operationCommitted = config.getBoolean("operation_committed", false)
                val snapshotsReady = config.getBoolean("snapshots_ready", false)
                val rawRecords = config.getMapList("worlds")
                require(rawRecords.isNotEmpty()) { "worlds is missing or empty" }
                val records = rawRecords.map { map ->
                    val uuid = UUID.fromString(map["uuid"]?.toString())
                    fun requiredString(key: String): String = map[key]?.toString()?.takeIf { it.isNotBlank() }
                        ?: error("$key is missing for $uuid")
                    fun requiredBoolean(key: String): Boolean = map[key] as? Boolean
                        ?: error("$key is invalid for $uuid")
                    RestoreJournalWorld(
                        worldUuid = uuid,
                        targetWorldPath = requiredString("target_world_path"),
                        temporaryWorldPath = requiredString("temporary_world_path"),
                        previousWorldPath = requiredString("previous_world_path"),
                        previousWorldExisted = requiredBoolean("previous_world_existed"),
                        targetMetadataPath = requiredString("target_metadata_path"),
                        previousMetadataPath = requiredString("previous_metadata_path"),
                        previousMetadataExisted = requiredBoolean("previous_metadata_existed"),
                        targetChanponPath = requiredString("target_chanpon_path"),
                        previousChanponPath = requiredString("previous_chanpon_path"),
                        previousChanponExisted = requiredBoolean("previous_chanpon_existed"),
                        previousPortalsPath = requiredString("previous_portals_path"),
                        previousWorldMoveStarted = requiredBoolean("previous_world_move_started"),
                        previousWorldMoved = requiredBoolean("previous_world_moved"),
                        targetWorldReplaceStarted = requiredBoolean("target_world_replace_started"),
                        targetWorldReplaced = requiredBoolean("target_world_replaced"),
                        rollbackWorldMoveStarted = map["rollback_world_move_started"] as? Boolean ?: false,
                        phase = RestoreJournalPhase.valueOf(requiredString("phase")),
                    )
                }
                require(records.map { it.worldUuid }.toSet().size == records.size) {
                    "worlds contains duplicate UUIDs"
                }
                RestoreTransactionJournal(
                    directory = directory,
                    backupId = backupId,
                    records = records.associateByTo(LinkedHashMap()) { it.worldUuid },
                    operationCommitted = operationCommitted,
                    snapshotsReady = snapshotsReady,
                )
            }.getOrElse { error("Could not read restore transaction journal: ${file.absolutePath}: ${it.message}") }
        }

        fun pending(rootDir: File): List<RestoreTransactionJournal> {
            return directories(rootDir).mapNotNull { directory -> runCatching { load(directory) }.getOrNull() }
        }

        fun directories(rootDir: File): List<File> {
            val transactions = File(rootDir, "restore-transactions")
            return transactions.listFiles { file -> file.isDirectory }
                .orEmpty()
                .sortedBy { it.name }
        }
    }
}
