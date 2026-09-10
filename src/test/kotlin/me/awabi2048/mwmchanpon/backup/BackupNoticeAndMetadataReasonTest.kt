package me.awabi2048.mwmchanpon.backup

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * バックアップ開始通知とロールバックのメタデータ検証に関する回帰テスト。
 *
 * - 手動作成の全経路（オーナー・管理者・復元ポイント）は、非同期処理の投入前に開始通知を送る。
 * - メタデータ検証失敗の英文理由は、表示時に日本語へ変換される。
 */
class BackupNoticeAndMetadataReasonTest {
    @Test
    fun `手動作成の全経路は開始通知を送ってから非同期処理を投入する`() {
        val owner = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
        ).readText()
        val ownerBody = functionBody(owner, "executeOwnerBackup")
        assertTrue("CHANPON_BACKUP_MESSAGE_BACKUP_STARTED" in ownerBody)
        assertTrue(ownerBody.indexOf("BACKUP_STARTED") < ownerBody.indexOf("createOwnerWorldBackup"))

        val admin = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/WorldDataManagementMenu.kt",
        ).readText()
        val adminBody = functionBody(admin, "confirmBackup")
        assertTrue("CHANPON_BACKUP_MESSAGE_BACKUP_STARTED" in adminBody)

        val automation = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/AutomationSettingsMenu.kt",
        ).readText()
        val restorePointBody = functionBody(automation, "confirmRestorePoint")
        assertTrue("CHANPON_BACKUP_MESSAGE_BACKUP_STARTED" in restorePointBody)
        assertTrue(restorePointBody.indexOf("BACKUP_STARTED") < restorePointBody.indexOf("createAllBackup"))
    }

    @Test
    fun `メタデータ検証失敗の理由は日本語へ変換される`() {
        listOf(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/BackupListMenu.kt",
        ).forEach { path ->
            val source = Path.of(path).readText()
            val body = functionBody(source, "restoreReason")
            assertTrue(
                "\"backup world metadata is missing or invalid\"" in body,
                "$path のrestoreReasonにメタデータ失敗の変換が必要です",
            )
            assertTrue(
                "else -> message" in body,
                "$path のrestoreReasonのフォールバックが欠落しています",
            )
        }
    }

    @Test
    fun `メタデータ検証失敗時は原因をサーバーログへ残す`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/backup/BackupService.kt",
        ).readText()
        assertTrue("diagnoseBackupMetadata" in source)
        assertTrue("Rollback preflight: backup world metadata check failed" in source)
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("private fun $name").takeIf { it >= 0 }
            ?: source.indexOf("fun $name").takeIf { it >= 0 }
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
