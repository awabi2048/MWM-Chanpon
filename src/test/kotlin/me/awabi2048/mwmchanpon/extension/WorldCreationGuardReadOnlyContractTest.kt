package me.awabi2048.mwmchanpon.extension

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldCreationGuardReadOnlyContractTest {
    @Test
    fun `registered creation guard remains safe for gui inspection`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponWorldCreationGuard.kt",
        ).readText()
        val body = functionBody(source, "evaluate")

        assertTrue("getWorldCreationAvailability()" in body)
        listOf(
            "sendMessage(",
            "runTask",
            "callEvent(",
            ".save(",
            ".set",
            "dispatchCommand",
            "runtime.",
            "teleport",
        ).forEach { sideEffect ->
            assertFalse(sideEffect in body, "WorldCreationGuard.evaluate must stay read-only: $sideEffect")
        }
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name")
        require(start >= 0) { "function not found: $name" }
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "body not found: $name" }
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
        error("body is not closed: $name")
    }
}
