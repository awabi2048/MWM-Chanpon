package me.awabi2048.mwmchanpon.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 実行サーバーのCC-System ABIに対してChanponをビルドする契約を検証します。 */
class GuiItemSpecAbiContractTest {
    private val pom = Path.of("pom.xml").readText()

    @Test
    fun `CC-System依存はGuiItemSpecのitemModel追加版へ揃える`() {
        val ccSystemDependency = pom.substringAfter(
            "<artifactId>CC-System</artifactId>",
            "",
        )

        assertTrue("<version>26.831.7</version>" in ccSystemDependency)
    }
}
