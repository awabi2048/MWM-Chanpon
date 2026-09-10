package me.awabi2048.mwmchanpon.extension

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * issue #1 の回帰テスト。
 *
 * 手動バックアップ確認画面と復元確認画面では、確認ボタンの action payload が空であり、
 * 実行に必要な値は確認 route の payload に保持される。
 * 確認 route の payload が action payload へ複製されていなくても正常解決できることを保証する。
 */
class WorldBackupConfirmRoutePayloadTest {
    @Test
    fun `手動バックアップ確認は空のaction payloadでもroute payloadから解決できる`() {
        val worldUuid = UUID.randomUUID()
        val routePayload = mapOf(
            WorldBackupCapability.PAYLOAD_WORLD_UUID to worldUuid.toString(),
            WorldBackupCapability.PAYLOAD_PAGE to "2",
            WorldBackupCapability.BACKUP_NAME_KEY to "test-backup",
        )
        // 実際の確認ボタンと同様に action payload は空である。
        val actionPayload = emptyMap<String, String>()

        // 旧実装では actionPayload[backup_name] を参照していたため Rejected になっていた。
        assertNull(actionPayload[WorldBackupCapability.BACKUP_NAME_KEY])

        val confirmation = WorldBackupCapability.resolveOwnerBackupConfirmation(routePayload)
            ?: error("route payloadから解決できる必要があります")
        assertEquals(worldUuid, confirmation.source.worldUuid)
        assertEquals(2, confirmation.source.page)
        assertEquals("test-backup", confirmation.backupName)
    }

    @Test
    fun `復元確認は空のaction payloadでもroute payloadから解決できる`() {
        val worldUuid = UUID.randomUUID()
        val routePayload = mapOf(
            WorldBackupCapability.PAYLOAD_WORLD_UUID to worldUuid.toString(),
            WorldBackupCapability.PAYLOAD_PAGE to "1",
            WorldBackupCapability.BACKUP_ID_KEY to "backup-123",
        )
        val actionPayload = emptyMap<String, String>()

        assertNull(actionPayload[WorldBackupCapability.BACKUP_ID_KEY])

        val confirmation = WorldBackupCapability.resolveRestoreConfirmation(routePayload)
            ?: error("route payloadから解決できる必要があります")
        assertEquals(worldUuid, confirmation.source.worldUuid)
        assertEquals(1, confirmation.source.page)
        assertEquals("backup-123", confirmation.backupId)
    }

    @Test
    fun `確認routeに必須キーがなければ解決失敗する`() {
        val worldUuid = UUID.randomUUID()
        val withoutName = mapOf(
            WorldBackupCapability.PAYLOAD_WORLD_UUID to worldUuid.toString(),
            WorldBackupCapability.PAYLOAD_PAGE to "0",
        )
        assertNull(WorldBackupCapability.resolveOwnerBackupConfirmation(withoutName))

        val withoutId = mapOf(
            WorldBackupCapability.PAYLOAD_WORLD_UUID to worldUuid.toString(),
            WorldBackupCapability.PAYLOAD_PAGE to "0",
        )
        assertNull(WorldBackupCapability.resolveRestoreConfirmation(withoutId))
    }

    @Test
    fun `確認ハンドラーはroute payloadを参照し確認キーをaction payloadから取得しない`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/WorldBackupCapability.kt",
        ).readText()
        val ownerBody = functionBody(source, "confirmOwnerBackup")
        val restoreBody = functionBody(source, "confirmRestore")

        assertTrue("context.route.payload" in ownerBody)
        assertTrue("context.route.payload" in restoreBody)
        assertFalse("context.payload[BACKUP_NAME_KEY]" in ownerBody)
        assertFalse("context.payload[BACKUP_ID_KEY]" in restoreBody)
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
