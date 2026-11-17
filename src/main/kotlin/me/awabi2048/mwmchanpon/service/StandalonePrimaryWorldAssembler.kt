package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object StandalonePrimaryWorldAssembler {
    const val PRIMARY_DIRECTORY = "dimensions/minecraft/overworld"
    private val serverMainDataPaths = setOf("level.dat", "level.dat_old", "data")

    fun copy(representativeWorld: Path, serverMainWorld: Path, target: Path) {
        require(Files.isDirectory(representativeWorld)) {
            "primary world directory is missing: $representativeWorld"
        }
        require(Files.isRegularFile(serverMainWorld.resolve("level.dat"))) {
            "server main world level.dat is missing: $serverMainWorld"
        }

        // 26.2では生成設定・天候・ゲームルール等もディメンション固有データです。
        // セーブ直下の共有data/と混ぜず、overworldも他ディメンションと同じ構造にします。
        copyDimension(representativeWorld, target.resolve(PRIMARY_DIRECTORY))
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

    fun copyDimension(source: Path, target: Path) {
        listOf("region", "entities", "poi", "data").forEach { name ->
            val directory = source.resolve(name)
            if (Files.isDirectory(directory)) copyTree(directory, target.resolve(name)) { path ->
                // Paperのゲームモード上書きやプラグイン用データを単体セーブへ持ち込みません。
                val relative = directory.relativize(path)
                name != "data" || relative.iterator().asSequence().firstOrNull()?.toString() != "paper"
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
