package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CycleDirectionContractTest {
    @Test
    fun `公開レベルは左右クリックを別方向として処理する`() {
        val menu = source("ui/ChanponWorldSettingsMenu.kt")
        val capabilities = source("extension/ChanponWorldSettingsCapabilities.kt")

        assertTrue("val direction = GuiCycle.direction(click)" in menu)
        assertTrue("trigger = MenuCapabilityTrigger.LEFT_RIGHT" in capabilities)
        assertTrue("id = \"publish-cycle\"" in capabilities)
    }

    @Test
    fun `循環リストは共通の左右方向アクションを使用する`() {
        listOf(
            "ui/ChanponAdminWorldListMenu.kt",
            "ui/SubmissionAdminMenu.kt",
            "ui/ChanponEnvironmentGui.kt",
        ).forEach { path ->
            assertTrue("chanponCycleAction(" in source(path), path)
        }
        assertTrue("GuiMenuActionIntent.PlainLeftRight" in source("ui/ChanponMenuActionSafety.kt"))
    }

    private fun source(relativePath: String): String = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon/$relativePath",
    ).readText()
}
