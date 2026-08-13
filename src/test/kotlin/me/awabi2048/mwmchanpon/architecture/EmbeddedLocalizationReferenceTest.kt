package me.awabi2048.mwmchanpon.architecture

import com.awabi2048.ccsystem.api.localization.LocalizationCatalogContract
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class EmbeddedLocalizationReferenceTest {
    @Test
    fun `リテラル言語キーは埋込カタログに期待型で存在する`() {
        val errors = mutableListOf<String>()
        val call = Regex(
            "(?:getI18nStringList|getI18nString|getMessageList|getMessage)" +
                "\\([^\\\"\\)]*?\\\"([a-z0-9_]+(?:\\.[a-z0-9_]+)+)\\\"",
        )
        Files.walk(Path.of("src/main/kotlin")).use { files ->
            files.filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .forEach { file ->
                    call.findAll(Files.readString(file)).forEach { match ->
                        val invocation = match.value.substringBefore('(')
                        val key = match.groupValues[1]
                        val expected = if (invocation.contains("List")) {
                            LocalizationKey.ValueType.TEXT_LIST
                        } else {
                            LocalizationKey.ValueType.TEXT
                        }
                        if (!LocalizationCatalogContract.contains(key)) {
                            errors += "$file: キーがありません: $key"
                        } else if (LocalizationCatalogContract.valueType(key) != expected) {
                            errors += "$file: 値型が一致しません: key=$key expected=$expected actual=${LocalizationCatalogContract.valueType(key)}"
                        }
                    }
                }
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }
}
