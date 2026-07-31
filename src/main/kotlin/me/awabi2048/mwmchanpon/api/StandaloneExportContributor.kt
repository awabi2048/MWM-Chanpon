package me.awabi2048.mwmchanpon.api

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class StandaloneExportWorld(
    val uuid: UUID,
    val sourceWorldName: String,
    val dimensionKey: String,
    val primary: Boolean,
)

data class StandaloneExportContext(
    val worlds: List<StandaloneExportWorld>,
)

fun interface PreparedStandaloneExport {
    fun writeTo(stagingWorld: Path)
}

fun interface StandaloneExportContributor {
    /**
     * Called synchronously on the server thread. Implementations must validate the complete
     * selected export and capture immutable data here. Throwing aborts the export before any
     * output archive is published.
     */
    fun prepare(context: StandaloneExportContext): PreparedStandaloneExport
}

object StandaloneExportContributors {
    private val contributors = CopyOnWriteArrayList<StandaloneExportContributor>()

    @JvmStatic
    fun register(contributor: StandaloneExportContributor) {
        require(contributor !in contributors) { "contributor is already registered" }
        contributors += contributor
    }

    @JvmStatic
    fun unregister(contributor: StandaloneExportContributor) {
        contributors -= contributor
    }

    internal fun prepare(context: StandaloneExportContext): List<PreparedStandaloneExport> =
        contributors.map { it.prepare(context) }
}
