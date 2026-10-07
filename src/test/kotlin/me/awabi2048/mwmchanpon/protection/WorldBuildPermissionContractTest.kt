package me.awabi2048.mwmchanpon.protection

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 保護モード→WorldGuardフラグ変換の契約テスト。
 *
 * 「誰でも」は明示的な passthrough=ALLOW として書き込み、未設定値を
 * 保護へ補完する既定適用と混同されない表現を保ちます。
 */
class WorldBuildPermissionContractTest {
    @Test
    fun `PUBLICはpassthroughを明示的にALLOWへ収束する`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/protection/WorldBuildPermissionService.kt",
        ).readText()
        val publicBranch = source
            .substringAfter("ChanponWorldProtectionMode.PUBLIC ->")
            .substringBefore("}")

        assertTrue("Flags.PASSTHROUGH, StateFlag.State.ALLOW" in publicBranch)
        assertTrue("Flags.PASSTHROUGH.regionGroupFlag, RegionGroup.ALL" in publicBranch)
        assertFalse("Flags.PASSTHROUGH, null" in publicBranch)
    }

    @Test
    fun `保護モードはDENY側もグループ指定を維持する`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/protection/WorldBuildPermissionService.kt",
        ).readText()

        assertTrue("RegionGroup.NON_OWNERS" in source)
        assertTrue("RegionGroup.NON_MEMBERS" in source)
        assertTrue("StateFlag.State.DENY" in source)
    }
}
