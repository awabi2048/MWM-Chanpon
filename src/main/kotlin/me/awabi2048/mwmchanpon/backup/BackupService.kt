package me.awabi2048.mwmchanpon.backup

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.service.PortalSnapshotCodec
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot
import me.awabi2048.myworldmanager.api.service.ApiWorldRepository
import me.awabi2048.myworldmanager.api.service.ApiWorldService
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import me.awabi2048.myworldmanager.api.service.WorldOperation
import me.awabi2048.myworldmanager.api.service.WorldOperationLease

class BackupService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) {
    private val rootDir = File(plugin.dataFolder, "backups")
    private val activeBackupIds = ConcurrentHashMap.newKeySet<String>()
    private val createdFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    @Volatile private var restoreRecoveryFailure: String? = null
    private var postRestoreHandler: (List<UUID>) -> Unit = {}

    init {
        rootDir.mkdirs()
        recoverInterruptedRestoreTransactions()
    }

    fun listBackups(): List<BackupEntry> {
        return rootDir.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { readManifest(it) }
            .sortedByDescending { it.createdAt }
    }

    fun listBackupsForWorld(worldUuid: UUID): List<BackupEntry> {
        // 一括バックアップにも対象UUIDが含まれるため、対象ワールド画面ではscopeを必ず限定します。
        return listBackups().filter { it.isDedicatedWorldBackupFor(worldUuid) }
    }

    fun latestRestorePoint(): BackupEntry? {
        return listBackups().firstOrNull { it.kind == BackupKind.RESTORE_POINT && it.scope == BackupScope.ALL }
    }

    fun setPostRestoreHandler(handler: (List<UUID>) -> Unit) {
        postRestoreHandler = handler
    }

    /**
     * 前回プロセスが復元途中で停止した場合、起動時にファイル世代を一つへ戻します。
     * ジャーナルが壊れている場合は推測復旧せず、手動確認が必要な状態として残します。
     */
    private fun recoverInterruptedRestoreTransactions() {
        RestoreOperationMarker.directories(rootDir).forEach { directory ->
            val marker = runCatching { RestoreOperationMarker.load(directory) }.getOrNull()
            if (marker == null) {
                if (directory.listFiles().orEmpty().isEmpty()) {
                    directory.delete()
                    return@forEach
                }
                restoreRecoveryFailure = "restore operation marker is unreadable: ${directory.absolutePath}"
                plugin.logger.severe("Could not read interrupted restore operation marker: $directory")
                return@forEach
            }
            runCatching {
                if (marker.isCommitted()) {
                    // 操作マーカーを複数ワールド復元のコミット記録とします。
                    // コミット後の個別ジャーナルは、プロセス停止時に各ジャーナルの
                    // operation_committed が保存されていなくても後始末用の記録です。
                    marker.journals().forEach { journalDirectory ->
                        if (journalDirectory.exists()) {
                            val journal = RestoreTransactionJournal.load(journalDirectory)
                                ?: error("restore journal is unreadable: $journalDirectory")
                            journal.worlds().forEach(::cleanupInterruptedWorld)
                            journal.delete()
                        }
                        marker.removeJournal(journalDirectory)
                    }
                    marker.delete()
                } else {
                    marker.journals().forEach { journalDirectory ->
                        if (journalDirectory.exists()) {
                            val journal = RestoreTransactionJournal.load(journalDirectory)
                                ?: error("restore journal is unreadable: $journalDirectory")
                            rollbackRestoreJournal(journal, MyWorldManagerApi.getWorldService())
                        }
                        // rollbackRestoreJournal はロールバック完了後にジャーナルを削除します。
                        // その後でマーカーからも外すことで、復旧途中の停止を再実行可能にします。
                        marker.removeJournal(journalDirectory)
                    }
                    marker.delete()
                }
            }.onFailure { error ->
                restoreRecoveryFailure = "restore operation recovery failed: ${error.message ?: error.javaClass.simpleName}"
                plugin.logger.log(
                    java.util.logging.Level.SEVERE,
                    "Could not recover interrupted restore operation marker: $directory",
                    error,
                )
            }
        }
        RestoreTransactionJournal.directories(rootDir).forEach { directory ->
            val journal = RestoreTransactionJournal.load(directory) ?: run {
                if (directory.listFiles().orEmpty().isEmpty()) {
                    directory.delete()
                    return@forEach
                }
                restoreRecoveryFailure = "restore recovery journal is unreadable: ${directory.absolutePath}"
                plugin.logger.severe("Could not read interrupted backup restore journal: $directory")
                return@forEach
            }
            runCatching {
                if (journal.isOperationCommitted()) {
                    journal.worlds().forEach(::cleanupInterruptedWorld)
                    journal.delete()
                    return@runCatching
                }
                rollbackRestoreJournal(journal, MyWorldManagerApi.getWorldService())
                plugin.logger.warning("Recovered interrupted backup restore transaction: ${journal.backupId}")
            }.onFailure { error ->
                restoreRecoveryFailure = "restore recovery failed: ${error.message ?: error.javaClass.simpleName}"
                plugin.logger.log(
                    java.util.logging.Level.SEVERE,
                    "Could not recover interrupted backup restore transaction: ${journal.directory}",
                    error,
                )
            }
        }
    }

    private fun rollbackInterruptedWorld(record: RestoreJournalWorld) {
        val targetWorld = File(record.targetWorldPath)
        val temporaryWorld = File(record.temporaryWorldPath)
        val previousWorld = File(record.previousWorldPath)
        if (record.targetWorldReplaced && targetWorld.exists()) {
            require(targetWorld.deleteRecursively()) { "Could not remove interrupted restored world: $targetWorld" }
        }
        if (record.previousWorldExisted) {
            if (previousWorld.exists() && !targetWorld.exists()) {
                moveWithoutReplacement(previousWorld, targetWorld)
            } else if (previousWorld.exists() && targetWorld.exists()) {
                require(targetWorld.deleteRecursively()) { "Could not remove interrupted restored world: $targetWorld" }
                moveWithoutReplacement(previousWorld, targetWorld)
            } else if (!targetWorld.exists()) {
                error("Original world directory is missing during recovery: $targetWorld")
            }
        } else if (targetWorld.exists() && record.targetWorldReplaceStarted) {
            // targetWorldReplaceStarted is persisted before the replacement
            // move, so an unmarked target is not removed as an unrelated
            // directory when the original target did not exist.
            require(targetWorld.deleteRecursively()) { "Could not remove interrupted new world: $targetWorld" }
        }
        if (temporaryWorld.exists()) require(temporaryWorld.deleteRecursively()) {
            "Could not remove interrupted temporary world: $temporaryWorld"
        }
        restoreFileSnapshot(
            target = File(record.targetMetadataPath),
            snapshot = File(record.previousMetadataPath),
            existed = record.previousMetadataExisted,
        )
        restoreFileSnapshot(
            target = File(record.targetChanponPath),
            snapshot = File(record.previousChanponPath),
            existed = record.previousChanponExisted,
        )
    }

