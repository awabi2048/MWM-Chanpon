package me.awabi2048.mwmchanpon.service

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.service.ApiWorldDataState
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player

class ArchiveService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage
) {
    private val incomingDir = File(plugin.dataFolder, "archives/incoming")
    private val storageDir = File(plugin.dataFolder, "archives/storage")
    private val fullBackupDir = File(plugin.dataFolder, "archives/full-backups")

    init {
        incomingDir.mkdirs()
        storageDir.mkdirs()
        fullBackupDir.mkdirs()
        if (globalStorage.isArchiveLocked()) {
            plugin.logger.warning("Clearing stale archive operation lock from the previous server process.")
            globalStorage.setArchiveLocked(false)
        }
    }

    fun createAnnualArchive(archiveName: String, executor: Player): CompletableFuture<ArchiveResult> {
        val resultFuture = CompletableFuture<ArchiveResult>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!beginOperation(resultFuture)) return@Runnable
            val bossBar = createBossBar(executor)
            createAnnualArchiveLocked(archiveName.trim(), executor, bossBar).whenComplete { result, error ->
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    bossBar.isVisible = false
                    globalStorage.setArchiveLocked(false)
                    if (error != null) {
                        plugin.logger.warning("Annual archive failed: ${error.message}")
                        resultFuture.complete(ArchiveResult(false, error.message ?: "archive_failed"))
                    } else {
                        resultFuture.complete(result)
                    }
                })
            }
        })
        return resultFuture
    }

    private fun createAnnualArchiveLocked(
        archiveName: String,
        executor: Player,
        bossBar: BossBar
    ): CompletableFuture<ArchiveResult> {
        val nameError = validateArchiveName(archiveName)
        if (nameError != null) return CompletableFuture.completedFuture(ArchiveResult(false, nameError))

        val repository = MyWorldManagerApi.getWorldRepository()
            ?: return CompletableFuture.completedFuture(ArchiveResult(false, "core_repository_unavailable"))
        val worldService = MyWorldManagerApi.getWorldService()
            ?: return CompletableFuture.completedFuture(ArchiveResult(false, "core_service_unavailable"))
        val output = File(storageDir, "$archiveName.zip")
        val tempOutput = File(storageDir, "$archiveName.zip.tmp")
        val safetyBackup = File(fullBackupDir, "$archiveName-full.zip")
        val tempSafetyBackup = File(fullBackupDir, "$archiveName-full.zip.tmp")
        if (output.exists() || tempOutput.exists() || safetyBackup.exists() || tempSafetyBackup.exists()) {
            return CompletableFuture.completedFuture(ArchiveResult(false, "archive_name_conflict"))
        }

        val allWorlds = repository.findAll()
        val trackedWorlds = allWorlds.filter { storage.worldFile(it.uuid).exists() }
        val spawnWorld = globalStorage.getSpawnWorldUuid()?.let(repository::findByUuid)
        val completedWorlds = trackedWorlds.filter {
            runCatching { storage.getSubmissionState(it.uuid) }.getOrNull() == SubmissionState.COMPLETED
        }
        val worldsToArchive = (completedWorlds + listOfNotNull(spawnWorld)).distinctBy { it.uuid }
        val affectedWorlds = (trackedWorlds + worldsToArchive).distinctBy { it.uuid }
        val worldsToDelete = trackedWorlds.filter { world -> worldsToArchive.none { it.uuid == world.uuid } }

        if (affectedWorlds.isEmpty()) {
            return CompletableFuture.completedFuture(ArchiveResult(false, "no_tracked_worlds"))
        }

        val evacuateLocation = MyWorldManagerApi.getEvacuationLocationOverride()
            ?: return CompletableFuture.completedFuture(ArchiveResult(false, "spawn_world_not_available"))
        affectedWorlds.forEach { worldData ->
            Bukkit.getWorld(worldService.getWorldFolderName(worldData))?.let { world ->
                world.players.forEach { it.teleport(evacuateLocation) }
                world.save()
            }
        }
        evacuateLocation.world.save()

        bossBar.isVisible = true
        syncBossBar(bossBar, executor, 0, affectedWorlds.size + worldsToArchive.size + worldsToDelete.size + 2)

        return createZipAsync(
            output,
            tempOutput,
            archiveName,
            worldsToArchive,
            worldService,
            bossBar,
            executor,
            affectedWorlds.size + 1
        ).thenCompose {
            createZipAsync(
                safetyBackup,
                tempSafetyBackup,
                "$archiveName-full",
                affectedWorlds,
                worldService,
                bossBar,
                executor,
                affectedWorlds.size + worldsToArchive.size + 2
            )
        }.thenCompose {
            deleteWorldsSequential(worldsToDelete, worldService, bossBar, executor, affectedWorlds.size + worldsToArchive.size + 2)
        }.thenApply { deleted ->
            if (!deleted) {
                ArchiveResult(false, "world_delete_failed")
            } else {
                ArchiveResult(true, "archive_created")
            }
        }
    }

    fun unzipArchive(archiveName: String, executor: Player): CompletableFuture<ArchiveResult> {
        val resultFuture = CompletableFuture<ArchiveResult>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!beginOperation(resultFuture)) return@Runnable
            val bossBar = createBossBar(executor)
            unzipArchiveLocked(archiveName.trim(), executor, bossBar).whenComplete { result, error ->
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    bossBar.isVisible = false
                    globalStorage.setArchiveLocked(false)
                    if (error != null) {
                        plugin.logger.warning("Unzip archive failed: ${error.message}")
                        resultFuture.complete(ArchiveResult(false, error.message ?: "unzip_failed"))
                    } else {
                        resultFuture.complete(result)
                    }
                })
            }
        })
        return resultFuture
    }

    private fun unzipArchiveLocked(
        archiveName: String,
        executor: Player,
        bossBar: BossBar
    ): CompletableFuture<ArchiveResult> {
        val nameError = validateArchiveName(archiveName)
        if (nameError != null) return CompletableFuture.completedFuture(ArchiveResult(false, nameError))

        val archiveLookup = archiveFileForName(archiveName)
        if (archiveLookup.error != null) {
            return CompletableFuture.completedFuture(ArchiveResult(false, archiveLookup.error))
        }
        val zipFile = archiveLookup.file
            ?: return CompletableFuture.completedFuture(ArchiveResult(false, "archive_not_found"))
        val repository = MyWorldManagerApi.getWorldRepository()
            ?: return CompletableFuture.completedFuture(ArchiveResult(false, "core_repository_unavailable"))
        val worldService = MyWorldManagerApi.getWorldService()
            ?: return CompletableFuture.completedFuture(ArchiveResult(false, "core_service_unavailable"))

        bossBar.isVisible = true
        val future = CompletableFuture<ArchiveResult>()
        val copiedWorldDirectories = mutableListOf<File>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val tempDir = Files.createTempDirectory("mwmchan_${archiveName}_")
            try {
                ZipArchiveExtractor.extract(zipFile, tempDir)
                val manifestWorlds = ArchiveManifest.read(
                    tempDir.resolve("manifest.yml").toFile(),
                    ARCHIVE_SCHEMA_VERSION
                )
                val imports = ArchiveImportValidator.resolve(manifestWorlds) { uuid ->
                    readWorldData(tempDir, uuid)
                }
                ArchiveImportValidator.validateConflicts(
                    imports,
                    uuidExists = { repository.stateOf(it) != ApiWorldDataState.NOT_FOUND },
                    folder = { worldData ->
                        val folderName = worldService.getWorldFolderName(worldData)
                        ImportFolder(
                            folderName,
                            worldService.getWorldCreationDirectory(worldData).exists()
                        )
                    }
                )

                imports.forEachIndexed { index, imported ->
                    val prefix = tempDir.resolve("worlds/${imported.uuid}")
                    val sourceDir = prefix.resolve("world").toFile()
                    val targetDir = worldService.getWorldCreationDirectory(imported.worldData)
                    if (sourceDir.isDirectory) {
                        sourceDir.copyRecursively(targetDir, overwrite = false)
                        copiedWorldDirectories += targetDir
                    }
                    syncBossBar(bossBar, executor, index + 1, imports.size + 1)
                }

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    try {
                        imports.forEach { imported ->
                            repository.save(imported.worldData)
                            restoreChanponData(tempDir, imported.uuid)
                            restorePortals(tempDir, imported.uuid)
                            storage.setImportedArchive(imported.uuid, true)
                        }
                        imports.forEach { imported ->
                            runCatching { worldService.loadWorld(imported.uuid) }
                                .onSuccess { loaded ->
                                    if (!loaded) {
                                        plugin.logger.warning(
                                            "Imported archive world could not be loaded and remains available for later loading: " +
                                                "${imported.worldData.name} (${imported.uuid})"
                                        )
                                    }
                                }
                                .onFailure {
                                    plugin.logger.warning(
                                        "Imported archive world could not be loaded and remains available for later loading: " +
                                            "${imported.worldData.name} (${imported.uuid}): ${it.message}"
                                    )
                                }
                            plugin.logger.info("Extracted archive world: ${imported.worldData.name} (${imported.uuid})")
                        }
                        syncBossBar(bossBar, executor, imports.size + 1, imports.size + 1)
                        future.complete(ArchiveResult(true, "unzip_completed"))
                    } catch (ex: Exception) {
                        rollbackImportedWorlds(imports, copiedWorldDirectories, repository)
                        future.completeExceptionally(ex)
                    } finally {
                        tempDir.toFile().deleteRecursively()
                    }
                })
            } catch (ex: Exception) {
                copiedWorldDirectories.asReversed().forEach { directory ->
                    if (directory.exists() && !directory.deleteRecursively()) {
                        plugin.logger.severe("Failed to remove partially imported world directory: $directory")
                    }
                }
                tempDir.toFile().deleteRecursively()
                future.completeExceptionally(ex)
            }
        })
        return future
    }

    private fun rollbackImportedWorlds(
        imports: List<ImportedWorld>,
        copiedWorldDirectories: List<File>,
        repository: me.awabi2048.myworldmanager.api.service.ApiWorldRepository
    ) {
        imports.asReversed().forEach { imported ->
            runCatching {
                MyWorldManagerApi.getWorldService()?.replaceRelatedPortals(imported.uuid, emptyList())
            }.onFailure {
                plugin.logger.severe("Failed to roll back imported portals for ${imported.uuid}: ${it.message}")
            }
            runCatching { repository.delete(imported.uuid) }
                .onFailure { plugin.logger.severe("Failed to roll back imported metadata for ${imported.uuid}: ${it.message}") }
            runCatching { storage.worldFile(imported.uuid).delete() }
        }
        copiedWorldDirectories.asReversed().forEach { directory ->
            if (directory.exists() && !directory.deleteRecursively()) {
                plugin.logger.severe("Failed to roll back imported world directory: $directory")
            }
        }
    }

    private fun beginOperation(resultFuture: CompletableFuture<ArchiveResult>): Boolean {
        if (globalStorage.isArchiveLocked()) {
            resultFuture.complete(ArchiveResult(false, "archive_in_progress"))
            return false
        }
        globalStorage.setArchiveLocked(true)
        return true
    }

    private fun createZipAsync(
        output: File,
        temp: File,
        archiveName: String,
        worlds: List<WorldData>,
        worldService: me.awabi2048.myworldmanager.api.service.ApiWorldService,
        bossBar: BossBar,
        executor: Player,
        progressOffset: Int
    ): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                if (temp.exists()) temp.delete()
                ZipOutputStream(Files.newOutputStream(temp.toPath())).use { zip ->
                    addStringToZip(zip, manifestYaml(archiveName, worlds), "manifest.yml")
                    worlds.forEachIndexed { index, worldData ->
                        val worldDir = worldService.getWorldDirectory(worldData)
                        if (Files.isDirectory(worldDir.toPath())) {
                            addDirectoryToZip(zip, worldDir.toPath(), "worlds/${worldData.uuid}/world")
                        }
                        val mwmDataFile = File(plugin.dataFolder.parentFile, "MyWorldManager/my_worlds/${worldData.uuid}.yml")
                        if (mwmDataFile.exists()) {
                            addFileToZip(zip, mwmDataFile, "worlds/${worldData.uuid}/mwm_data.yml")
                        }
                        val chanponFile = storage.worldFile(worldData.uuid)
                        if (chanponFile.exists()) {
                            addFileToZip(zip, chanponFile, "worlds/${worldData.uuid}/chanpon_data.yml")
                        }
                        addStringToZip(
                            zip,
                            serializePortals(worldService.getRelatedPortals(worldData.uuid)),
                            "worlds/${worldData.uuid}/portals.yml"
                        )
                        syncBossBar(bossBar, executor, progressOffset + index, progressOffset + worlds.size)
                    }
                }
                AtomicFilePublisher.publish(temp.toPath(), output.toPath())
                plugin.logger.info("Archive created: $output (${output.length()} bytes)")
                future.complete(Unit)
            } catch (ex: Exception) {
                temp.delete()
                future.completeExceptionally(ex)
            }
        })
        return future
    }

    private fun deleteWorldsSequential(
        worlds: List<WorldData>,
        worldService: me.awabi2048.myworldmanager.api.service.ApiWorldService,
        bossBar: BossBar,
        executor: Player,
        progressOffset: Int
    ): CompletableFuture<Boolean> {
        var chain = CompletableFuture.completedFuture(true)
        worlds.forEachIndexed { index, worldData ->
            chain = chain.thenCompose { previous ->
                if (!previous) {
                    CompletableFuture.completedFuture(false)
                } else {
                    worldService.deleteWorldForMaintenance(worldData.uuid).thenApply { deleted ->
                        if (deleted) {
                            storage.deleteWorld(worldData.uuid)
                            plugin.logger.info("Deleted annual-update world: ${worldData.name} (${worldData.uuid})")
                        }
                        syncBossBar(bossBar, executor, progressOffset + index + 1, progressOffset + worlds.size)
                        deleted
                    }
                }
            }
        }
        return chain
    }

    private fun archiveFileForName(archiveName: String): ArchiveLookup {
        val incoming = File(incomingDir, "$archiveName.zip")
        val stored = File(storageDir, "$archiveName.zip")
        if (incoming.exists() && stored.exists()) {
            return ArchiveLookup(null, "archive_name_conflict")
        }
        return when {
            incoming.exists() -> ArchiveLookup(incoming, null)
            stored.exists() -> ArchiveLookup(stored, null)
            else -> ArchiveLookup(null, null)
        }
    }

    private fun validateArchiveName(archiveName: String): String? {
        if (archiveName.isBlank()) return "archive_name_empty"
        val invalid = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        if (archiveName.any { it in invalid }) return "archive_name_invalid"
        return null
    }

    private fun readWorldData(tempDir: Path, uuid: UUID): WorldData? {
        val file = tempDir.resolve("worlds/$uuid/mwm_data.yml").toFile()
        if (!file.exists()) return null
        val config = YamlConfiguration.loadConfiguration(file)
        return WorldData.deserialize(config.getValues(true))
    }

    private fun restoreChanponData(tempDir: Path, uuid: UUID) {
        val source = tempDir.resolve("worlds/$uuid/chanpon_data.yml").toFile()
        if (source.exists()) {
            source.copyTo(storage.worldFile(uuid), overwrite = true)
        }
    }

    private fun restorePortals(tempDir: Path, uuid: UUID) {
        val file = tempDir.resolve("worlds/$uuid/portals.yml").toFile()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        MyWorldManagerApi.getWorldService()?.replaceRelatedPortals(uuid, deserializePortals(yaml))
    }

    private fun manifestYaml(archiveName: String, worlds: List<WorldData>): String {
        val yaml = YamlConfiguration()
        yaml.set("name", archiveName)
        yaml.set("schema_version", ARCHIVE_SCHEMA_VERSION)
        yaml.set("created_at", LocalDateTime.now().toString())
        yaml.set("world_count", worlds.size)
        yaml.set(
            "worlds",
            worlds.map {
                mapOf(
                    "uuid" to it.uuid.toString(),
                    "name" to it.name,
                    "world_key" to it.worldKey
                )
            }
        )
        return yaml.saveToString()
    }

    private fun addDirectoryToZip(zip: ZipOutputStream, source: Path, rootName: String) {
        Files.walk(source).use { stream ->
            stream.sorted(Comparator.comparing { source.relativize(it).toString() }).forEach { path ->
                if (path.fileName?.toString() == "session.lock") return@forEach
                val relative = source.relativize(path)
                val entryName = if (relative.toString().isEmpty()) {
                    "$rootName/"
                } else {
                    "$rootName/${relative.toString().replace(File.separatorChar, '/')}"
                }
                if (Files.isDirectory(path)) {
                    zip.putNextEntry(ZipEntry(entryName.trimEnd('/') + "/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(entryName))
                    Files.copy(path, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        Files.copy(file.toPath(), zip)
        zip.closeEntry()
    }

    private fun addStringToZip(zip: ZipOutputStream, content: String, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun serializePortals(portals: List<ApiPortalSnapshot>): String {
        val yaml = YamlConfiguration()
        yaml.set(
            "portals",
            portals.map { portal ->
                linkedMapOf(
                    "id" to portal.id.toString(),
                    "world_uuid" to portal.worldUuid?.toString(),
                    "world_key" to portal.worldKey,
                    "x" to portal.x,
                    "y" to portal.y,
                    "z" to portal.z,
                    "target_world_key" to portal.targetWorldKey,
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
            }
        )
        return yaml.saveToString()
    }

    private fun deserializePortals(yaml: YamlConfiguration): List<ApiPortalSnapshot> {
        val list = yaml.getMapList("portals")
        return list.mapNotNull {
            val id = runCatching { UUID.fromString(it["id"]?.toString()) }.getOrNull() ?: return@mapNotNull null
            val worldName = it["world_key"]?.toString() ?: ""
            val x = (it["x"] as? Number)?.toInt() ?: 0
            val y = (it["y"] as? Number)?.toInt() ?: 0
            val z = (it["z"] as? Number)?.toInt() ?: 0
            val worldUuid = it["world_uuid"]?.toString()?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
            val targetWorldName = it["target_world_key"]?.toString()
            val showText = it["show_text"] as? Boolean ?: true
            val ownerUuid = it["owner_uuid"]?.toString()?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            } ?: return@mapNotNull null
            ApiPortalSnapshot(
                id, worldName, x, y, z,
                worldUuid, targetWorldName, showText,
                (it["particle_color_rgb"] as? Number)?.toInt() ?: 0,
                ownerUuid,
                it["created_at"]?.toString() ?: "",
                it["text_display_uuid"]?.toString()?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                },
                it["type"]?.toString() ?: "portal",
                (it["min_x"] as? Number)?.toInt(),
                (it["min_y"] as? Number)?.toInt(),
                (it["min_z"] as? Number)?.toInt(),
                (it["max_x"] as? Number)?.toInt(),
                (it["max_y"] as? Number)?.toInt(),
                (it["max_z"] as? Number)?.toInt()
            )
        }
    }

    private fun createBossBar(player: Player): BossBar {
        return Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID).also { bar ->
            bar.addPlayer(player)
        }
    }

    private fun syncBossBar(bar: BossBar, player: Player, current: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val safeCurrent = current.coerceIn(0, safeTotal)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline) {
                bar.progress = safeCurrent.toDouble() / safeTotal.toDouble()
                bar.setTitle("§bアーカイブ処理中... $safeCurrent/$safeTotal")
            }
        })
    }

    private data class ArchiveLookup(
        val file: File?,
        val error: String?
    )

    companion object {
        private const val ARCHIVE_SCHEMA_VERSION = 2
    }
}

data class ArchiveResult(
    val success: Boolean,
    val message: String
)
