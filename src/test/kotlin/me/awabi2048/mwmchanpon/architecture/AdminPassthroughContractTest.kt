package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 管理者による権限貫通の回帰テスト。
 *
 * オーナー判定は `AdminAccess` へ集約し、素の権限文字列による個別判定を増やさない。
 * MWM管理系とChanpon管理系のいずれかを満たせば管理者として扱う。
 */
class AdminPassthroughContractTest {
    @Test
    fun `貫通対象の判定はAdminAccessへ集約する`() {
        listOf(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/ToolPermissionCapability.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponMemberManagementPasswordCapability.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ToolPermissionMenu.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponWorldSettingsMenu.kt",
        ).forEach { path ->
            val source = Path.of(path).readText()
            assertTrue("AdminAccess.isOwnerOrAdmin" in source, "$path はAdminAccessへ集約すること")
        }
    }

    @Test
    fun `集約済み箇所に素のMWM管理者判定を残さない`() {
        listOf(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/ToolPermissionCapability.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponMemberManagementPasswordCapability.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ToolPermissionMenu.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponWorldSettingsMenu.kt",
        ).forEach { path ->
            val source = Path.of(path).readText()
            assertFalse("hasPermission(\"myworldmanager.admin\")" in source, "$path に素の判定を残さないこと")
        }
    }

    @Test
    fun `worldmenu経由の管理者作成はADMIN_MANUALでオーナークォータを消費しない`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
        ).readText()
        val body = functionBody(source, "executeOwnerBackup")

        assertTrue("BackupKind.ADMIN_MANUAL" in body)
        assertTrue("createWorldBackup(" in body)
        // 開始通知は分岐前に送る契約を維持する。
        assertTrue(body.indexOf("BACKUP_STARTED") < body.indexOf("createOwnerWorldBackup"))
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
