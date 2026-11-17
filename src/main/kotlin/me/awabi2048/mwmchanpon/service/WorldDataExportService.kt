package me.awabi2048.mwmchanpon.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.service.WorldOperation
import me.awabi2048.myworldmanager.api.service.WorldOperationLease
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration

class WorldDataExportService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage,
    private val globalStorage: me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage,
) {
    private val exportDir = File(plugin.dataFolder, "exports")

    init {
        exportDir.mkdirs()
    }

    // 全件出力と統合出力は、同じ保守セッションで対象確定・凍結・復元を行います。
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var abortExport: (() -> Unit)? = null

    /** プラグイン停止時も保守ロックとアンロード状態を残しません。 */
    fun shutdown() { abortExport?.invoke() }

    init {
        Bukkit.getPluginManager().registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
            fun onLogin(event: io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent) {
                if (running.get()) event.kickMessage(maintenanceMessage())
            }

            // ログイン検証通過直後に保守が始まった接続も、退出待ちと同じ扱いにします。
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
            fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
                if (running.get()) event.player.kick(maintenanceMessage())
            }
        }, plugin)
    }

    private fun maintenanceMessage() = com.awabi2048.ccsystem.CCSystem.getAPI().getI18nComponent(
        null, com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys.CHANPON_BACKUP_EXPORT_MENU_MAINTENANCE_KICK,
    )

    fun completedWorlds(): List<WorldData> =
        MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { !it.isArchived && storage.isSubmissionCompleted(it.uuid) }
            .sortedByDescending { it.createdAt }

    fun individualWorlds(): List<WorldData> {
        val spawnUuid = globalStorage.getSpawnWorldUuid() ?: error("スポーンワールドが設定されていません。")
        val spawn = MyWorldManagerApi.getWorldRepository()?.findByUuid(spawnUuid)
            ?: error("スポーンワールドが見つかりません: $spawnUuid")
        require(!spawn.isArchived) { "スポーンワールドがアーカイブされています。" }
        return (completedWorlds() + spawn).distinctBy { it.uuid }
    }

    fun exportAllIndividualWorlds(): CompletableFuture<WorldDataExportResult> =
        runCatching { startExport(individualWorlds().map { it.uuid }, individual = true) }
            .getOrElse { failed(it.message ?: "出力対象を取得できません。") }

    fun exportStandaloneWorld(selectedWorldUuids: List<UUID>): CompletableFuture<WorldDataExportResult> =
        startExport(selectedWorldUuids, individual = false)

    private fun failed(message: String) = CompletableFuture.completedFuture(WorldDataExportResult(false, null, message))

    private fun validateWorlds(worlds: List<WorldData>, individual: Boolean) {
        val eligible = (if (individual) individualWorlds() else completedWorlds()).mapTo(mutableSetOf()) { it.uuid }
        require(worlds.isNotEmpty() && worlds.all { it.uuid in eligible }) { "出力対象の完成状態が変更されています。" }
        if (individual) require(worlds.map { it.uuid }.toSet() == eligible) { "全件出力の対象が変更されています。再実行してください。" }
        val migration = MyWorldManagerApi.evaluateMigration(worlds.map { it.uuid }, includeGlobal = true, includeUnresolved = false)
        require(migration.allowed) { migration.blocks.first().message }
        require(worlds.all { storage.isWorldDataCurrent(it.uuid) }) { "Chanponデータの移行が必要です。/mwm migration を実行してください。" }
    }

    private fun startExport(selectedWorldUuids: List<UUID>, individual: Boolean): CompletableFuture<WorldDataExportResult> {
        check(Bukkit.isPrimaryThread()) { "出力はサーバースレッドから開始してください。" }
        if (!running.compareAndSet(false, true)) return failed("ワールド出力を実行中です。")
        val leases = mutableListOf<WorldOperationLease>()
        val snapshots = mutableListOf<me.awabi2048.myworldmanager.api.service.ApiWorldSnapshot>()
        val future = CompletableFuture<WorldDataExportResult>()
        val work = File(exportDir, ".export-${UUID.randomUUID()}")
        val originallyLoaded = mutableSetOf<UUID>()
        val core = MyWorldManagerApi.getWorldService()
        var finished = false
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        fun finish(result: WorldDataExportResult, temporaryZip: Path? = null) {
            check(Bukkit.isPrimaryThread())
            if (finished) return
            finished = true
            var finalResult = result
            // closeによる再ロードもロック保持中に完了させ、途中で入場できる隙間を作りません。
            try {
                val restoreErrors = snapshots.asReversed().mapNotNull { snapshot ->
                    runCatching { snapshot.close() }.exceptionOrNull()
                }
                check(restoreErrors.isEmpty()) { "ワールド復元失敗: ${restoreErrors.first().message}" }
                originallyLoaded.forEach { uuid ->
                    val world = MyWorldManagerApi.getWorldRepository()?.findByUuid(uuid)
                    check(world != null && Bukkit.getWorld(core!!.getWorldFolderName(world)) != null) {
                        "出力後のワールド再ロードに失敗しました: $uuid"
                    }
                }
                if (result.success && temporaryZip != null) {
                    val output = requireNotNull(result.file).toPath()
                    try {
                        Files.move(temporaryZip, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporaryZip, output, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } catch (error: Exception) {
                plugin.logger.severe("ワールド出力の復元・公開失敗: ${error.message}")
                finalResult = WorldDataExportResult(false, null, error.message ?: "ワールドの復元に失敗しました。")
            } finally {
                leases.asReversed().forEach { it.close() }
                runCatching { work.deleteRecursively() }
                abortExport = null
                running.set(false)
            }
            plugin.logger.info("ワールド出力: ${finalResult.message} ${finalResult.file ?: ""}")
            future.complete(finalResult)
        }

        abortExport = {
            cancelled.set(true)
            finish(WorldDataExportResult(false, null, "プラグイン停止のため出力を中止しました。"))
        }
        try {
            require(core != null) { "MWM APIを利用できません。" }
            require(selectedWorldUuids.distinct().size == selectedWorldUuids.size) { "出力対象が重複しています。" }
            val repository = requireNotNull(MyWorldManagerApi.getWorldRepository())
            val worlds = selectedWorldUuids.map { repository.findByUuid(it) ?: error("ワールドが見つかりません: $it") }
            validateWorlds(worlds, individual)
            val fiscalYear = plugin.config.getInt("standalone_export.fiscal_year")
            val plan = StandaloneExportPlan.create(fiscalYear, worlds.map { ExportWorldIdentity(it.uuid, it.name) }, individual)
            worlds.forEach { world ->
                leases += MyWorldManagerApi.tryAcquireWorldOperation(world.uuid, WorldOperation.BACKUP)
                    ?: error("ワールドは別の操作中です: ${world.name}")
            }
            validateWorlds(worlds, individual)
            val worldDirectories = worlds.associate { it.uuid to core.getWorldDirectory(it).toPath() }
            val worldKeys = worlds.associate { it.uuid to "minecraft:${core.getWorldFolderName(it)}" }
            val spawns = worlds.associate { world ->
                val loaded = Bukkit.getWorld(core.getWorldFolderName(world))
                if (loaded != null) originallyLoaded += world.uuid
                val spawn = world.spawnPosGuest ?: world.spawnPosMember ?: loaded?.spawnLocation
                world.uuid to ExportCoordinates(spawn?.x ?: 0.5, spawn?.y ?: 100.0, spawn?.z ?: 0.5, spawn?.yaw ?: 0.0f)
            }
            val portals = worlds.flatMap { core.getRelatedPortals(it.uuid) }.distinctBy { it.id }
            val sharedSource = ServerMainWorldDirectory.resolve(Bukkit.getWorldContainer().toPath())
            @Suppress("DEPRECATION")
            val dataVersion = Bukkit.getUnsafe().dataVersion
            val minecraftVersion = Bukkit.getMinecraftVersion()
            require(work.mkdirs()) { "一時ディレクトリを作成できません。" }
            // スポーンも対象になるため退避ではなくキックします。退出リスナーの完了後に凍結します。
            Bukkit.getOnlinePlayers().toList().forEach { it.kick(maintenanceMessage()) }
            var waitTicks = 0
            object : org.bukkit.scheduler.BukkitRunnable() {
                override fun run() {
                    if (finished) { cancel(); return }
                    if (Bukkit.getOnlinePlayers().isNotEmpty()) {
                        if (++waitTicks < 100) return
                        cancel()
                        finish(WorldDataExportResult(false, null, "プレイヤーの退出が完了しませんでした。"))
                        return
                    }
                    cancel()
                    try {
                        validateWorlds(worlds, individual)
                        worlds.forEachIndexed { index, world ->
                            val snapshot = core.beginWorldSnapshot(world.uuid, leases[index])
                            snapshots += snapshot
                            require(snapshot.quiescent) { snapshot.failureReason ?: "ワールドを凍結できません。" }
                            require(Bukkit.getWorld(core.getWorldFolderName(world)) == null) { "ワールドをアンロードできません: ${world.name}" }
                        }
                        // サーバーメインワールドは必須ワールドのため、同期flushと同一tick内のコピーで
                        // 共有データだけを一度固定します。非同期処理は稼働中の共有データを読みません。
                        Bukkit.getWorlds().firstOrNull {
                            it.worldFolder.toPath().toAbsolutePath().normalize() == sharedSource.path
                        }?.save(true)
                        val shared = work.toPath().resolve("shared")
                        Files.createDirectories(shared)
                        listOf("level.dat", "level.dat_old", "data").forEach { name ->
                            val source = sharedSource.path.resolve(name)
                            if (Files.isDirectory(source)) copyTree(source, shared.resolve(name))
                            else if (Files.isRegularFile(source)) Files.copy(source, shared.resolve(name))
                        }
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                            try {
                                val bundle = work.toPath().resolve("bundle")
                                Files.createDirectories(bundle)
                                plan.archives.forEachIndexed { index, archive ->
                                    check(!cancelled.get()) { "出力が中止されました。" }
                                    val included = archive.worldUuids.map { id -> worlds.first { it.uuid == id } }
                                    val staging = work.toPath().resolve("world-$index")
                                    assembleWorld(staging, included, worldDirectories, worldKeys, spawns, portals, shared,
                                        archive.fileName, fiscalYear, sharedSource.levelName, dataVersion, minecraftVersion)
                                    zipWorld(staging, bundle.resolve(archive.fileName))
                                    staging.toFile().deleteRecursively()
                                }
                                val temporaryZip = work.toPath().resolve("output.zip")
                                if (individual) {
                                    val manifest = YamlConfiguration()
                                    manifest.set("format", 1)
                                    manifest.set("mode", "individual")
                                    manifest.set("archives", plan.archives.map { linkedMapOf("file" to it.fileName, "world_uuid" to it.worldUuids.single().toString()) })
                                    manifest.save(bundle.resolve("mwm-export-bundle.yml").toFile())
                                    zipWorld(bundle, temporaryZip)
                                } else Files.move(bundle.resolve(plan.archives.single().fileName), temporaryZip)
                                check(!cancelled.get()) { "出力が中止されました。" }
                                Bukkit.getScheduler().runTask(plugin, Runnable {
                                    try {
                                        require(snapshots.all { it.isStillQuiescent() }) { "コピー中にワールドが更新されたため、出力を破棄します。" }
                                        finish(WorldDataExportResult(true, File(exportDir, plan.fileName), "ワールド出力が完了しました。"), temporaryZip)
                                    } catch (error: Exception) {
                                        finish(WorldDataExportResult(false, null, error.message ?: "静止状態の確認に失敗しました。"))
                                    }
                                })
                            } catch (error: Exception) {
                                if (cancelled.get()) { work.deleteRecursively(); return@Runnable }
                                Bukkit.getScheduler().runTask(plugin, Runnable {
                                    finish(WorldDataExportResult(false, null, error.message ?: "ZIPの生成に失敗しました。"))
                                })
                            }
                        })
                    } catch (error: Exception) {
                        finish(WorldDataExportResult(false, null, error.message ?: "ワールドの凍結に失敗しました。"))
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L)
        } catch (error: Exception) {
            finish(WorldDataExportResult(false, null, error.message ?: "出力準備に失敗しました。"))
        }
        return future
    }

    private fun assembleWorld(
        root: Path,
        worlds: List<WorldData>,
        directories: Map<UUID, Path>,
        worldKeys: Map<UUID, String>,
        spawns: Map<UUID, ExportCoordinates>,
        portals: List<me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot>,
        shared: Path,
        fileName: String,
        fiscalYear: Int,
        sharedSourceName: String,
        dataVersion: Int,
        minecraftVersion: String,
    ) {
        val primary = worlds.first()
        copyPrimaryWorld(directories.getValue(primary.uuid), shared, root)
        val spawn = spawns.getValue(primary.uuid)
        val metadata = StandaloneLevelMetadata(primary.name, System.currentTimeMillis(), dataVersion, minecraftVersion,
            kotlin.math.floor(spawn.x).toInt(), kotlin.math.floor(spawn.y).toInt(), kotlin.math.floor(spawn.z).toInt(), spawn.yaw, worlds.size > 1)
        listOf("level.dat", "level.dat_old").forEach { name ->
            val path = root.resolve(name)
            if (Files.isRegularFile(path)) StandaloneLevelDatRewriter.rewrite(path, path, metadata)
        }
        require(Files.isRegularFile(root.resolve(StandalonePrimaryWorldAssembler.PRIMARY_DIRECTORY).resolve("data/minecraft/world_gen_settings.dat"))) {
            "代表ワールドの26.2生成設定がありません。先にMinecraft側で移行してください。"
        }
        writeDimensions(root, worlds.drop(1), directories)
        val selectedIds = worlds.mapTo(mutableSetOf()) { it.uuid }
        val selectedKeys = worldKeys.filterKeys { it in selectedIds }
        val relatedPortals = portals.filter { it.worldKey in selectedKeys.values }
        writePortalCommandBlocks(root, primary, worlds, selectedKeys, spawns, relatedPortals)
        writeManifest(root.toFile(), fileName, fiscalYear, primary, worlds, relatedPortals, sharedSourceName, selectedKeys)
    }

    private fun copyPrimaryWorld(source: Path, serverMainWorld: Path, target: Path) {
        StandalonePrimaryWorldAssembler.copy(source, serverMainWorld, target)
    }

    private fun writeDimensions(
        root: Path,
        worlds: List<WorldData>,
        worldDirectories: Map<UUID, Path>
    ) {
        if (worlds.isEmpty()) return
        val packRoot = root.resolve("datapacks/mwm-standalone-export")
        Files.createDirectories(packRoot)
        Files.writeString(packRoot.resolve("pack.mcmeta"), StandaloneWorldExportLayout.packMetadata())

        worlds.forEach { world ->
            val dimensionId = StandaloneWorldExportLayout.dimensionId(world.uuid)
            val definition = packRoot.resolve(
                "data/${StandaloneWorldExportLayout.NAMESPACE}/dimension/$dimensionId.json"
            )
            Files.createDirectories(definition.parent)
            Files.writeString(definition, StandaloneWorldExportLayout.dimensionDefinition())

            val source = worldDirectories.getValue(world.uuid)
            require(Files.isDirectory(source)) { "world directory is missing: ${world.uuid}" }
            val target = root.resolve(
                "dimensions/${StandaloneWorldExportLayout.NAMESPACE}/$dimensionId"
            )
            StandalonePrimaryWorldAssembler.copyDimension(source, target)
        }
    }

    private fun writeManifest(
        root: File,
        outputFileName: String,
        fiscalYear: Int,
        primary: WorldData,
        worlds: List<WorldData>,
        portals: List<me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot>,
        serverMainWorldName: String,
        worldKeys: Map<UUID, String>,
    ) {
        val manifest = YamlConfiguration()
        manifest.set("format", 2)
        manifest.set("created_at", LocalDateTime.now().toString())
        manifest.set("output_file", outputFileName)
        manifest.set("fiscal_year", fiscalYear)
        manifest.set("game_mode", "creative")
        manifest.set("allow_commands", true)
        manifest.set("shared_data_source", serverMainWorldName)
        manifest.set("shared_data_policy", "all selected dimensions use the running server main world data/")
        manifest.set("primary_world_uuid", primary.uuid.toString())
        manifest.set(
            "worlds",
            worlds.map { world ->
                linkedMapOf(
                    "uuid" to world.uuid.toString(),
                    "name" to world.name,
                    "dimension" to if (world.uuid == primary.uuid) {
                        "minecraft:overworld"
                    } else {
                        StandaloneWorldExportLayout.dimensionKey(world.uuid)
                    }
                )
            }
        )
        val selectedIds = worlds.mapTo(mutableSetOf()) { it.uuid }
        manifest.set(
            "portals",
            portals.map { portal ->
                linkedMapOf(
                    "id" to portal.id.toString(),
                    "source_world_key" to portal.worldKey,
                    "x" to portal.x,
                    "y" to portal.y,
                    "z" to portal.z,
                    "target_world_uuid" to portal.worldUuid?.toString(),
                    "target_world_key" to portal.targetWorldKey,
                    "included_target" to (StandalonePortalTargets.resolve(portal, worldKeys) in selectedIds),
                    "converted" to (StandalonePortalTargets.resolve(portal, worldKeys) in selectedIds),
                    "type" to portal.type,
                    "min" to listOf(portal.minX, portal.minY, portal.minZ),
                    "max" to listOf(portal.maxX, portal.maxY, portal.maxZ)
                )
            }
        )
        manifest.save(File(root, "mwm-export-manifest.yml"))
    }

    private fun writePortalCommandBlocks(
        root: Path,
        primary: WorldData,
        worlds: List<WorldData>,
        worldKeys: Map<UUID, String>,
        spawnCoordinates: Map<UUID, ExportCoordinates>,
        portals: List<me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot>
    ) {
        val dimensionByWorld = worlds.associate { world ->
            world.uuid to if (world.uuid == primary.uuid) {
                "minecraft:overworld"
            } else {
                StandaloneWorldExportLayout.dimensionKey(world.uuid)
            }
        }
        val sourceWorldByKey = worldKeys.entries.associate { it.value to it.key }
        portals.forEach { portal ->
            val sourceUuid = sourceWorldByKey[portal.worldKey] ?: return@forEach
            val targetUuid = StandalonePortalTargets.resolve(portal, worldKeys) ?: return@forEach
            dimensionByWorld[sourceUuid] ?: return@forEach
            val targetDimension = dimensionByWorld[targetUuid] ?: return@forEach
            val spawn = spawnCoordinates[targetUuid] ?: return@forEach
            val sourceRoot = if (sourceUuid == primary.uuid) {
                root.resolve(StandalonePrimaryWorldAssembler.PRIMARY_DIRECTORY)
            } else {
                root.resolve(
                    "dimensions/${StandaloneWorldExportLayout.NAMESPACE}/${StandaloneWorldExportLayout.dimensionId(sourceUuid)}",
                )
            }
            StandalonePortalRegionEditor.write(
                sourceRoot,
                StandalonePortalCommand(
                    portal.x,
                    portal.y,
                    portal.z,
                    "execute as @p at @s in $targetDimension run tp @s ${spawn.x} ${spawn.y} ${spawn.z}",
                ),
            )
        }
    }

    private fun copyTree(source: Path, target: Path, include: (Path) -> Boolean = { true }) {
        Files.walk(source).use { paths ->
            paths.sorted(Comparator.comparing { source.relativize(it).toString() }).forEach { path ->
                if (!include(path)) return@forEach
                val relative = source.relativize(path)
                val destination = target.resolve(relative)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(
                        path,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                    )
                }
            }
        }
    }

    private fun zipWorld(source: Path, output: Path) {
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            Files.walk(source).use { paths ->
                paths.sorted(Comparator.comparing { source.relativize(it).toString() }).forEach { path ->
                    val relative = source.relativize(path)
                    if (relative.toString().isEmpty()) return@forEach
                    val entryName = relative.toString().replace(File.separatorChar, '/')
                    if (Files.isDirectory(path)) {
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
    }

}

private data class ExportCoordinates(val x: Double, val y: Double, val z: Double, val yaw: Float)

internal object StandaloneExportFileName {
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    fun create(fiscalYear: Int, worldNames: List<String>): String {
        require(fiscalYear in 2000..9999) { "fiscal year must be between 2000 and 9999" }
        require(worldNames.isNotEmpty()) { "at least one world is required" }
        worldNames.forEach { name ->
            require(name.isNotBlank() && !forbidden.containsMatchIn(name)) {
                "world name contains characters that cannot be used in an export file name: $name"
            }
        }
        val suffix = if (worldNames.size == 1) "" else "ほか"
        return "［さばちゃんぽん$fiscalYear］【${worldNames.first()}】$suffix.zip"
    }
}

data class WorldDataExportResult(
    val success: Boolean,
    val file: File?,
    val message: String
)
