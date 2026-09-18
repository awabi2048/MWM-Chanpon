package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChanponWorldSettingsReplacementContractTest {
    @Test
    fun `Chanponワールド設定は画面全体の差し替え経路を維持する`() {
        val plugin = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/MWMChanponPlugin.kt",
        ).readText()
        val menu = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponWorldSettingsMenu.kt",
        ).readText()

        assertTrue("registerWorldSettingsRouteCapability(chanponWorldSettingsMenu)" in plugin)
        assertTrue("unregisterWorldSettingsRouteCapability(chanponWorldSettingsMenu)" in plugin)
        assertTrue(") : WorldSettingsRouteCapability" in menu)
        assertTrue("Screen.MAIN -> renderMain(player, worldData)" in menu)
        assertTrue("private fun renderMain(" in menu)
        assertTrue("elements += worldInfoElement(player, worldData)" in menu)
        assertTrue("ChanponWorldSettingsPlacements.EXPANSION_ACTION" in menu)
    }
}
