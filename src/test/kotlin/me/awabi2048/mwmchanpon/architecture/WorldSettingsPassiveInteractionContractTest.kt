package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldSettingsPassiveInteractionContractTest {
    @Test
    fun `ワールド外の必須設定は操作を持たない`() {
        val source = source("ui/ChanponWorldSettingsMenu.kt")

        assertTrue("actions = if (outsideTargetWorld || !contract.actionable)" in source)
        assertTrue("displayOnlyWhenUnavailable = !isCurrentWorld(player, worldData)" in source)
    }

    @Test
    fun `ポータル取得は明示契約で操作案内だけを省略する`() {
        val source = source("extension/LinkedPortalCapability.kt")

        assertTrue("it.actionGuidance = MenuCapabilityActionGuidance.HIDDEN" in source)
        assertTrue("actions = listOf(" in source)
    }

    private fun source(relativePath: String): String = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon/$relativePath",
    ).readText()
}
