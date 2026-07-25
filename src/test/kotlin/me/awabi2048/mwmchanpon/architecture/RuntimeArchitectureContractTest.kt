package me.awabi2048.mwmchanpon.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

class RuntimeArchitectureContractTest {
    @Test
    fun `Runtimeを迂回するUI実装を増やさない`() {
        val projectRoot = Path.of("").toAbsolutePath().normalize()
        val sourceRoot = projectRoot.resolve("src/main")
        val actual = sortedSetOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in setOf("kt", "java") }
                .forEach { source ->
                    val content = source.readText()
                    val relative = projectRoot.relativize(source).toString().replace('\\', '/')
                    RULES.forEach { (id, pattern) ->
                        val count = pattern.findAll(content).count()
                        if (count > 0) actual += "$id|$count|$relative"
                    }
                }
        }
        val expected = javaClass.getResourceAsStream("/architecture/runtime-ui-legacy-allowlist.txt")
            ?.bufferedReader()
            ?.useLines { it.map(String::trim).filter { line -> line.isNotEmpty() && !line.startsWith("#") }.toSortedSet() }
            ?: error("Runtime移行の一時許可リストがありません")
        assertEquals(
            expected,
            actual,
            "Runtime迂回実装が変化しました。新規追加は禁止です。移行で削減した場合だけ許可リストも同時に減らしてください。",
        )
    }

    private companion object {
        val RULES = mapOf(
            "CREATE_INVENTORY" to Regex("""Bukkit\s*\.\s*createInventory"""),
            "INVENTORY_CLICK_EVENT" to Regex("""InventoryClickEvent"""),
            "DIALOG_CREATE" to Regex("""Dialog\s*\.\s*create"""),
            "CUMULUS_FORM" to Regex("""org\.geysermc\.cumulus|(?:SimpleForm|CustomForm|ModalForm)\s*\.\s*builder"""),
            "MANUAL_CLICK_SOUND" to Regex("""playClickSound|playAdminClickSound"""),
        )
    }
}
