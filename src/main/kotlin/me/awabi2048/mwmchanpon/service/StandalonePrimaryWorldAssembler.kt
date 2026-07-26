package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object StandalonePrimaryWorldAssembler {
    private val rootExcludedFiles = setOf("session.lock", "uid.dat")
    private val serverMainDataPaths = setOf("level.dat", "level.dat_old", "data")

    fun copy(representativeWorld: Path, serverMainWorld: Path, target: Path) {
        require(Files.isDirectory(representativeWorld)) {
            "primary world directory is missing: $representativeWorld"
        }
        require(Files.isRegularFile(serverMainWorld.resolve("level.dat"))) {
            "server main world level.dat is missing: $serverMainWorld"
        }

        copyTree(representativeWorld, target) { path ->
            val relative = representativeWorld.relativize(path)
            val first = relative.iterator().asSequence().firstOrNull()?.toString()
            path.fileName?.toString() !in rootExcludedFiles &&
                first !in serverMainDataPaths
        }
        serverMainDataPaths.forEach { name ->
            val source = serverMainWorld.resolve(name)
            val destination = target.resolve(name)
            when {
                Files.isDirectory(source) -> copyTree(source, destination)
                Files.isRegularFile(source) -> {
                    Files.createDirectories(destination.parent)
                    Files.copy(
                        source,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                }
            }
        }
    }

    private fun copyTree(source: Path, target: Path, include: (Path) -> Boolean = { true }) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                if (!include(path)) return@forEach
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(
                        path,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                }
            }
        }
    }
}
