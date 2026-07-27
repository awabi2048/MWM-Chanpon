package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveSafetyContractTest {
    private val source = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon/service/ArchiveService.kt",
    ).readText()

    @Test
    fun `起動時に前プロセスのアーカイブロックを解除する`() {
        assertTrue("globalStorage.setArchiveLocked(false)" in source)
    }

    @Test
    fun `インポート失敗時に全ての永続化先を巻き戻す`() {
        val rollback = functionBody("rollbackImportedWorlds")
        assertTrue("replaceRelatedPortals(imported.uuid, emptyList())" in rollback)
        assertTrue("repository.delete(imported.uuid)" in rollback)
        assertTrue("storage.worldFile(imported.uuid).delete()" in rollback)
        assertTrue("directory.deleteRecursively()" in rollback)
    }

    private fun functionBody(name: String): String {
        val start = source.indexOf("private fun $name")
        require(start >= 0) { "$name が見つかりません" }
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("$name の本体が閉じられていません")
    }
}
