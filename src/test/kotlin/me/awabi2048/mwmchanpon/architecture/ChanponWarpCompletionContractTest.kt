package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** プレイヤーワールド一覧が、テレポート受付時に成功案内を出さないことを検証します。 */
class ChanponWarpCompletionContractTest {
    private val source = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldCapability.kt",
    ).readText()

    @Test
    fun `成功メッセージは実テレポート完了コールバック内でだけ送信する`() {
        val warpCall = source.indexOf("teleportToWorldWithCompletion")
        val callback = source.indexOf("Runnable {", warpCall)
        val successMessage = source.indexOf("MESSAGES_WARP_SUCCESS", callback)

        assertTrue(warpCall >= 0)
        assertTrue(callback > warpCall)
        assertTrue(successMessage > callback)
        assertFalse("teleportToWorld(player, worldData.uuid)" in source)
    }
}
