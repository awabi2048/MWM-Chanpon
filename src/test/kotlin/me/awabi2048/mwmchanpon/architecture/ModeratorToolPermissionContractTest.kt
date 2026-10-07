package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * モデレータによるメンバーツール権限の管理に関する契約テスト。
 *
 * MWM側のメンバー管理権限（オーナー・モデレータ・管理者）と同じ境界を
 * capabilityの到達可否とメニューの実行時判定の両方へ適用し、
 * 役割変更だけをオーナー・管理者へ限定する。
 */
class ModeratorToolPermissionContractTest {
    @Test
    fun `ツール権限capabilityはモデレータへも開放する`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/ToolPermissionCapability.kt",
        ).readText()

        assertTrue("player.uniqueId in worldData.moderators" in expressionDecl(source, "canManage"))
    }

    @Test
    fun `メニューのツール切替はモデレータを許可し役割変更は許可しない`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ToolPermissionMenu.kt",
        ).readText()

        assertTrue("player.uniqueId in worldData.moderators" in expressionDecl(source, "canManage("))
        assertFalse("worldData.moderators" in expressionDecl(source, "canManageRoles"))
        assertTrue("canManageRoles(context.player, worldData)" in functionBody(source, "toggleRole"))
        assertFalse("canManage(context.player, worldData)" in functionBody(source, "toggleRole"))
    }

    @Test
    fun `役割変更はMWMの単一経路を使う`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ToolPermissionMenu.kt",
        ).readText()
        val body = functionBody(source, "toggleRole")

        assertTrue("MyWorldManagerApi.toggleMemberRole" in body)
        assertFalse("worldData.moderators.remove" in body)
        assertFalse("worldData.moderators.add" in body)
        assertFalse("worldData.members.remove" in body)
        assertFalse("worldData.members.add" in body)
    }

    @Test
    fun `役割切替枠は操作権限がない利用者へ表示しない`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ToolPermissionMenu.kt",
        ).readText()

        assertTrue("canManageRoles(player, worldData)" in functionBody(source, "render"))
    }

    /** `fun name(...)`宣言から十分な長さを切り出します。式本体関数の検査向けです。 */
    private fun expressionDecl(source: String, name: String): String =
        source.substringAfter("fun $name").take(500)

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name").takeIf { it >= 0 }
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
