package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 実行サーバーのCC-System ABIに対してChanponをビルドする契約を検証します。 */
class GuiItemSpecAbiContractTest {
    private val buildScript = Path.of("build.gradle.kts").readText()

    @Test
    fun `CC-System依存はGuiItemSpecのitemModel追加版へ揃える`() {
        val version = Regex("""com\.awabi2048:CC-System:(\d+)\.(\d+)\.(\d+)""")
            .find(buildScript)
            ?.destructured
            ?.toList()
            ?.map { it.toInt() }
            ?: emptyList()

        // 実サーバーで稼働するCC-SystemのitemModel対応版（26.905.2以降）へ追随します。
        assertTrue(version.isNotEmpty(), "build.gradle.kts にCC-System依存が見つかりません")
        val required = listOf(26, 905, 2)
        val atLeastRequired = version.zip(required)
            .firstOrNull { (actual, req) -> actual != req }
            ?.let { (actual, req) -> actual > req }
            ?: true
        assertTrue(atLeastRequired, "CC-System依存がitemModel対応版（26.905.2以降）より古い: $version")
    }
}
