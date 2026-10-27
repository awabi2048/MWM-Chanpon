package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.LocalPlayer
import com.sk89q.worldguard.protection.RegionResultSet
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.myworldmanager.model.ManagedDimension
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** WorldGuardの実際のフラグ評価で、モード・役割・提出ロックを検証します。 */
class WorldBuildPermissionContractTest {
    private enum class Role { OWNER, MODERATOR, MEMBER, GUEST }

    @Test
    fun allModesMatchWorldGuardBuildDecisionsAndSubmissionLock() {
        val owner = UUID.randomUUID()
        val moderator = UUID.randomUUID()
        val member = UUID.randomUUID()
        val world = WorldData(
            UUID.randomUUID(), ManagedDimension.OVERWORLD, "test", "", Material.STONE, "test", "", owner,
        ).also {
            it.moderators.add(moderator)
            it.members.add(member)
        }
        val players = mapOf(
            Role.OWNER to owner,
            Role.MODERATOR to moderator,
            Role.MEMBER to member,
            Role.GUEST to UUID.randomUUID(),
        )

        for (mode in ChanponWorldProtectionMode.entries) {
            // 旧設定を模した__global__の明示DENYを含め、同期後の実評価を確認します。
            val global = GlobalProtectedRegion("__global__").apply {
                owners.addPlayer(owner)
                members.addPlayer(moderator)
                members.addPlayer(member)
                setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
                setFlag(Flags.PASSTHROUGH.regionGroupFlag, RegionGroup.NON_OWNERS)
                setFlag(Flags.BUILD, StateFlag.State.DENY)
                setFlag(Flags.BUILD.regionGroupFlag, RegionGroup.NON_MEMBERS)
                setFlag(Flags.PVP, StateFlag.State.DENY)
            }
            val access = WorldBuildRegionPolicy.configure(global, world, mode, -64, 320)
            assertNull(global.getFlag(Flags.BUILD))
            assertNull(global.getFlag(Flags.BUILD.regionGroupFlag))
            assertEquals(StateFlag.State.ALLOW, global.getFlag(Flags.PASSTHROUGH))
            assertNull(global.getFlag(Flags.PASSTHROUGH.regionGroupFlag))
            assertEquals(StateFlag.State.DENY, global.getFlag(Flags.PVP))

            if (mode == ChanponWorldProtectionMode.PUBLIC) {
                assertNull(access)
            } else {
                requireNotNull(access)
                assertEquals(WorldBuildRegionPolicy.PRIORITY, access.priority)
                assertEquals(setOf(owner), access.owners.uniqueIds)
                assertEquals(setOf(moderator, member), access.members.uniqueIds)
                assertEquals(StateFlag.State.DENY, access.getFlag(Flags.PASSTHROUGH))
                assertEquals(
                    if (mode == ChanponWorldProtectionMode.OWNER_ONLY) RegionGroup.NON_OWNERS
                    else RegionGroup.NON_MEMBERS,
                    access.getFlag(Flags.BUILD.regionGroupFlag),
                )
            }

            for (locked in listOf(false, true)) {
                val lock = if (locked) ProtectedCuboidRegion(
                    "__submission_lock__",
                    BlockVector3.at(-30_000_000, -64, -30_000_000),
                    BlockVector3.at(30_000_000, 319, 30_000_000),
                ).apply {
                    priority = 1_000_000
                    setFlag(Flags.BUILD, StateFlag.State.DENY)
                } else null
                val regions = buildList<ProtectedRegion> {
                    access?.let { add(it) }
                    lock?.let { add(it) }
                }.toMutableList()
                val result = RegionResultSet(regions, global)
                players.forEach { (role, uuid) ->
                    val expected = !locked && when (mode) {
                        ChanponWorldProtectionMode.OWNER_ONLY -> role == Role.OWNER
                        ChanponWorldProtectionMode.MEMBERS -> role != Role.GUEST
                        ChanponWorldProtectionMode.PUBLIC -> true
                    }
                    assertEquals(expected, result.testState(localPlayer(uuid), Flags.BUILD),
                        "$mode locked=$locked role=$role")
                    // Axiom Paper 6.0.1 asks WorldGuard to test BUILD together with
                    // BLOCK_BREAK/BLOCK_PLACE. Keep that independent integration aligned with
                    // the same world-level policy used by ordinary block edits.
                    assertEquals(expected, axiomWorldGuardBuildAllowed(result, localPlayer(uuid)),
                        "Axiom $mode locked=$locked role=$role")
                    assertFalse(result.testState(localPlayer(uuid), Flags.PVP),
                        "$mode locked=$locked role=$role must retain the unrelated global PVP denial")
                }
            }
        }
    }

    private fun localPlayer(uuid: UUID): LocalPlayer =
        Proxy.newProxyInstance(
            LocalPlayer::class.java.classLoader,
            arrayOf(LocalPlayer::class.java),
        ) { proxy, method, args ->
            if (method.isDefault) {
                InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray()))
            } else {
                when (method.name) {
                    "getUniqueId" -> uuid
                    "getName" -> uuid.toString()
                    "hasGroup", "hasPermission", "isPlayer" -> false
                    "getGroups" -> emptyArray<String>()
                    else -> error("Unexpected WorldGuard LocalPlayer method: $method")
                }
            }
        } as LocalPlayer

    /** Mirrors AxiomPaperPlugin's RegionQuery.testBuild(location, player, breakFlag, placeFlag). */
    private fun axiomWorldGuardBuildAllowed(result: RegionResultSet, player: LocalPlayer): Boolean {
        val build = StateFlag.denyToNone(result.queryState(player, Flags.BUILD))
        val operation = result.queryState(player, Flags.BLOCK_BREAK, Flags.BLOCK_PLACE)
        return StateFlag.test(StateFlag.combine(build, operation))
    }
}
