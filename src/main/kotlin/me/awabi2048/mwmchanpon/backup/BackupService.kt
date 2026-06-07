package me.awabi2048.mwmchanpon.backup

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot
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

class BackupService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) {
    private val rootDir = File(plugin.dataFolder, "backups")
    private val operationLocks = ConcurrentHashMap.newKeySet<UUID>()
    private val activeBackupIds = ConcurrentHashMap.newKeySet<String>()
    private val createdFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var postRestoreHandler: (List<UUID>) -> Unit = {}

    init {
        rootDir.mkdirs()
    }

    fun listBackups(): List<BackupEntry> {
        return rootDir.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { readManifest(it) }
            .sortedByDescending { it.createdAt }
    }

    fun listBackupsForWorld(worldUuid: UUID): List<BackupEntry> {
        return listBackups().filter { worldUuid in it.worldUuids }
    }

    fun latestRestorePoint(): BackupEntry? {
        return listBackups().firstOrNull { it.kind == BackupKind.RESTORE_POINT && it.scope == BackupScope.ALL }
    }

    fun setPostRestoreHandler(handler: (List<UUID>) -> Unit) {
        postRestoreHandler = handler
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

    fun restoreWorldBackup(entry: BackupEntry, worldUuid: UUID): CompletableFuture<RestoreOperationResult> {
        if (worldUuid !in entry.worldUuids) {
            return CompletableFuture.completedFuture(
                RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), listOf(worldUuid), null, "world is not included in backup")
            )
        }
        return restoreBackup(entry, listOf(worldUuid))
    }

    private fun restoreBackup(entry: BackupEntry, requestedWorlds: List<UUID>): CompletableFuture<RestoreOperationResult> {
        if (!activeBackupIds.add(entry.id)) {
            return CompletableFuture.completedFuture(
                RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), requestedWorlds, null, "backup is locked")
            )
        }
        val targets = requestedWorlds.distinct().filter { storage.isProductionWorld(it) }
        if (targets.isEmpty()) {
            activeBackupIds.remove(entry.id)
            return CompletableFuture.completedFuture(
                RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), requestedWorlds, null, "no production worlds")
            )
        }
        val locked = targets.filter { operationLocks.add(it) }
        if (locked.size != targets.size) {
            locked.forEach(operationLocks::remove)
            activeBackupIds.remove(entry.id)
            return CompletableFuture.completedFuture(
                RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, "world is locked")
            )
        }

        val future = CompletableFuture<RestoreOperationResult>()
        val core = MyWorldManagerApi.getWorldService()
        if (core == null) {
            targets.forEach(operationLocks::remove)
            activeBackupIds.remove(entry.id)
            future.complete(RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, "core api unavailable"))
            return future
        }

        val evacuatedPlayers = evacuatePlayers(targets)
        unloadSequential(targets, save = true).thenCompose { unloaded ->
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
            targets.forEach(operationLocks::remove)
            activeBackupIds.remove(entry.id)
            if (throwable != null) {
                future.complete(
                    RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, throwable.message ?: "restore failed")
                )
            } else {
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
        val locked = if (acquireLocks) targets.filter { operationLocks.add(it) } else emptyList()
        if (acquireLocks && locked.size != targets.size) {
            locked.forEach(operationLocks::remove)
            return CompletableFuture.completedFuture(
                BackupOperationResult(BackupOperationStatus.FAILED, null, "world is locked")
            )
        }
        runCatching { saveLoadedWorlds(targets) }.getOrElse { ex ->
            locked.forEach(operationLocks::remove)
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
                locked.forEach(operationLocks::remove)
            }
        })
        return future
    }

    private fun restoreFilesAsync(entry: BackupEntry, targets: List<UUID>): CompletableFuture<RestoreOperationResult> {
        val future = CompletableFuture<RestoreOperationResult>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val restored = mutableListOf<UUID>()
            val failed = mutableListOf<UUID>()
            val portalsByWorld = mutableMapOf<UUID, List<ApiPortalSnapshot>>()
            val core = MyWorldManagerApi.getWorldService()
            if (core == null) {
                future.complete(RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, "core api unavailable"))
                return@Runnable
            }

            for (worldUuid in targets) {
                try {
                    val source = File(backupDir(entry.id), "worlds/$worldUuid")
                    val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(worldUuid)
                    val targetWorldDir = worldData?.let { core.getWorldDirectory(it) } ?: File(Bukkit.getWorldContainer(), "my_world.$worldUuid")
                    val backupWorldZip = File(source, "world.zip")
                    if (!backupWorldZip.isFile) error("backup world zip missing: $worldUuid")

                    val tempWorldDir = File(targetWorldDir.parentFile ?: Bukkit.getWorldContainer(), "${targetWorldDir.name}.restore-${UUID.randomUUID()}")
                    try {
                        extractWorldZip(backupWorldZip.toPath(), tempWorldDir.toPath())
                        if (targetWorldDir.exists()) targetWorldDir.deleteRecursively()
                        Files.move(tempWorldDir.toPath(), targetWorldDir.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        if (tempWorldDir.exists()) tempWorldDir.deleteRecursively()
                    }

                    copyFileIfExists(File(source, "mwm.yml"), core.getWorldDataFile(worldUuid))
                    copyChanponFileForRestore(File(source, "chanpon.yml"), storage.worldFile(worldUuid))
                    portalsByWorld[worldUuid] = readPortalSnapshots(File(source, "portals.yml"))
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
                    future.complete(RestoreOperationResult(BackupOperationStatus.FAILED, emptyList(), targets, null, "core api unavailable"))
                    return@Runnable
                }
                try {
                    syncCore.reloadWorldData()
                    restored.forEach { worldUuid ->
                        runCatching {
                            syncCore.replaceRelatedPortals(worldUuid, portalsByWorld[worldUuid].orEmpty())
                            storage.setLastBackupHash(worldUuid, backupHash(entry, worldUuid))
                        }.onFailure { ex ->
                            plugin.logger.warning("Restore portal sync failed for $worldUuid: ${ex.message}")
                            syncRestored.remove(worldUuid)
                            syncFailed.add(worldUuid)
                        }
                    }
                    syncCore.reloadPortalData()
                    val distinctFailed = syncFailed.distinct()
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
        })
        return future
    }

    private fun unloadSequential(worldUuids: List<UUID>, save: Boolean): CompletableFuture<Boolean> {
        val core = MyWorldManagerApi.getWorldService() ?: return CompletableFuture.completedFuture(false)
        var chain = CompletableFuture.completedFuture(true)
        worldUuids.forEach { uuid ->
            chain = chain.thenCompose { ok ->
                if (!ok) CompletableFuture.completedFuture(false) else core.unloadWorldForMaintenance(uuid, save)
            }
        }
        return chain
    }

    private fun evacuatePlayers(worldUuids: List<UUID>): List<EvacuatedPlayer> {
        val fallback = Bukkit.getWorld("level") ?: Bukkit.getWorlds().firstOrNull()
        val location = fallback?.spawnLocation
        val names = worldUuids.map { "my_world.$it" }.toSet()
        return Bukkit.getOnlinePlayers()
            .mapNotNull { player ->
                val worldUuid = MyWorldManagerApi.getWorldRepository()?.findByWorldName(player.world.name)?.uuid
                    ?: if (player.world.name in names) {
                        runCatching { UUID.fromString(player.world.name.removePrefix("my_world.")) }.getOrNull()
                    } else {
                        null
                    }
                if (worldUuid == null || worldUuid !in worldUuids) return@mapNotNull null
                EvacuatedPlayer(player.uniqueId, worldUuid, player.location.clone()).also {
                    if (location != null) player.teleport(location)
                }
            }
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
        core.getRelatedPortals(worldData.uuid)
            .sortedBy { it.id.toString() }
            .forEach { digest.update(portalToMap(it).toString().toByteArray(Charsets.UTF_8)) }
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
        readPortalSnapshots(File(dir, "portals.yml"))
            .sortedBy { it.id.toString() }
            .forEach { digest.update(portalToMap(it).toString().toByteArray(Charsets.UTF_8)) }
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

    private fun saveLoadedWorlds(worldUuids: List<UUID>) {
        val core = MyWorldManagerApi.getWorldService() ?: return
        val repository = MyWorldManagerApi.getWorldRepository() ?: return
        worldUuids.mapNotNull(repository::findByUuid).forEach { worldData ->
            val world = Bukkit.getWorld(core.getWorldFolderName(worldData)) ?: return@forEach
            world.save()
        }
    }

    private fun writePortalSnapshots(file: File, snapshots: List<ApiPortalSnapshot>) {
        val config = YamlConfiguration()
        config.set("portals", snapshots.map(::portalToMap))
        config.save(file)
    }

    private fun readPortalSnapshots(file: File): List<ApiPortalSnapshot> {
        if (!file.exists()) return emptyList()
        return YamlConfiguration.loadConfiguration(file).getMapList("portals").mapNotNull(::mapToPortal)
    }

    private fun portalToMap(portal: ApiPortalSnapshot): Map<String, Any?> = linkedMapOf(
        "id" to portal.id.toString(),
        "world_name" to portal.worldName,
        "x" to portal.x,
        "y" to portal.y,
        "z" to portal.z,
        "world_uuid" to portal.worldUuid?.toString(),
        "target_world_name" to portal.targetWorldName,
        "show_text" to portal.showText,
        "particle_color_rgb" to portal.particleColorRgb,
        "owner_uuid" to portal.ownerUuid.toString(),
        "created_at" to portal.createdAt,
        "text_display_uuid" to portal.textDisplayUuid?.toString(),
        "type" to portal.type,
        "min_x" to portal.minX,
        "min_y" to portal.minY,
        "min_z" to portal.minZ,
        "max_x" to portal.maxX,
        "max_y" to portal.maxY,
        "max_z" to portal.maxZ
    )

    private fun mapToPortal(map: Map<*, *>): ApiPortalSnapshot? {
        return runCatching {
            ApiPortalSnapshot(
                UUID.fromString(map["id"] as String),
                map["world_name"] as String,
                (map["x"] as Number).toInt(),
                (map["y"] as Number).toInt(),
                (map["z"] as Number).toInt(),
                (map["world_uuid"] as? String)?.let(UUID::fromString),
                map["target_world_name"] as? String,
                map["show_text"] as? Boolean ?: true,
                (map["particle_color_rgb"] as Number).toInt(),
                UUID.fromString(map["owner_uuid"] as String),
                map["created_at"] as String,
                (map["text_display_uuid"] as? String)?.let(UUID::fromString),
                map["type"] as? String ?: "portal",
                (map["min_x"] as? Number)?.toInt(),
                (map["min_y"] as? Number)?.toInt(),
                (map["min_z"] as? Number)?.toInt(),
                (map["max_x"] as? Number)?.toInt(),
                (map["max_y"] as? Number)?.toInt(),
                (map["max_z"] as? Number)?.toInt()
            )
        }.getOrNull()
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

    private fun copyChanponFileForRestore(source: File, target: File) {
        if (!source.exists()) return
        val lastOwnerBackupAt = if (target.exists()) {
            YamlConfiguration.loadConfiguration(target).getLong("backup.owner_manual.last_created_at", 0L)
        } else {
            0L
        }
        copyFileIfExists(source, target)
        val restored = YamlConfiguration.loadConfiguration(target)
        restored.set("backup.last_hash", null)
        restored.set(
            "backup.owner_manual.last_created_at",
            if (lastOwnerBackupAt > 0L) lastOwnerBackupAt else null
        )
        restored.save(target)
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
