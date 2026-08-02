package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuiRuntimeContractStartupOrderTest {
    @Test
    fun `GUI runtime contract is checked before every registration`() {
        val source = Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/MWMChanponPlugin.kt").readText()
        val onEnable = source.substringAfter("override fun onEnable() {").substringBefore("private fun verifyGuiRuntimeContract")
        val checkIndex = onEnable.indexOf("if (!verifyGuiRuntimeContract()) return")
        assertTrue(checkIndex >= 0)
        listOf(
            ".register(",
            "registerPlayerWorldRouteCapability",
            "registerWorldCreationGuard",
            "registerWorldPlayerStatePolicy",
        ).forEach { registration ->
            val registrationIndex = onEnable.indexOf(registration)
            assertTrue(registrationIndex < 0 || checkIndex < registrationIndex, "$registration must follow the contract check")
        }
        val prefix = onEnable.substring(0, checkIndex).trim()
        assertTrue(prefix.isEmpty(), "the contract check must be the first onEnable statement")
        assertTrue(source.contains("actualVersion != REQUIRED_GUI_RUNTIME_CONTRACT_VERSION"))
        assertTrue(source.contains("REQUIRED_GUI_RUNTIME_CONTRACT_VERSION = 6"))
        assertTrue(
            source.contains("runCatching { CCSystem.getAPI().guiRuntimeContractVersion }"),
            "NoSuchMethodError等のLinkageErrorも初期化前に捕捉する必要があります",
        )
        assertTrue(source.contains("getOrElse { exception ->"))
        assertTrue(source.contains("logger.severe("))
        assertTrue(source.contains("server.pluginManager.disablePlugin(this)"))
    }
}
