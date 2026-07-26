package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal data class ServerMainWorldSource(
    val levelName: String,
    val path: Path,
)

/**
 * server.properties の level-name を正本として、サーバー直下のメインワールドを解決します。
 */
internal object ServerMainWorldDirectory {
    fun resolve(serverRoot: Path): ServerMainWorldSource {
        val normalizedRoot = serverRoot.toAbsolutePath().normalize()
        val propertiesPath = normalizedRoot.resolve("server.properties")
        require(Files.isRegularFile(propertiesPath)) {
            "server.properties is unavailable"
        }

        val properties = Properties()
        Files.newInputStream(propertiesPath).use(properties::load)
        val levelName = properties.getProperty("level-name")?.trim().orEmpty()
        require(levelName.isNotEmpty()) {
            "server.properties level-name is unavailable"
        }

        val worldPath = normalizedRoot.resolve(levelName).normalize()
        require(worldPath.startsWith(normalizedRoot)) {
            "server.properties level-name points outside the server directory"
        }
        require(Files.isRegularFile(worldPath.resolve("level.dat"))) {
            "server main world level.dat is unavailable: $levelName"
        }
        return ServerMainWorldSource(levelName, worldPath)
    }
}
