package me.awabi2048.mwmchanpon.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.bukkit.configuration.file.YamlConfiguration

/** 複数ワールド復元全体のコミット境界を記録するマーカーです。 */
internal class RestoreOperationMarker private constructor(
    val directory: File,
    val backupId: String,
    private val journalDirectories: MutableList<String>,
    private var committed: Boolean,
) {
    @Synchronized
    fun journals(): List<File> = journalDirectories.map(::File)

    @Synchronized
    fun addJournal(directory: File) {
        val path = directory.absolutePath
        if (path !in journalDirectories) {
            journalDirectories += path
            persist()
        }
    }

    /**
     * 復元または後始末が完了したジャーナルだけをマーカーから外します。
     * ジャーナル削除と次回起動の間で停止しても、残ったマーカーから再開できます。
     */
    @Synchronized
    fun removeJournal(directory: File) {
        if (journalDirectories.remove(directory.absolutePath)) {
            persist()
        }
    }

    @Synchronized
    fun markCommitted() {
        val previous = committed
        committed = true
        runCatching { persist() }.onFailure {
            committed = previous
            throw it
        }
    }

    @Synchronized
    fun isCommitted(): Boolean = committed

    @Synchronized
    fun delete() {
        if (directory.exists() && !directory.deleteRecursively()) {
            error("Could not delete restore operation marker: $directory")
        }
    }

    @Synchronized
    private fun persist() {
        val config = YamlConfiguration()
        config.set("backup_id", backupId)
        config.set("committed", committed)
        config.set("journals", journalDirectories)
        directory.mkdirs()
        val temporary = File(directory, "operation.yml.tmp")
        val target = File(directory, "operation.yml")
        try {
            config.save(temporary)
            // 名前変更だけが永続化され中身が欠ける状態を防ぐため、先に内容を
            // ディスクへ出し切ります。
            java.nio.channels.FileChannel.open(temporary.toPath(), java.nio.file.StandardOpenOption.WRITE)
                .use { it.force(true) }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    companion object {
        fun create(rootDir: File, backupId: String): RestoreOperationMarker {
            val directory = File(File(rootDir, "restore-operations"), java.util.UUID.randomUUID().toString())
            require(directory.mkdirs()) { "Could not create restore operation marker: $directory" }
            return RestoreOperationMarker(directory, backupId, mutableListOf(), false).also { it.persist() }
        }

        fun load(directory: File): RestoreOperationMarker? {
            val file = File(directory, "operation.yml")
            if (!file.isFile) return null
            return runCatching {
                val config = YamlConfiguration.loadConfiguration(file)
                val rawJournals = config.get("journals")
                require(rawJournals is List<*>) { "journals is missing or invalid" }
                val journals = rawJournals.map { it?.toString()?.takeIf(String::isNotBlank) }
                require(journals.none { it == null }) { "journals contains an empty path" }
                RestoreOperationMarker(
                    directory = directory,
                    backupId = config.getString("backup_id")?.takeIf { it.isNotBlank() }
                        ?: error("backup_id is missing"),
                    journalDirectories = journals.filterNotNull().toMutableList(),
                    committed = config.getBoolean("committed", false),
                )
            }.getOrElse { error("Could not read restore operation marker: ${file.absolutePath}: ${it.message}") }
        }

        fun directories(rootDir: File): List<File> = File(rootDir, "restore-operations")
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
    }
}
