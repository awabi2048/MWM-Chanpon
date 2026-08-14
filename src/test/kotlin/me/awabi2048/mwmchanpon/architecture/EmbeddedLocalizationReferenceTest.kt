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
}
