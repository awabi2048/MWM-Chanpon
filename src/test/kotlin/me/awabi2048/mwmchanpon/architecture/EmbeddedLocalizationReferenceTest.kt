package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmbeddedLocalizationReferenceTest {
    @Test
    fun `fixed localization references use generated typed keys`() {
        val errors = mutableListOf<String>()
        val fixedCall = Regex(
            "(?:getI18nStringList|getI18nString|getMessageList|getMessage)" +
                "\\([^\\\"\\)]*?\\\"([a-z0-9_]+(?:\\.[a-z0-9_]+)+)\\\"",
        )
        Files.walk(Path.of("src/main/kotlin")).use { files ->
            files.filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .forEach { file ->
                    fixedCall.findAll(Files.readString(file)).forEach { match ->
                        errors += "$file: 固定キーは生成済みLocalizationKeyで参照してください: ${match.groupValues[1]}"
                    }
                }
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `consumer code cannot recreate or pass raw localization keys`() {
        val forbidden = listOf(
            "getI18nString(" to "旧文字列 Text API",
            "getI18nStringList(" to "旧文字列 TextList API",
            "LocalizationKey.text(" to "子システムでのキー生成",
            "LocalizationKey.textList(" to "子システムでのリストキー生成",
        )
        val errors = mutableListOf<String>()
        Files.walk(Path.of("src/main/kotlin")).use { files ->
            files.filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .forEach { file ->
                    val source = Files.readString(file)
                    forbidden.forEach { (token, description) ->
                        if (token in source) errors += "$file: $description を使用しています"
                    }
                    Regex("fun\\s+(?:text|textList|component|i18n|i18nList|i18nComponent|\\w*I18n\\w*)\\s*\\([^)]*key:\\s*String")
                        .findAll(source)
                        .forEach { errors += "$file: 文字列キーを受け取るローカライズ補助関数があります: ${it.value}" }
                }
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }
}
