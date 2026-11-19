package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object StandalonePrimaryWorldAssembler {
    const val PRIMARY_DIRECTORY = "dimensions/minecraft/overworld"
    private const val WORLD_GEN_SETTINGS = "data/minecraft/world_gen_settings.dat"
    private val serverMainDataPaths = setOf("level.dat", "level.dat_old", "data")

    fun copy(representativeWorld: Path, serverMainWorld: Path, target: Path) {
        require(Files.isDirectory(representativeWorld)) {
            "primary world directory is missing: $representativeWorld"
        }
        require(Files.isRegularFile(serverMainWorld.resolve("level.dat"))) {
            "server main world level.dat is missing: $serverMainWorld"
        }
        val generatorSettings = representativeWorld.resolve(WORLD_GEN_SETTINGS)
        require(Files.isRegularFile(generatorSettings)) {
            "代表ワールドの26.2生成設定がありません。先にMinecraft側で移行してください。"
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
        // Paper/Chiyogamiはディメンション配下を、Java版クライアントはセーブ直下を読みます。
        // 共有データに同名ファイルがあっても、代表ワールドの設定で統一します。
        val clientSettings = target.resolve(WORLD_GEN_SETTINGS)
        Files.createDirectories(clientSettings.parent)
        Files.copy(generatorSettings, clientSettings, StandardCopyOption.REPLACE_EXISTING)
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
