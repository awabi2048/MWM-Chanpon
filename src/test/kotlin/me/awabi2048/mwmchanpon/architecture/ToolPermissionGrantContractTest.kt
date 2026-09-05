package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPermissionGrantContractTest {
    @Test
    fun `explicit Kantan permission nodes follow the same transient lifecycle as tool groups`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/tool/LuckPermsToolPermissionService.kt",
        ).readText()

        assertTrue("PermissionNode" in source)
        assertTrue("activePermissionNode(definition)?.let(user.transientData()::add)" in source)
        assertTrue("activePermissionNode(definition)?.let(user.transientData()::remove)" in source)
        assertTrue("definition.permissionNode" in source)
    }
}
