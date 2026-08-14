package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldSettingsViewerDisplayContractTest {
    @Test
    fun `member and moderator settings remain visible as passive entries`() {
        val menu = source("ui/ChanponWorldSettingsMenu.kt")

        assertTrue("canInspectSettings" in menu)
        assertFalse("mwmCurrentData" in menu)
        assertFalse("environmentCurrentValue" in menu)
        assertFalse("gui.settings.expand.blocks.current_level" in menu)
        assertFalse("environment.current_label" in menu)
        assertFalse("critical.current_label" in menu)
        assertTrue("description = mwmDescription(player, iconKey)" in menu)
        assertTrue("actions = if (outsideTargetWorld || !contract.actionable)" in menu)
        assertTrue("role = if (actionable) GuiElementRole.ACTION else GuiElementRole.CONTENT" in menu)
        assertTrue("isFeatureRoleActionable" in menu)
        assertTrue("existing.filter { it is GuiLoreLine.Data || it is GuiLoreLine.Text }" in menu)
        assertTrue("CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING" in menu)
    }

    @Test
    fun `unavailable capability entries keep only description and restriction warning`() {
        val menu = source("ui/ChanponWorldSettingsMenu.kt")

        assertTrue("if (isFeatureRoleActionable(player, worldData, WorldSettingsFeature.PUBLISH))" in menu)
        assertTrue("if (isFeatureRoleActionable(player, worldData, WorldSettingsFeature.NOTIFICATION))" in menu)
        assertTrue("role = if (isFeatureRoleActionable(player, worldData, WorldSettingsFeature.ENVIRONMENT))" in menu)
        assertTrue("role = if (isFeatureActionable(player, worldData, WorldSettingsFeature.NOTIFICATION))" in menu)
        assertTrue("role = if (isFeatureActionable(player, worldData, WorldSettingsFeature.PUBLISH))" in menu)
    }

    @Test
    fun `viewer restriction warnings use the common warning block`() {
        val menu = source("ui/ChanponWorldSettingsMenu.kt")
        val policy = source("ui/WorldSettingsViewerPolicy.kt")

        assertTrue("worldSettingsRestrictionWarningKey" in menu)
        assertTrue("warnings = warningKey?.let { listOf(i18n(player, it)) } ?: emptyList()" in menu)
        assertTrue("outsideTargetWorld -> ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING" in policy)
        assertTrue("CHANPON_SETTINGS_COMMON_MEMBER_CANNOT_CHANGE_WARNING" in policy)
        assertTrue("CHANPON_SETTINGS_COMMON_MODERATOR_CANNOT_CHANGE_WARNING" in policy)
    }

    private fun source(relativePath: String): String = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon",
    ).resolve(relativePath).readText()
}