    /**
     * スナップショット作成を独立したジャーナル段階として扱います。これが永続化される前に
     * 停止した場合、対象ワールドとメタデータはまだ置換されていないため準備用ファイルだけを
     * 削除します。不完全なスナップショットから復元するより、確認が必要な状態で停止する方が安全です。
     */
    private fun cleanupPreparedRestoreArtifacts(record: RestoreJournalWorld) {
        val previousWorld = File(record.previousWorldPath)
        require(!previousWorld.exists()) {
            "Previous world appeared before restore snapshots were ready: $previousWorld"
        }
        listOf(
            File(record.temporaryWorldPath),
            File(record.previousMetadataPath),
            File(record.previousChanponPath),
            File(record.previousPortalsPath),
        ).forEach { file ->
            if (file.isDirectory) require(file.deleteRecursively()) { "Could not remove restore artifact: $file" }
            else Files.deleteIfExists(file.toPath())
        }
    }

    private fun cleanupInterruptedWorld(record: RestoreJournalWorld) {
        listOf(
            File(record.temporaryWorldPath),
            File(record.previousWorldPath),
            File(record.previousMetadataPath),
            File(record.previousChanponPath),
            File(record.previousPortalsPath),
        ).forEach { file ->
            if (file.isDirectory) require(file.deleteRecursively()) { "Could not remove restore artifact: $file" }
            else Files.deleteIfExists(file.toPath())
        }
    }

