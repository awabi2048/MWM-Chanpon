package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.MenuAcceptedClicks
import com.awabi2048.ccsystem.api.gui.MenuGesture
import java.nio.file.Path
import kotlin.io.path.readText
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MenuSingleActionClickContractTest {
    @Test
    fun `管理単一操作はANYの全クリックを受理して方向に依存しない`() {
        val expectedClicks = setOf(
            ClickType.LEFT,
            ClickType.RIGHT,
            ClickType.SHIFT_LEFT,
            ClickType.SHIFT_RIGHT,
            ClickType.MIDDLE,
        )
        assertEquals(expectedClicks, MenuGesture.ANY.clicks)

        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponAdminMenu.kt",
        ).readText()
        val buildingPermissionItem = functionBody(source, "buildingPermissionItem")
        val worldCreationAvailabilityItem = functionBody(source, "worldCreationAvailabilityItem")
        val toggleBuilding = functionBody(source, "toggleBuilding")
        val cycleWorldCreation = functionBody(source, "cycleWorldCreation")

        assertTrue("MenuGesture.ANY" in buildingPermissionItem)
        assertTrue("chanponCycleAction(" in worldCreationAvailabilityItem)
        assertFalse("context.click" in toggleBuilding)
        assertTrue("setBuildingAllowed" in toggleBuilding)
        assertTrue("GuiCycle.direction(context.click)" in cycleWorldCreation)
    }

    @Test
    fun `バックアップ一覧の復元操作はLEFT契約だけを公開する`() {
        assertEquals(MenuAcceptedClicks.LEFT, MenuGesture.LEFT.clicks)
        assertEquals(
            setOf(ClickType.LEFT, ClickType.SHIFT_LEFT),
            MenuGesture.LEFT.clicks,
        )

        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
        ).readText()
        val backupEntry = functionBody(source, "backupEntry")
        val selectBackup = functionBody(source, "selectBackup")

        assertTrue("MenuGesture.LEFT" in backupEntry)
        assertFalse("MenuGesture.LEFT_RIGHT" in backupEntry)
        assertTrue("!context.click.isLeftClick" in selectBackup)
        assertFalse("isRightClick" in selectBackup)
    }

    @Test
    fun `未使用の先行Lore描画helperを持たない`() {
        listOf(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponAdminMenu.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponAdminWorldListMenu.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/SubmissionAdminMenu.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
        ).forEach { path ->
            val source = Path.of(path).readText()
            assertFalse("getLoreService().render" in source, path)
        }
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("private fun $name").takeIf { it >= 0 }
            ?: error("$name が見つかりません")
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "$name の本体が見つかりません" }
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
        error("$name の本体が閉じられていません")
    }
}
