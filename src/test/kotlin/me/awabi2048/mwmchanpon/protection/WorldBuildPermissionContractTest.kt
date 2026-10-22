package me.awabi2048.mwmchanpon.protection

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 保護モード→WorldGuardフラグ変換の契約テスト。
 *
 * __global__ では passthrough-group が評価されないため、建築可否は
 * build フラグ＋グループで表現します。「誰でも」は明示的な
 * passthrough=ALLOW として書き込み、build はクリアして全員へ開きます。
 */
class WorldBuildPermissionContractTest {
    private val source = Path.of(
        "src/main/kotlin/me/awabi2048/mwmchanpon/protection/WorldBuildPermissionService.kt",
    ).readText()

    @Test
    fun `PUBLICはpassthroughをALLOWとしbuildをクリアする`() {
        val publicBranch = source
            .substringAfter("ChanponWorldProtectionMode.PUBLIC ->")
            .substringBefore("}")

        assertTrue("Flags.PASSTHROUGH, StateFlag.State.ALLOW" in publicBranch)
        assertTrue("Flags.BUILD, null" in publicBranch)
        assertTrue("Flags.BUILD.regionGroupFlag, null" in publicBranch)
        assertFalse("Flags.BUILD, StateFlag.State.DENY" in publicBranch)
    }

    @Test
    fun `DENY側はbuildフラグと拒否対象グループを設定する`() {
        val ownerOnlyBranch = source
            .substringAfter("ChanponWorldProtectionMode.OWNER_ONLY ->")
            .substringBefore("}")
        val membersBranch = source
            .substringAfter("ChanponWorldProtectionMode.MEMBERS ->")
            .substringBefore("}")

        assertTrue("Flags.PASSTHROUGH, StateFlag.State.DENY" in ownerOnlyBranch)
        assertTrue("Flags.BUILD, StateFlag.State.DENY" in ownerOnlyBranch)
        assertTrue("Flags.BUILD.regionGroupFlag, RegionGroup.NON_OWNERS" in ownerOnlyBranch)

        assertTrue("Flags.PASSTHROUGH, StateFlag.State.DENY" in membersBranch)
        assertTrue("Flags.BUILD, StateFlag.State.DENY" in membersBranch)
        assertTrue("Flags.BUILD.regionGroupFlag, RegionGroup.NON_MEMBERS" in membersBranch)
    }

    @Test
    fun `評価されないpassthrough-groupは常にクリアする`() {
        assertTrue("Flags.PASSTHROUGH.regionGroupFlag, null" in source)
        // __global__ で評価されないグループ指定を書き戻さない
        assertFalse("Flags.PASSTHROUGH.regionGroupFlag, RegionGroup" in source)
    }
}
