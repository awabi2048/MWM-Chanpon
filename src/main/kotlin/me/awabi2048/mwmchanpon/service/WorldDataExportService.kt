package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorldDataExportService(
    private val plugin: MWMChanponPlugin,
    private val storage: ChanponWorldStorage
) {
    private val exportDir = File(plugin.dataFolder, "exports")
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    init {
        exportDir.mkdirs()
    }

    fun exportWorld(worldUuid: UUID): CompletableFuture<WorldDataExportResult> {
        val world = productionWorlds().firstOrNull { it.uuid == worldUuid }
            ?: return CompletableFuture.completedFuture(WorldDataExportResult(false, null, "world is not exportable"))
        return exportWorlds(listOf(world), "world-${world.uuid}")
    }

    fun exportAll(): CompletableFuture<WorldDataExportResult> {
        val worlds = productionWorlds()
        if (worlds.isEmpty()) {
            return CompletableFuture.completedFuture(WorldDataExportResult(false, null, "no production worlds"))
        }
        return exportWorlds(worlds, "all")
    }

    fun productionWorlds(): List<WorldData> {
        return MyWorldManagerApi.getWorldRepository()?.findAll().orEmpty()
            .filter { storage.isProductionWorld(it.uuid) }
            .sortedByDescending { it.createdAt }
    }

    private fun exportWorlds(worlds: List<WorldData>, label: String): CompletableFuture<WorldDataExportResult> {
        val core = MyWorldManagerApi.getWorldService()
            ?: return CompletableFuture.completedFuture(WorldDataExportResult(false, null, "core api unavailable"))
        val levelDataDir = File(Bukkit.getWorldContainer(), "level/data")
        if (!levelDataDir.isDirectory) {
            return CompletableFuture.completedFuture(WorldDataExportResult(false, null, "level data directory is missing"))
        }
        saveLoadedWorlds(worlds)

        val output = File(exportDir, "${LocalDateTime.now().format(formatter)}-$label.zip")
        val future = CompletableFuture<WorldDataExportResult>()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                output.parentFile?.mkdirs()
                val temp = File(output.parentFile, "${output.name}.tmp")
                if (temp.exists()) temp.delete()
                ZipOutputStream(Files.newOutputStream(temp.toPath())).use { zip ->
                    worlds.forEach { world ->
                        val worldDir = core.getWorldDirectory(world)
                        if (!worldDir.isDirectory) error("world directory missing: ${world.uuid}")
                        copyLevelData(levelDataDir.toPath(), File(worldDir, "data").toPath())
                        addDirectoryToZip(zip, worldDir.toPath(), core.getWorldFolderName(world))
                    }
                }
                Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                future.complete(WorldDataExportResult(true, output, "export completed"))
            } catch (ex: Exception) {
                plugin.logger.warning("World data export failed: ${ex.message}")
                output.delete()
                future.complete(WorldDataExportResult(false, null, ex.message ?: "export failed"))
            }
        })
        return future
    }

    private fun saveLoadedWorlds(worlds: List<WorldData>) {
        val core = MyWorldManagerApi.getWorldService() ?: return
        worlds.forEach { worldData ->
            Bukkit.getWorld(core.getWorldFolderName(worldData))?.save()
        }
        Bukkit.getWorld("level")?.save()
    }

    private fun copyLevelData(source: Path, target: Path) {
        Files.createDirectories(target)
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val relative = source.relativize(path)
                if (relative.toString().isEmpty()) return@forEach
                val destination = target.resolve(relative)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
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
}

data class WorldDataExportResult(
    val success: Boolean,
    val file: File?,
    val message: String
)