    private fun restoreFileSnapshot(target: File, snapshot: File, existed: Boolean) {
        if (!existed) {
            Files.deleteIfExists(target.toPath())
            return
        }
        require(snapshot.isFile) { "Restore snapshot is missing: $snapshot" }
        target.parentFile?.mkdirs()
        Files.copy(snapshot.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun moveWithoutReplacement(source: File, target: File) {
        target.parentFile?.mkdirs()
        require(!target.exists()) { "Restore target already exists during recovery: $target" }
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    /** 実行中のポータル同期失敗時にも、ジャーナル単位でファイルとポータルを戻します。 */
    private fun rollbackRestoreJournal(journal: RestoreTransactionJournal, core: ApiWorldService?) {
        if (!journal.areSnapshotsReady()) {
            journal.worlds().forEach { record ->
                if (record.phase != RestoreJournalPhase.ROLLED_BACK) {
                    require(!record.previousWorldMoveStarted && !record.targetWorldReplaceStarted) {
                        "Restore journal reached world replacement before snapshots were ready: ${record.worldUuid}"
                    }
                }
                cleanupPreparedRestoreArtifacts(record)
                if (record.phase != RestoreJournalPhase.ROLLED_BACK) {
                    journal.markRolledBack(record.worldUuid)
                }
            }
            journal.delete()
            return
        }
        val service = core ?: error("core api unavailable during restore rollback")
        val repository = MyWorldManagerApi.getWorldRepository() ?: error("world repository unavailable during restore rollback")
        service.reloadWorldData()
        journal.worlds()
            .forEach { record ->
                if (record.phase != RestoreJournalPhase.ROLLED_BACK) {
                    journal.markRollbackWorldMoveStarted(record.worldUuid)
                    rollbackInterruptedWorld(record)
                    val worldData = repository.findByUuid(record.worldUuid)
                        ?: error("world data missing during restore rollback: ${record.worldUuid}")
                    val portals = PortalSnapshotCodec.read(
                        File(record.previousPortalsPath),
                        expectedWorldUuid = record.worldUuid,
                        expectedWorldKey = worldData.worldKey,
                    )
                    service.replaceRelatedPortals(record.worldUuid, portals)
                    journal.markRolledBack(record.worldUuid)
                }
                cleanupInterruptedWorld(journal.record(record.worldUuid) ?: record)
            }
        service.reloadPortalData()
        journal.delete()
    }

    private fun rollbackJournalFilesOnly(journal: RestoreTransactionJournal) {
        if (!journal.areSnapshotsReady()) {
            journal.worlds().forEach { record ->
                if (record.phase != RestoreJournalPhase.ROLLED_BACK) {
                    require(!record.previousWorldMoveStarted && !record.targetWorldReplaceStarted) {
                        "Restore journal reached world replacement before snapshots were ready: ${record.worldUuid}"
                    }
                }
                cleanupPreparedRestoreArtifacts(record)
                if (record.phase != RestoreJournalPhase.ROLLED_BACK) {
                    journal.markRolledBack(record.worldUuid)
                }
            }
            journal.delete()
            return
        }
        journal.worlds()
            .forEach { record ->
                if (record.phase != RestoreJournalPhase.ROLLED_BACK) {
                    journal.markRollbackWorldMoveStarted(record.worldUuid)
                    rollbackInterruptedWorld(record)
                    journal.markRolledBack(record.worldUuid)
                }
                cleanupInterruptedWorld(journal.record(record.worldUuid) ?: record)
            }
        journal.delete()
    }

    private fun rollbackOperationMarker(marker: RestoreOperationMarker) {
        if (marker.isCommitted()) {
            marker.journals().forEach { journalDirectory ->
                if (journalDirectory.exists()) {
                    val journal = RestoreTransactionJournal.load(journalDirectory)
                        ?: error("restore journal is unreadable: $journalDirectory")
                    journal.worlds().forEach(::cleanupInterruptedWorld)
                    journal.delete()
                }
                marker.removeJournal(journalDirectory)
            }
            marker.delete()
            return
        }
        marker.journals().forEach { directory ->
            if (directory.exists()) {
                val journal = RestoreTransactionJournal.load(directory)
                    ?: error("restore journal is unreadable: $directory")
                rollbackRestoreJournal(journal, MyWorldManagerApi.getWorldService())
            }
            marker.removeJournal(directory)
        }
        marker.delete()
    }

    fun createWorldBackup(
        worldUuid: UUID,
        kind: BackupKind,
        actorUuid: UUID?,
        actorName: String?,
        reason: String? = null,
        name: String? = BackupDisplay.defaultName(kind)
    ): CompletableFuture<BackupOperationResult> {
        return createBackup(listOf(worldUuid), BackupScope.WORLD, kind, actorUuid, actorName, reason, name, acquireLocks = true)
    }

    fun createOwnerWorldBackup(
        worldUuid: UUID,
        actorUuid: UUID,
        actorName: String,
        reason: String? = null,
        name: String? = BackupDisplay.DEFAULT_OWNER_MANUAL_NAME
    ): CompletableFuture<BackupOperationResult> {
        val remainingSeconds = ownerBackupCooldownRemainingSeconds(worldUuid)
        if (remainingSeconds > 0) {
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.FAILED, null, "cooldown:$remainingSeconds")
            )
        }
        return createBackup(listOf(worldUuid), BackupScope.WORLD, BackupKind.USER_MANUAL, actorUuid, actorName, reason, name, acquireLocks = true)
            .thenApply { result ->
                if (result.status == BackupOperationStatus.SUCCESS) {
                    markOwnerBackupCreated(worldUuid)
                }
                result
            }
    }

    fun ownerBackupCooldownRemainingSeconds(worldUuid: UUID): Long {
        val remaining = ownerBackupCooldownRemainingMillis(worldUuid)
        if (remaining <= 0L) return 0L
        return (remaining + 999L) / 1000L
    }

    fun manualRollbackCooldownRemainingSeconds(worldUuids: List<UUID>): Long {
        val remaining = worldUuids.distinct().maxOfOrNull(::manualRollbackCooldownRemainingMillis) ?: 0L
        if (remaining <= 0L) return 0L
        return (remaining + 999L) / 1000L
    }

    fun markManualRollbackExecuted(worldUuids: List<UUID>) {
        val now = System.currentTimeMillis()
        worldUuids.distinct().forEach { storage.setManualRollbackLastExecutedAt(it, now) }
    }

    fun createAllBackup(
        worldUuids: List<UUID>,
        kind: BackupKind,
        actorUuid: UUID?,
        actorName: String?,
        reason: String? = null,
        name: String? = BackupDisplay.defaultName(kind)
    ): CompletableFuture<BackupOperationResult> {
        return createBackup(worldUuids, BackupScope.ALL, kind, actorUuid, actorName, reason, name, acquireLocks = true)
    }

    fun createChangedScheduledBackup(): CompletableFuture<BackupOperationResult> {
        val worlds = productionWorlds().filter(::hasChangedSinceLastBackup)
        if (worlds.isEmpty()) {
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.SUCCESS, null, "no changed production worlds")
            )
        }
        return createAllBackup(worlds.map { it.uuid }, BackupKind.SCHEDULED, null, "system", "scheduled", BackupDisplay.SCHEDULED_NAME)
    }

    fun restoreBackup(entry: BackupEntry): CompletableFuture<RestoreOperationResult> {
        return restoreBackup(entry, entry.worldUuids)
    }

    fun restorePreflightFailure(
        entry: BackupEntry,
        requestedWorlds: List<UUID> = entry.worldUuids,
        includeBackupLock: Boolean = true,
    ): String? {
        restoreRecoveryFailure?.let { return it }
        val core = MyWorldManagerApi.getWorldService()
        val repository = MyWorldManagerApi.getWorldRepository()
        val distinctTargets = requestedWorlds.distinct()
        val targetStates = distinctTargets.map { worldUuid ->
            val worldData = repository?.findByUuid(worldUuid)
            val worldMigrationCurrent = !MyWorldManagerApi.isWorldMigrationPending(worldUuid)
            val chanponDataCurrent = storage.isWorldDataCurrent(worldUuid)
            val targetDirectory = if (core != null && worldData != null && worldMigrationCurrent && chanponDataCurrent) {
                core.getWorldDirectory(worldData)
            } else {
                null
            }
            val targetParent = targetDirectory?.parentFile
            RestorePreflightTarget(
                includedInBackup = worldUuid in entry.worldUuids,
                productionWorld = storage.isProductionWorld(worldUuid),
                worldDataAvailable = worldData != null,
                archiveReadable =
                    File(backupDir(entry.id), "worlds/$worldUuid/world.zip")
                        .let { it.isFile && it.canRead() && it.length() > 0L },
                targetDirectoryWritable =
                    targetParent != null &&
                        targetParent.isDirectory &&
                        targetParent.canWrite(),
                worldMigrationCurrent = worldMigrationCurrent,
                chanponDataCurrent = chanponDataCurrent,
            )
        }
        val backupMetadataFailure = repository?.let { worldRepository ->
            distinctTargets.firstNotNullOfOrNull { worldUuid ->
                diagnoseBackupMetadata(worldRepository, entry.id, worldUuid)?.also { detail ->
                    plugin.logger.warning(
                        "Rollback preflight: backup world metadata check failed: backup=${entry.id} world=$worldUuid detail=$detail"
                    )
                }
            }
        }
        val targetSet = distinctTargets.toSet()
        val evacuationRequired = Bukkit.getOnlinePlayers().any { player ->
            repository?.findByWorldName(player.world.name)?.uuid in targetSet
        }
        return RestorePreflightPolicy.failureReason(
            backupLocked = includeBackupLock && entry.id in activeBackupIds,
            coreAvailable = core != null && repository != null,
            globalMigrationPending = MyWorldManagerApi.isGlobalMigrationPending(),
            targets = targetStates,
            evacuationRequired = evacuationRequired,
            evacuationAvailable = MyWorldManagerApi.getEvacuationLocationOverride() != null
        ) ?: backupMetadataFailure?.let { "backup world metadata is missing or invalid" }
    }

    /**
     * バックアップ内メタデータの検証失敗理由を返す。正常時は null。
     * 判定文言は変えず、詳細は呼び出し元のサーバーログで原因特定に使う。
     */
    private fun diagnoseBackupMetadata(
        worldRepository: ApiWorldRepository,
        backupId: String,
        worldUuid: UUID,
    ): String? {
        val current = worldRepository.findByUuid(worldUuid) ?: return "target world data is missing"
        val base = File(backupDir(backupId), "worlds/$worldUuid")
        val restored = runCatching {
            worldRepository.readCurrentDataFile(File(base, "mwm.yml"), worldUuid)
        }.getOrElse { return "mwm.yml is missing or unreadable: ${it.message}" }
        if (restored.worldKey != current.worldKey) {
            return "world_key mismatch: backup=${restored.worldKey} current=${current.worldKey}"
        }
        if (restored.dimension != current.dimension) {
            return "dimension mismatch: backup=${restored.dimension} current=${current.dimension}"
        }
        val sourceChanpon = File(base, "chanpon.yml")
        if (sourceChanpon.exists()) {
            runCatching { storage.validateExternalWorldFile(sourceChanpon, worldUuid) }
                .onFailure { return "chanpon.yml is invalid: ${it.message}" }
        }
        runCatching {
            PortalSnapshotCodec.read(
                File(base, "portals.yml"),
                expectedWorldUuid = worldUuid,
                expectedWorldKey = current.worldKey,
            )
        }.onFailure { return "portals.yml is missing or invalid: ${it.message}" }
        return null
    }

    fun restoreWorldBackup(entry: BackupEntry, worldUuid: UUID): CompletableFuture<RestoreOperationResult> {
        if (worldUuid !in entry.worldUuids) {
            return CompletableFuture.completedFuture(
                restoreFailure(entry, listOf(worldUuid), "world is not included in backup")
            )
        }
        return restoreBackup(entry, listOf(worldUuid))
    }

    private fun restoreBackup(entry: BackupEntry, requestedWorlds: List<UUID>): CompletableFuture<RestoreOperationResult> {
        restorePreflightFailure(entry, requestedWorlds)?.let { reason ->
            return CompletableFuture.completedFuture(
                restoreFailure(entry, requestedWorlds, reason)
            )
        }
        if (!activeBackupIds.add(entry.id)) {
            return CompletableFuture.completedFuture(
                restoreFailure(entry, requestedWorlds, "backup is locked")
            )
        }
        val targets = requestedWorlds.distinct().filter { storage.isProductionWorld(it) }
        if (targets.isEmpty()) {
            activeBackupIds.remove(entry.id)
            return CompletableFuture.completedFuture(
                restoreFailure(entry, requestedWorlds, "no production worlds")
            )
        }
        val leases = targets.mapNotNull {
            MyWorldManagerApi.tryAcquireWorldOperation(it, WorldOperation.RESTORE)
        }
        if (leases.size != targets.size) {
            leases.forEach(WorldOperationLease::close)
            activeBackupIds.remove(entry.id)
            return CompletableFuture.completedFuture(
                restoreFailure(entry, targets, "world is locked")
            )
        }

        // ロック取得後にも移行状態が変わり得るため、副作用開始直前に再検証します。
        restorePreflightFailure(entry, requestedWorlds, includeBackupLock = false)?.let { reason ->
            leases.forEach(WorldOperationLease::close)
            activeBackupIds.remove(entry.id)
            return CompletableFuture.completedFuture(restoreFailure(entry, targets, reason))
        }

        val future = CompletableFuture<RestoreOperationResult>()
        val core = MyWorldManagerApi.getWorldService()
        if (core == null) {
            leases.forEach(WorldOperationLease::close)
            activeBackupIds.remove(entry.id)
            future.complete(restoreFailure(entry, targets, "core api unavailable"))
            return future
        }

        val evacuatedPlayers = evacuatePlayers(targets)
        if (evacuatedPlayers == null) {
            leases.forEach(WorldOperationLease::close)
            activeBackupIds.remove(entry.id)
            future.complete(restoreFailure(entry, targets, "spawn world is not configured or unavailable"))
            return future
        }
        unloadSequential(targets, save = true, leases.associateBy { it.worldUuid }).thenCompose { unloaded ->
            if (!unloaded) {
                CompletableFuture.completedFuture(
                    RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, "failed to unload worlds")
                )
            } else {
                createBackup(targets, BackupScope.ALL, BackupKind.PRE_ROLLBACK, null, "system", "pre_rollback:${entry.id}", BackupDisplay.PRE_ROLLBACK_NAME, acquireLocks = false)
                    .thenCompose { preResult ->
                        val preBackup = preResult.backup
                        if (preResult.status != BackupOperationStatus.SUCCESS || preBackup == null) {
                            CompletableFuture.completedFuture(
                                RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, preBackup, "failed to create pre rollback backup")
                            )
                        } else {
                            restoreFilesAsync(entry, targets).thenCompose { restoreResult ->
                                if (restoreResult.status == BackupOperationStatus.SUCCESS) {
                                    CompletableFuture.completedFuture(restoreResult.copy(preRollbackBackup = preBackup))
                                } else {
                                    restoreFilesAsync(preBackup, targets).thenApply { recovery ->
                                        if (recovery.status == BackupOperationStatus.SUCCESS) {
                                            RestoreOperationResult(
                                                BackupOperationStatus.RECOVERED,
                                                recovery.restoredWorlds,
                                                restoreResult.failedWorlds,
                                                preBackup,
                                                "restore failed; recovered original state"
                                            )
                                        } else {
                                            RestoreOperationResult(
                                                BackupOperationStatus.RECOVERY_FAILED,
                                                recovery.restoredWorlds,
                                                restoreResult.failedWorlds,
                                                preBackup,
                                                "restore failed; recovery also failed"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }.whenComplete { result, throwable ->
            leases.forEach(WorldOperationLease::close)
            activeBackupIds.remove(entry.id)
            if (throwable != null) {
                val failedResult =
                    RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, throwable.message ?: "restore failed")
                logRestoreFailure(entry, failedResult, throwable)
                future.complete(failedResult)
            } else {
                logRestoreFailure(entry, result)
                val restoredWorlds = result.restoredWorlds.distinct()
                if (restoredWorlds.isNotEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        postRestoreHandler(restoredWorlds)
                        restoreEvacuatedPlayers(evacuatedPlayers, result)
                    })
                }
                future.complete(result)
            }
        }
        return future
    }

    private fun restoreFailure(
        entry: BackupEntry?,
        failedWorlds: List<UUID>,
        message: String,
        restoredWorlds: List<UUID> = emptyList(),
        preRollbackBackup: BackupEntry? = null,
        status: BackupOperationStatus = BackupOperationStatus.FAILED
    ): RestoreOperationResult {
        val result = RestoreOperationResult(status, restoredWorlds, failedWorlds, preRollbackBackup, message)
        logRestoreFailure(entry, result)
        return result
    }

    private fun logRestoreFailure(
        entry: BackupEntry?,
        result: RestoreOperationResult,
        throwable: Throwable? = null
    ) {
        if (result.status == BackupOperationStatus.SUCCESS) return
        val message = buildString {
            append("Rollback failed: status=${result.status}")
            append(", backup=${entry?.id ?: "-"}")
            append(", message=${result.message}")
            append(", restored=${result.restoredWorlds.joinToString()}")
            append(", failed=${result.failedWorlds.joinToString()}")
            append(", preRollback=${result.preRollbackBackup?.id ?: "-"}")
        }
        if (result.status == BackupOperationStatus.RECOVERY_FAILED) {
            plugin.logger.severe(message)
        } else {
            plugin.logger.warning(message)
        }
        if (throwable != null) {
            plugin.logger.warning("Rollback exception: ${throwable.message}")
        }
    }

    fun deleteBackup(entry: BackupEntry): Boolean {
        if (!activeBackupIds.add(entry.id)) return false
        val dir = backupDir(entry.id)
        return try {
            dir.exists() && dir.deleteRecursively()
        } finally {
            activeBackupIds.remove(entry.id)
        }
    }

    fun applyRetention() {
        if (!plugin.config.getBoolean("backup.retention.enabled", false)) return
        BackupKind.entries.forEach { kind ->
            val keep = plugin.config.getInt("backup.retention.keep.${kind.storageValue}", -1)
            if (keep < 0) return@forEach
            val backups = listBackups().filter { it.kind == kind }.sortedByDescending { it.createdAt }
            backups.drop(keep).forEach(::deleteBackup)
        }
    }

    private fun createBackup(
        worldUuids: List<UUID>,
        scope: BackupScope,
        kind: BackupKind,
        actorUuid: UUID?,
        actorName: String?,
        reason: String?,
        name: String?,
        acquireLocks: Boolean
    ): CompletableFuture<BackupOperationResult> {
        val targets = worldUuids.distinct().filter { storage.isProductionWorld(it) }
        if (targets.isEmpty()) {
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.FAILED, null, "no production worlds")
            )
        }
        backupPreflightFailure(targets, includeUnresolved = scope == BackupScope.ALL)?.let { reason ->
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.FAILED, null, reason)
            )
        }
        val leases = if (acquireLocks) {
            targets.mapNotNull { MyWorldManagerApi.tryAcquireWorldOperation(it, WorldOperation.BACKUP) }
        } else {
            emptyList()
        }
        if (acquireLocks && leases.size != targets.size) {
            leases.forEach(WorldOperationLease::close)
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.FAILED, null, "world is locked")
            )
        }
        // ロック取得中に migration が開始されても、ワールド保存を先行させないため再検証します。
        backupPreflightFailure(targets, includeUnresolved = scope == BackupScope.ALL)?.let { reason ->
            leases.forEach(WorldOperationLease::close)
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.FAILED, null, reason)
            )
        }
        runCatching { saveLoadedWorlds(targets) }.getOrElse { ex ->
            leases.forEach(WorldOperationLease::close)
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.FAILED, null, ex.message ?: "failed to save worlds")
            )
        }
        val id = "${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}-${UUID.randomUUID()}"
        val dir = backupDir(id)
        val future = CompletableFuture<BackupOperationResult>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                dir.mkdirs()
                targets.forEach { copyWorldSnapshot(it, dir) }
                val entry = writeManifest(dir, id, scope, kind, targets, actorUuid, actorName, reason, name)
                writeWorldHashes(entry)
                if (kind != BackupKind.PRE_ROLLBACK) {
                    applyRetention()
                }
                future.complete(BackupOperationResult(BackupOperationStatus.SUCCESS, entry, "backup created"))
            } catch (ex: Exception) {
                plugin.logger.warning("Backup failed: ${ex.message}")
                dir.deleteRecursively()
                future.complete(BackupOperationResult(BackupOperationStatus.FAILED, null, ex.message ?: "backup failed"))
            } finally {
                leases.forEach(WorldOperationLease::close)
            }
        })
        return future
    }

    /**
     * バックアップ作成は原本の読み取りだけに見えて、実際にはロード済みワールドの保存と
     * last_backup_hashの更新を伴います。移行対象を含む状態で「正常なバックアップ」を
     * 作らないよう、副作用開始前に対象集合を一括検査します。
     */
    private fun backupPreflightFailure(targets: List<UUID>, includeUnresolved: Boolean): String? {
        restoreRecoveryFailure?.let { return it }
        val evaluation = MyWorldManagerApi.evaluateMigration(
            worldUuids = targets,
            includeGlobal = true,
            includeUnresolved = includeUnresolved,
        )
        if (!evaluation.allowed) return evaluation.blocks.first().message
        if (targets.any { !storage.isWorldDataCurrent(it) }) return "Chanpon world data requires /mwm migration"
        return null
    }

    private fun restoreFilesAsync(entry: BackupEntry, targets: List<UUID>): CompletableFuture<RestoreOperationResult> {
        val future = CompletableFuture<RestoreOperationResult>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            restoreRecoveryFailure?.let { reason ->
                future.complete(
                    RestoreOperationResult(
                        BackupOperationStatus.FAILED,
                        emptyList(),
                        targets,
                        null,
                        reason,
                    ),
                )
                return@Runnable
            }
            val restored = mutableListOf<UUID>()
            val failed = mutableListOf<UUID>()
            val portalsByWorld = mutableMapOf<UUID, List<ApiPortalSnapshot>>()
            val journalsByWorld = mutableMapOf<UUID, RestoreTransactionJournal>()
            var operationCommitAttempted = false
            var operationCommitPersisted = false
            val core = MyWorldManagerApi.getWorldService()
            if (core == null) {
                future.complete(RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, "core api unavailable"))
                return@Runnable
            }
            val operationMarker = runCatching { RestoreOperationMarker.create(rootDir, entry.id) }
                .getOrElse { error ->
                    plugin.logger.log(
                        java.util.logging.Level.SEVERE,
                        "Could not create restore operation marker",
                        error,
                    )
                    future.complete(
                        RestoreOperationResult(
                            BackupOperationStatus.FAILED,
                            emptyList(),
                            targets,
                            null,
                            "restore journal could not be created",
                        ),
                    )
                    return@Runnable
                }

            try {
                for (worldUuid in targets) {
                try {
                    val source = File(backupDir(entry.id), "worlds/$worldUuid")
                    val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
                    val targetWorldDir = worldData?.let { core.getWorldDirectory(it) }
                        ?: error("world data missing: $worldUuid")
                    val backupWorldZip = File(source, "world.zip")
                    if (!backupWorldZip.isFile) error("backup world zip missing: $worldUuid")
                    val worldRepository = MyWorldManagerApi.getWorldRepository()
                        ?: error("world repository unavailable: $worldUuid")
                    val sourceMetadata = File(source, "mwm.yml")
                    val restoredWorldData = worldRepository.readCurrentDataFile(sourceMetadata, worldUuid)
                    check(restoredWorldData.worldKey == worldData.worldKey) {
                        "backup world metadata targets a different world key: $worldUuid"
                    }
                    check(restoredWorldData.dimension == worldData.dimension) {
                        "backup world metadata targets a different dimension: $worldUuid"
                    }
                    val sourceChanpon = File(source, "chanpon.yml")
                    if (sourceChanpon.exists()) {
                        storage.validateExternalWorldFile(sourceChanpon, worldUuid)
                    }
                    val sourcePortals = PortalSnapshotCodec.read(
                        File(source, "portals.yml"),
                        expectedWorldUuid = worldUuid,
                        expectedWorldKey = worldData.worldKey,
                    )
                    val tempWorldDir = File(
                        targetWorldDir.parentFile ?: error("world directory parent missing: $targetWorldDir"),
                        "${targetWorldDir.name}.restore-${UUID.randomUUID()}"
                    )
                    val previousWorldDir = tempWorldDir.resolveSibling("${tempWorldDir.name}.previous")
                    val targetMetadata = core.getWorldDataFile(worldUuid)
                    val previousMetadataFile = File(tempWorldDir.parentFile, "${tempWorldDir.name}.metadata.previous")
                    val targetChanpon = storage.worldFile(worldUuid)
                    val previousChanponFile = File(tempWorldDir.parentFile, "${tempWorldDir.name}.chanpon.previous")
                    val previousPortalsFile = File(tempWorldDir.parentFile, "${tempWorldDir.name}.portals.previous.yml")
                    val journalDirectory = RestoreTransactionJournal.allocateDirectory(rootDir)
                    val journal = RestoreTransactionJournal.create(
                        journalDirectory,
                        entry.id,
                        listOf(
                            RestoreJournalWorld(
                                worldUuid = worldUuid,
                                targetWorldPath = targetWorldDir.absolutePath,
                                temporaryWorldPath = tempWorldDir.absolutePath,
                                previousWorldPath = previousWorldDir.absolutePath,
                                previousWorldExisted = targetWorldDir.exists(),
                                targetMetadataPath = targetMetadata.absolutePath,
                                previousMetadataPath = previousMetadataFile.absolutePath,
                                previousMetadataExisted = targetMetadata.isFile,
                                targetChanponPath = targetChanpon.absolutePath,
                                previousChanponPath = previousChanponFile.absolutePath,
                                previousChanponExisted = targetChanpon.isFile,
                                previousPortalsPath = previousPortalsFile.absolutePath,
                                previousWorldMoveStarted = false,
                                previousWorldMoved = false,
                                targetWorldReplaceStarted = false,
                                targetWorldReplaced = false,
                                phase = RestoreJournalPhase.PREPARED,
                            ),
                        ),
                    )
                    journalsByWorld[worldUuid] = journal
                    operationMarker.addJournal(journal.directory)
                    try {
                        if (targetMetadata.isFile) Files.copy(targetMetadata.toPath(), previousMetadataFile.toPath())
                        if (targetChanpon.isFile) Files.copy(targetChanpon.toPath(), previousChanponFile.toPath())
                        PortalSnapshotCodec.write(previousPortalsFile, core.getRelatedPortals(worldUuid))
                        // ロールバック用スナップショットがすべて永続化されるまで、
                        // ワールドとメタデータの置換を開始しません。
                        journal.markSnapshotsReady()
                        extractWorldZip(backupWorldZip.toPath(), tempWorldDir.toPath())
                        if (targetWorldDir.exists()) {
                            journal.markPreviousWorldMoveStarted(worldUuid)
                            Files.move(targetWorldDir.toPath(), previousWorldDir.toPath())
                            journal.markPreviousWorldMoved(worldUuid)
                        }
                        journal.markTargetWorldReplaceStarted(worldUuid)
                        Files.move(tempWorldDir.toPath(), targetWorldDir.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        journal.markTargetWorldReplaced(worldUuid)
                        restoreCoreMetadataFile(sourceMetadata, targetMetadata)
                        if (sourceChanpon.isFile) {
                            restoreChanponFileForRestore(sourceChanpon, worldUuid)
                        } else {
                            // バックアップにない状態も復元結果の一部です。旧ファイルを残すと、復元後に別世代の設定が混在します。
                            storage.removeWorldFileForRestore(worldUuid)
                        }
                        portalsByWorld[worldUuid] = sourcePortals
                        journal.markDataCommitted(worldUuid)
                    } catch (error: Exception) {
                        val record = journal.record(worldUuid) ?: error("restore journal record missing: $worldUuid")
                        runCatching {
                            if (journal.areSnapshotsReady()) {
                                rollbackInterruptedWorld(record)
                            } else {
                                require(!record.previousWorldMoveStarted && !record.targetWorldReplaceStarted) {
                                    "Restore reached world replacement before snapshots were ready: $worldUuid"
                                }
                                cleanupPreparedRestoreArtifacts(record)
                            }
                            journal.markRolledBack(worldUuid)
                            cleanupInterruptedWorld(journal.record(worldUuid) ?: record)
                            operationMarker.removeJournal(journal.directory)
                            journal.delete()
                            journalsByWorld.remove(worldUuid)
                        }.onFailure { rollbackError ->
                            plugin.logger.log(
                                java.util.logging.Level.SEVERE,
                                "Could not roll back restored world data; journal was retained: $worldUuid",
                                rollbackError,
                            )
                        }
                        throw error
                    } finally {
                        if (tempWorldDir.exists()) tempWorldDir.deleteRecursively()
                    }
                    restored.add(worldUuid)
                } catch (ex: Exception) {
                    plugin.logger.warning("Restore failed for $worldUuid: ${ex.message}")
                    failed.add(worldUuid)
                }
            }

                Bukkit.getScheduler().runTask(plugin, Runnable {
                val syncFailed = failed.toMutableList()
                val syncRestored = restored.toMutableList()
                val syncCore = MyWorldManagerApi.getWorldService()
                if (syncCore == null) {
                    restoreRecoveryFailure = "core api became unavailable during restore rollback"
                    plugin.logger.severe(
                        "Restore rollback was deferred because the MyWorld API became unavailable; " +
                            "the restore journal was retained for startup recovery",
                    )
                    future.complete(RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, "core api unavailable"))
                    return@Runnable
                }
                try {
                    syncCore.reloadWorldData()
                    restored.forEach { worldUuid ->
                        runCatching {
                            syncCore.replaceRelatedPortals(worldUuid, portalsByWorld[worldUuid].orEmpty())
                            journalsByWorld[worldUuid]?.let { journal ->
                                journal.markPortalsCommitted(worldUuid)
                            }
                        }.onFailure { ex ->
                            plugin.logger.warning("Restore portal sync failed for $worldUuid: ${ex.message}")
                            journalsByWorld[worldUuid]?.let { journal ->
                                runCatching { rollbackRestoreJournal(journal, syncCore) }
                                    .onSuccess {
                                        operationMarker.removeJournal(journal.directory)
                                        journalsByWorld.remove(worldUuid)
                                    }
                                    .onFailure { rollbackError ->
                                        restoreRecoveryFailure =
                                            "portal synchronization rollback failed for $worldUuid: " +
                                                (rollbackError.message ?: rollbackError.javaClass.simpleName)
                                        plugin.logger.log(
                                            java.util.logging.Level.SEVERE,
                                            "Could not roll back failed portal synchronization: $worldUuid",
                                            rollbackError,
                                        )
                                    }
                            }
                            syncRestored.remove(worldUuid)
                            syncFailed.add(worldUuid)
                        }
                    }
                    syncCore.reloadPortalData()
                    val distinctFailed = syncFailed.distinct()
                    if (distinctFailed.isEmpty()) {
                        // 操作マーカーを全ワールドの永続コミット点とします。
                        // この点が保存されるまでバックアップハッシュを公開しません。
                        operationCommitAttempted = true
                        operationMarker.markCommitted()
                        operationCommitPersisted = true
                        restored.distinct().forEach { worldUuid ->
                            runCatching { storage.setLastBackupHash(worldUuid, backupHash(entry, worldUuid)) }
                                .onFailure { error ->
                                    plugin.logger.log(
                                        java.util.logging.Level.WARNING,
                                        "Could not update restore hash for $worldUuid after committed restore",
                                        error,
                                    )
                                }
                        }
                        journalsByWorld.values.distinct().forEach { journal ->
                            // Keep a per-world commit marker as a fallback for
                            // the orphan-journal scan if the process stops after
                            // the operation marker is updated but before this
                            // journal is removed.
                            journal.markOperationCommitted()
                            journal.worlds().forEach(::cleanupInterruptedWorld)
                            journal.delete()
                            operationMarker.removeJournal(journal.directory)
                        }
                        journalsByWorld.clear()
                        operationMarker.delete()
                    } else {
                        var rollbackSucceeded = true
                        journalsByWorld.values.distinct().forEach { journal ->
                            runCatching { rollbackRestoreJournal(journal, syncCore) }
                                .onSuccess {
                                    operationMarker.removeJournal(journal.directory)
                                    journalsByWorld.entries.removeIf { it.value === journal }
                                }
                                .onFailure { rollbackError ->
                                    rollbackSucceeded = false
                                    restoreRecoveryFailure =
                                        "partial restore rollback failed: " +
                                            (rollbackError.message ?: rollbackError.javaClass.simpleName)
                                    plugin.logger.log(
                                        java.util.logging.Level.SEVERE,
                                        "Could not roll back partial multi-world restore",
                                        rollbackError,
                                    )
                                }
                        }
                        syncRestored.clear()
                        if (rollbackSucceeded) {
                            operationMarker.delete()
                        }
                    }
                    val status = if (distinctFailed.isEmpty()) BackupOperationStatus.SUCCESS else BackupOperationStatus.FAILED
                    future.complete(
                        RestoreOperationResult(
                            status,
                            syncRestored.distinct(),
                            distinctFailed,
                            null,
                            if (distinctFailed.isEmpty()) "restore completed" else "restore failed"
                        )
                    )
                } catch (ex: Exception) {
                    plugin.logger.warning("Restore repository reload failed: ${ex.message}")
                    if (operationCommitAttempted && !operationCommitPersisted) {
                        restoreRecoveryFailure = "restore commit marker persistence is uncertain"
                        plugin.logger.log(
                            java.util.logging.Level.SEVERE,
                            "Restore commit marker persistence was uncertain; retaining journals for startup recovery",
                            ex,
                        )
                        future.complete(
                            RestoreOperationResult(
                                BackupOperationStatus.FAILED,
                                emptyList(),
                                targets,
                                null,
                                "restore commit state is uncertain; restart is required for recovery",
                            ),
                        )
                        return@Runnable
                    }
                    if (operationMarker.isCommitted()) {
                        // The data commit is already durable. Cleanup failure
                        // must not turn a committed restore back into a
                        // rollback; startup recovery will retry this cleanup
                        // from the committed operation marker.
                        runCatching {
                            operationMarker.journals().forEach { journalDirectory ->
                                if (journalDirectory.exists()) {
                                    val journal = RestoreTransactionJournal.load(journalDirectory)
                                        ?: error("restore journal is unreadable: $journalDirectory")
                                    journal.worlds().forEach(::cleanupInterruptedWorld)
                                    journal.delete()
                                }
                                operationMarker.removeJournal(journalDirectory)
                            }
                            operationMarker.delete()
                        }.onFailure { cleanupError ->
                            plugin.logger.log(
                                java.util.logging.Level.SEVERE,
                                "Committed restore cleanup was deferred to startup recovery",
                                cleanupError,
                            )
                        }
                        future.complete(
                            RestoreOperationResult(
                                BackupOperationStatus.SUCCESS,
                                syncRestored.distinct(),
                                emptyList(),
                                null,
                                "restore completed; cleanup pending",
                            ),
                        )
                        return@Runnable
                    }
                    journalsByWorld.values.toList().forEach { journal ->
                        runCatching { rollbackRestoreJournal(journal, syncCore) }
                            .onFailure { rollbackError ->
                                restoreRecoveryFailure =
                                    "restore rollback after repository reload failed: " +
                                        (rollbackError.message ?: rollbackError.javaClass.simpleName)
                                plugin.logger.log(
                                    java.util.logging.Level.SEVERE,
                                    "Could not roll back failed restore repository reload",
                                    rollbackError,
                                )
                            }
                        if (!journal.directory.exists()) {
                            journalsByWorld.entries.removeIf { it.value === journal }
                        }
                    }
                    runCatching { rollbackOperationMarker(operationMarker) }
                    future.complete(
                        RestoreOperationResult(
                            BackupOperationStatus.FAILED,
                            syncRestored.distinct(),
                            targets,
                            null,
                            ex.message ?: "restore failed"
                        )
                    )
                }
                })
            } catch (fatal: Exception) {
                val committed = operationMarker.isCommitted()
                if (!committed) {
                    restoreRecoveryFailure = "restore transaction failed before completion: ${fatal.message ?: fatal.javaClass.simpleName}"
                }
                plugin.logger.log(
                    java.util.logging.Level.SEVERE,
                    "Unexpected restore transaction failure; ${if (committed) "cleanup will be retried" else "journal was retained for startup recovery"}",
                    fatal,
                )
                future.complete(
                    RestoreOperationResult(
                        if (committed) BackupOperationStatus.SUCCESS else BackupOperationStatus.FAILED,
                        if (committed) restored.distinct() else emptyList(),
                        if (committed) emptyList() else targets,
                        null,
                        if (committed) "restore completed; cleanup pending" else "restore failed; restart is required for recovery",
                    ),
                )
            }
        })
        return future
    }

    private fun unloadSequential(
        worldUuids: List<UUID>,
        save: Boolean,
        leases: Map<UUID, WorldOperationLease>
    ): CompletableFuture<Boolean> {
        val core = MyWorldManagerApi.getWorldService() ?: return CompletableFuture.completedFuture(false)
        var chain = CompletableFuture.completedFuture(true)
        worldUuids.forEach { uuid ->
            chain = chain.thenCompose { ok ->
                if (!ok) {
                    CompletableFuture.completedFuture(false)
                } else {
                    core.unloadWorldForMaintenance(uuid, save, leases[uuid])
                }
            }
        }
        return chain
    }

    private fun evacuatePlayers(worldUuids: List<UUID>): List<EvacuatedPlayer>? {
        val names = worldUuids.map { "my_world.$it" }.toSet()
        val targets = Bukkit.getOnlinePlayers()
            .mapNotNull { player ->
                val worldUuid = MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid
                    ?: if (player.world.name in names) {
                        runCatching { UUID.fromString(player.world.name.removePrefix("my_world.")) }.getOrNull()
                    } else {
                        null
                    }
                if (worldUuid == null || worldUuid !in worldUuids) return@mapNotNull null
                player to EvacuatedPlayer(player.uniqueId, worldUuid, player.location.clone())
            }
        if (targets.isEmpty()) return emptyList()
        val location = MyWorldManagerApi.getEvacuationLocationOverride() ?: return null
        targets.forEach { (player, _) -> player.teleport(location) }
        return targets.map { it.second }
    }

    private fun restoreEvacuatedPlayers(evacuatedPlayers: List<EvacuatedPlayer>, result: RestoreOperationResult) {
        if (result.status != BackupOperationStatus.SUCCESS && result.status != BackupOperationStatus.RECOVERED) return
        val safeWorlds = result.restoredWorlds.toSet()
        val core = MyWorldManagerApi.getWorldService() ?: return
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        evacuatedPlayers
            .filter { it.worldUuid in safeWorlds }
            .forEach { evacuated ->
                val player = Bukkit.getPlayer(evacuated.playerUuid) ?: return@forEach
                if (!player.isOnline) return@forEach
                val worldData = repository.findByUuid(evacuated.worldUuid) ?: return@forEach
                if (!core.loadWorld(evacuated.worldUuid)) return@forEach
                val world = Bukkit.getWorld(core.getWorldFolderName(worldData)) ?: return@forEach
                val destination = evacuated.location.clone()
                destination.world = world
                player.teleport(destination)
            }
    }

    private fun copyWorldSnapshot(worldUuid: UUID, backupDir: File) {
        val core = MyWorldManagerApi.getWorldService() ?: error("core api unavailable")
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid) ?: error("world data missing: $worldUuid")
        val worldDir = core.getWorldDirectory(worldData)
        if (!worldDir.isDirectory) error("world directory missing: ${worldDir.absolutePath}")
        val target = File(backupDir, "worlds/$worldUuid")
        target.mkdirs()
        zipWorldDirectory(worldDir.toPath(), File(target, "world.zip").toPath())
        copyFileIfExists(core.getWorldDataFile(worldUuid), File(target, "mwm.yml"))
        copyFileIfExists(storage.worldFile(worldUuid), File(target, "chanpon.yml"))
        writePortalSnapshots(File(target, "portals.yml"), core.getRelatedPortals(worldUuid))
    }

    private fun writeManifest(
        dir: File,
        id: String,
        scope: BackupScope,
        kind: BackupKind,
        worldUuids: List<UUID>,
        actorUuid: UUID?,
        actorName: String?,
        reason: String?,
        name: String?
    ): BackupEntry {
        val created = LocalDateTime.now()
        val size = folderSize(dir)
        val config = YamlConfiguration()
        config.set("id", id)
        config.set("scope", scope.storageValue)
        config.set("kind", kind.storageValue)
        config.set("created_at", created.format(createdFormatter))
        config.set("worlds", worldUuids.map(UUID::toString))
        config.set("actor_uuid", actorUuid?.toString())
        config.set("actor_name", actorName)
        config.set("reason", reason)
        config.set("name", name?.takeIf { it.isNotBlank() })
        config.set("size_bytes", size)
        config.save(File(dir, "manifest.yml"))
        return BackupEntry(id, scope, kind, created, worldUuids, actorUuid, actorName, reason, name?.takeIf { it.isNotBlank() }, size)
    }

    private fun readManifest(dir: File): BackupEntry? {
        val file = File(dir, "manifest.yml")
        if (!file.exists()) return null
        val config = YamlConfiguration.loadConfiguration(file)
        val id = config.getString("id") ?: dir.name
        val scope = BackupScope.fromStorageValue(config.getString("scope")) ?: return null
        val kind = BackupKind.fromStorageValue(config.getString("kind")) ?: return null
        val created = runCatching {
            LocalDateTime.parse(config.getString("created_at"), createdFormatter)
        }.getOrElse { LocalDateTime.MIN }
        val worlds = config.getStringList("worlds").mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (worlds.any { !File(dir, "worlds/$it/world.zip").isFile }) return null
        val actorUuid = config.getString("actor_uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return BackupEntry(
            id = id,
            scope = scope,
            kind = kind,
            createdAt = created,
            worldUuids = worlds,
            actorUuid = actorUuid,
            actorName = config.getString("actor_name"),
            reason = config.getString("reason"),
            name = config.getString("name"),
            sizeBytes = config.getLong("size_bytes", folderSize(dir))
        )
    }

    private fun writeWorldHashes(entry: BackupEntry) {
        entry.worldUuids.forEach { worldUuid ->
            storage.setLastBackupHash(worldUuid, backupHash(entry, worldUuid))
        }
    }

    private fun hasChangedSinceLastBackup(worldData: WorldData): Boolean {
        val previous = storage.getLastBackupHash(worldData.uuid) ?: return true
        return currentWorldHash(worldData) != previous
    }

    private fun currentWorldHash(worldData: WorldData): String {
        val core = MyWorldManagerApi.getWorldService() ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateFile(file: File, label: String) {
            if (!file.exists()) return
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(Files.readAllBytes(file.toPath()))
        }
        val worldDir = core.getWorldDirectory(worldData)
        digest.update(hashPath(worldDir.toPath()).toByteArray(Charsets.UTF_8))
        updateFile(core.getWorldDataFile(worldData.uuid), "mwm")
        updateChanponFile(digest, storage.worldFile(worldData.uuid))
        digest.update(
            PortalSnapshotCodec.writeToString(
                core.getRelatedPortals(worldData.uuid).sortedBy { it.id.toString() },
            ).toByteArray(Charsets.UTF_8),
        )
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun backupHash(entry: BackupEntry, worldUuid: UUID): String {
        val dir = File(backupDir(entry.id), "worlds/$worldUuid")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(hashZip(File(dir, "world.zip").toPath()).toByteArray(Charsets.UTF_8))
        fun updateFile(file: File, label: String) {
            if (!file.exists()) return
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(Files.readAllBytes(file.toPath()))
        }
        updateFile(File(dir, "mwm.yml"), "mwm")
        updateChanponFile(digest, File(dir, "chanpon.yml"))
        digest.update(
            PortalSnapshotCodec.writeToString(
                PortalSnapshotCodec.read(File(dir, "portals.yml"), requireFile = false)
                    .sortedBy { it.id.toString() },
            ).toByteArray(Charsets.UTF_8),
        )
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun productionWorlds(): List<WorldData> {
        return MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty().filter { storage.isProductionWorld(it.uuid) }
    }

    private fun ownerBackupCooldownRemainingMillis(worldUuid: UUID): Long {
        val cooldownMillis = plugin.config.getLong("backup.owner_manual.cooldown_hours", 3L).coerceAtLeast(0L) * 60L * 60L * 1000L
        if (cooldownMillis <= 0L) return 0L
        val lastCreatedAt = storage.getOwnerManualBackupLastCreatedAt(worldUuid)
        if (lastCreatedAt <= 0L) return 0L
        return (cooldownMillis - (System.currentTimeMillis() - lastCreatedAt)).coerceAtLeast(0L)
    }

    private fun markOwnerBackupCreated(worldUuid: UUID) {
        storage.setOwnerManualBackupLastCreatedAt(worldUuid, System.currentTimeMillis())
    }

    private fun manualRollbackCooldownRemainingMillis(worldUuid: UUID): Long {
        val cooldownMillis = plugin.config.getLong("backup.manual_rollback.cooldown_minutes", 10L).coerceAtLeast(0L) * 60L * 1000L
        if (cooldownMillis <= 0L) return 0L
        val lastExecutedAt = storage.getManualRollbackLastExecutedAt(worldUuid)
        if (lastExecutedAt <= 0L) return 0L
        return (cooldownMillis - (System.currentTimeMillis() - lastExecutedAt)).coerceAtLeast(0L)
    }

    private fun saveLoadedWorlds(worldUuids: List<UUID>) {
        val core = MyWorldManagerApi.getWorldService() ?: return
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        worldUuids.mapNotNull(repository::findByUuid).forEach { worldData ->
            val world = Bukkit.getWorld(core.getWorldFolderName(worldData)) ?: return@forEach
            world.save()
        }
    }

    private fun writePortalSnapshots(file: File, snapshots: List<ApiPortalSnapshot>) {
        PortalSnapshotCodec.write(file, snapshots)
    }

    private fun backupDir(id: String): File = File(rootDir, id)

    private fun copyFileIfExists(source: File, target: File) {
        if (!source.exists()) return
        target.parentFile?.mkdirs()
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun updateChanponFile(digest: MessageDigest, file: File) {
        if (!file.exists()) return
        val config = YamlConfiguration.loadConfiguration(file)
        config.set("backup.last_hash", null)
        config.set("backup.owner_manual.last_created_at", null)
        digest.update("chanpon".toByteArray(Charsets.UTF_8))
        digest.update(config.saveToString().toByteArray(Charsets.UTF_8))
    }

    private fun restoreChanponFileForRestore(source: File, worldUuid: UUID) {
        if (!source.exists()) return
        storage.restoreExternalWorldFile(source, worldUuid)
    }

    /**
     * 復元先の既存メタデータを直接上書きせず、同一ディレクトリ内の一時ファイルから
     * atomic move します。呼び出し前に ApiWorldRepository で内容を検証済みであることが前提です。
     */
    private fun restoreCoreMetadataFile(source: File, target: File) {
        require(source.isFile) { "backup world metadata is missing: ${source.path}" }
        target.parentFile?.mkdirs()
        val temporary = target.resolveSibling("${target.name}.restore-${UUID.randomUUID()}.tmp")
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    private fun restoreCoreMetadataSnapshot(target: File, bytes: ByteArray?) {
        if (bytes == null) {
            Files.deleteIfExists(target.toPath())
            return
        }
        target.parentFile?.mkdirs()
        val temporary = target.resolveSibling("${target.name}.rollback-${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary.toPath(), bytes)
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

    private fun zipWorldDirectory(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.deleteIfExists(temp)
        try {
            ZipOutputStream(Files.newOutputStream(temp)).use { zip ->
                Files.walk(source).use { stream ->
                    stream.sorted(Comparator.comparing { source.relativize(it).toString() }).forEach { path ->
                        if (path.fileName?.toString() == "session.lock") return@forEach
                        val relative = source.relativize(path)
                        if (relative.toString().isEmpty()) return@forEach
                        val entryName = relative.toString().replace(File.separatorChar, '/')
                        if (path.isDirectory()) {
                            zip.putNextEntry(ZipEntry("$entryName/"))
                            zip.closeEntry()
                        } else {
                            zip.putNextEntry(ZipEntry(entryName))
                            Files.copy(path, zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (ex: Exception) {
            Files.deleteIfExists(temp)
            throw ex
        }
    }

    private fun extractWorldZip(source: Path, target: Path) {
        if (Files.exists(target)) target.toFile().deleteRecursively()
        Files.createDirectories(target)
        val root = target.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = root.resolve(entry.name).normalize()
                if (!destination.startsWith(root)) {
                    error("unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
            }
        }
    }

    private fun folderSize(file: File): Long {
        if (!file.exists()) return 0L
        return Files.walk(file.toPath()).use { stream ->
            stream.filter { !it.isDirectory() }.mapToLong { Files.size(it) }.sum()
        }
    }

    private fun hashPath(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!Files.exists(path)) return ""
        Files.walk(path).use { stream ->
            stream.filter { !it.isDirectory() }
                .sorted(Comparator.comparing { path.relativize(it).toString() })
                .forEach {
                    if (it.fileName?.toString() == "session.lock") return@forEach
                    digest.update(path.relativize(it).toString().replace(File.separatorChar, '/').toByteArray(Charsets.UTF_8))
                    digest.update(Files.readAllBytes(it))
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashZip(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!Files.exists(path)) return ""
        ZipInputStream(Files.newInputStream(path)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    digest.update(entry.name.toByteArray(Charsets.UTF_8))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class EvacuatedPlayer(
        val playerUuid: UUID,
        val worldUuid: UUID,
        val location: Location
    )
}
