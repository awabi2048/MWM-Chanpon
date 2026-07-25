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
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration

class WorldDataExportService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) {
    private val exportDir = File(plugin.dataFolder, "exports")

    init {
        exportDir.mkdirs()
    }

    fun exportStandaloneWorld(selectedWorldUuids: List<UUID>): CompletableFuture<WorldDataExportResult> {
        if (selectedWorldUuids.distinct().size != selectedWorldUuids.size) {
            return CompletableFuture.completedFuture(
                WorldDataExportResult(false, null, "selection contains duplicate worlds")
            )
        }
        val available = productionWorlds().associateBy { it.uuid }
        val worlds = selectedWorldUuids.mapNotNull(available::get)
        if (worlds.isEmpty()) {
            return CompletableFuture.completedFuture(
                WorldDataExportResult(false, null, "no worlds selected")
            )
        }
        if (worlds.size != selectedWorldUuids.size) {
            return CompletableFuture.completedFuture(
                WorldDataExportResult(false, null, "selection contains a world that is not exportable")
            )
        }

        val core = MyWorldManagerApi.getWorldService()
            ?: return CompletableFuture.completedFuture(
                WorldDataExportResult(false, null, "core api unavailable")
            )
        saveLoadedWorlds(worlds)
        val worldDirectories = worlds.associate { it.uuid to core.getWorldDirectory(it).toPath() }
        val worldKeys = worlds.associate { world ->
            world.uuid to "minecraft:${core.getWorldFolderName(world)}"
        }
        val spawnCoordinates = worlds.associate { world ->
            val loaded = Bukkit.getWorld(core.getWorldFolderName(world))
            val spawn = world.spawnPosGuest ?: world.spawnPosMember ?: loaded?.spawnLocation
            world.uuid to ExportCoordinates(
                spawn?.x ?: 0.5,
                spawn?.y ?: 100.0,
                spawn?.z ?: 0.5,
                spawn?.yaw ?: 0.0f
            )
        }
        val portalSnapshots = worlds.flatMap { core.getRelatedPortals(it.uuid) }.distinctBy { it.id }
        val bootstrapWorldDirectory = Bukkit.getWorlds()
            .asSequence()
            .map { it.worldFolder.toPath() }
            .firstOrNull { Files.isRegularFile(it.resolve("level.dat")) }
            ?: return CompletableFuture.completedFuture(
                WorldDataExportResult(false, null, "bootstrap level.dat is unavailable")
            )

        val fiscalYear = plugin.config.getInt("standalone_export.fiscal_year")
        val output = File(exportDir, StandaloneExportFileName.create(fiscalYear, worlds.map { it.name }))
        val future = CompletableFuture<WorldDataExportResult>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val staging = File(exportDir, ".${output.nameWithoutExtension}-${UUID.randomUUID()}")
            val temporaryZip = File(exportDir, "${output.name}.tmp")
            try {
                staging.mkdirs()
                val primary = worlds.first()
                copyPrimaryWorld(
                    worldDirectories.getValue(primary.uuid),
                    bootstrapWorldDirectory,
                    staging.toPath()
                )
                val now = System.currentTimeMillis()
                val primarySpawn = spawnCoordinates.getValue(primary.uuid)
                val metadata = StandaloneLevelMetadata(
                    levelName = primary.name,
                    lastPlayed = now,
                    dataVersion = Bukkit.getUnsafe().dataVersion,
                    versionName = Bukkit.getMinecraftVersion(),
                    spawnX = kotlin.math.floor(primarySpawn.x).toInt(),
                    spawnY = kotlin.math.floor(primarySpawn.y).toInt(),
                    spawnZ = kotlin.math.floor(primarySpawn.z).toInt(),
                    spawnAngle = primarySpawn.yaw,
                )
                StandaloneLevelDatRewriter.rewrite(
                    staging.toPath().resolve("level.dat"),
                    staging.toPath().resolve("level.dat"),
                    metadata,
                )
                val oldLevelData = staging.toPath().resolve("level.dat_old")
                if (Files.isRegularFile(oldLevelData)) {
                    StandaloneLevelDatRewriter.rewrite(oldLevelData, oldLevelData, metadata)
                }
                writeDimensions(staging.toPath(), worlds.drop(1), worldDirectories)
                writePortalFunctions(
                    staging.toPath(),
                    primary,
                    worlds,
                    worldKeys,
                    spawnCoordinates,
                    portalSnapshots
                )
                writeManifest(staging, output.name, fiscalYear, primary, worlds, portalSnapshots)
                zipWorld(staging.toPath(), temporaryZip.toPath())
                try {
                    Files.move(
                        temporaryZip.toPath(),
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(
                        temporaryZip.toPath(),
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                future.complete(WorldDataExportResult(true, output, "standalone export completed"))
            } catch (ex: Exception) {
                plugin.logger.warning("Standalone world export failed: ${ex.message}")
                temporaryZip.delete()
                output.delete()
                future.complete(WorldDataExportResult(false, null, ex.message ?: "export failed"))
            } finally {
                staging.deleteRecursively()
            }
        })
        return future
    }

    fun productionWorlds(): List<WorldData> =
        MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
            .sortedByDescending { it.createdAt }

    private fun saveLoadedWorlds(worlds: List<WorldData>) {
        val core = MyWorldManagerApi.getWorldService() ?: return
        worlds.forEach { worldData ->
            Bukkit.getWorld(core.getWorldFolderName(worldData))?.save()
        }
    }

    private fun copyPrimaryWorld(source: Path, bootstrapWorld: Path, target: Path) {
        require(Files.isDirectory(source)) { "primary world directory is missing: $source" }
        copyTree(source, target) { path ->
            path.fileName?.toString() !in ROOT_EXCLUDED_FILES
        }
        if (!Files.isRegularFile(target.resolve("level.dat"))) {
            Files.copy(
                bootstrapWorld.resolve("level.dat"),
                target.resolve("level.dat"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
            val oldLevelData = bootstrapWorld.resolve("level.dat_old")
            if (Files.isRegularFile(oldLevelData)) {
                Files.copy(
                    oldLevelData,
                    target.resolve("level.dat_old"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
            }
        }
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
            DIMENSION_DIRECTORIES.forEach { name ->
                val sourceDirectory = source.resolve(name)
                if (Files.isDirectory(sourceDirectory)) {
                    copyTree(sourceDirectory, target.resolve(name))
                }
            }
        }
    }

    private fun writeManifest(
        root: File,
        outputFileName: String,
        fiscalYear: Int,
        primary: WorldData,
        worlds: List<WorldData>,
        portals: List<me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot>
    ) {
        val manifest = YamlConfiguration()
        manifest.set("format", 2)
        manifest.set("created_at", LocalDateTime.now().toString())
        manifest.set("output_file", outputFileName)
        manifest.set("fiscal_year", fiscalYear)
        manifest.set("game_mode", "creative")
        manifest.set("allow_commands", true)
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
                    "included_target" to (portal.worldUuid == null || portal.worldUuid in selectedIds),
                    "type" to portal.type,
                    "min" to listOf(portal.minX, portal.minY, portal.minZ),
                    "max" to listOf(portal.maxX, portal.maxY, portal.maxZ)
                )
            }
        )
        manifest.save(File(root, "mwm-export-manifest.yml"))
    }

    private fun writePortalFunctions(
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
        val commands = portals.mapNotNull { portal ->
            val sourceUuid = sourceWorldByKey[portal.worldKey] ?: return@mapNotNull null
            val targetUuid = portal.worldUuid ?: return@mapNotNull null
            val sourceDimension = dimensionByWorld[sourceUuid] ?: return@mapNotNull null
            val targetDimension = dimensionByWorld[targetUuid] ?: return@mapNotNull null
            val spawn = spawnCoordinates[targetUuid] ?: return@mapNotNull null
            val minX = minOf(portal.minX ?: portal.x, portal.maxX ?: portal.x)
            val minY = minOf(portal.minY ?: portal.y, portal.maxY ?: portal.y)
            val minZ = minOf(portal.minZ ?: portal.z, portal.maxZ ?: portal.z)
            val maxX = maxOf(portal.minX ?: portal.x, portal.maxX ?: portal.x)
            val maxY = maxOf(portal.maxY ?: portal.y + 2, portal.minY ?: portal.y + 2)
            val maxZ = maxOf(portal.minZ ?: portal.z, portal.maxZ ?: portal.z)
            "execute in $sourceDimension as @a[x=$minX,y=$minY,z=$minZ,dx=${maxX - minX},dy=${maxY - minY},dz=${maxZ - minZ}] run execute in $targetDimension run tp @s ${spawn.x} ${spawn.y} ${spawn.z}"
        }
        if (commands.isEmpty()) return

        val packRoot = root.resolve("datapacks/mwm-standalone-export")
        Files.createDirectories(packRoot)
        Files.writeString(packRoot.resolve("pack.mcmeta"), StandaloneWorldExportLayout.packMetadata())
        val tickFunction = packRoot.resolve(
            "data/${StandaloneWorldExportLayout.NAMESPACE}/function/portal_tick.mcfunction"
        )
        Files.createDirectories(tickFunction.parent)
        Files.writeString(tickFunction, commands.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        val tickTag = packRoot.resolve("data/minecraft/tags/function/tick.json")
        Files.createDirectories(tickTag.parent)
        Files.writeString(
            tickTag,
            """{"values":["${StandaloneWorldExportLayout.NAMESPACE}:portal_tick"]}"""
        )
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

    companion object {
        private val ROOT_EXCLUDED_FILES = setOf("session.lock", "uid.dat")
        private val DIMENSION_DIRECTORIES = listOf("region", "entities", "poi")
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
