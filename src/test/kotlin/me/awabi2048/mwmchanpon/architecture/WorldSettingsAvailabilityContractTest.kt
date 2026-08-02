package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldSettingsAvailabilityContractTest {
    @Test
    fun `バックアップと本番設定は無効理由とBARRIER表示を共有する`() {
        val backup = source("extension/WorldBackupCapability.kt")
        val production = source("extension/ProductionToggleExtension.kt")

        assertTrue("backupDecision(" in backup)
        assertTrue("Material.BARRIER" in backup)
        assertTrue("reasonedAvailability(" in backup)
        assertTrue("productionDecision(" in production)
        assertTrue("Material.BARRIER" in production)
        assertTrue("reasonedAvailability(" in production)
    }

    @Test
    fun `設定画面の固定配置を維持する`() {
        val menu = source("ui/ChanponWorldSettingsMenu.kt")

        assertTrue("WorldSettingsCapabilityPlacements.FOOTER_ACTIONS, 6" in menu)
        assertTrue("WorldSettingsCapabilityPlacements.FOOTER_RIGHT_ACTIONS, size - 3" in menu)
    }

    @Test
    fun `新規作成Capabilityは並び替え判定を使用しない`() {
        val source = source("ui/ChanponPlayerWorldCapability.kt")
        val creation = source.substringAfter("id = CREATION_CAPABILITY_ID")
            .substringBefore("runtime.register(")

        assertTrue("subject(it)?.targetPlayerUuid == it.player.uniqueId" in creation)
        assertFalse("canPrioritizeWorld(it)" in creation)
    }

    private fun source(relativePath: String): String = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon/$relativePath",
    ).readText()
}
