package me.awabi2048.mwmchanpon.protection

import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyWorldSafetyDefaultsPolicyTest {
    @Test
    fun `specified game rules converge to the Chanpon defaults`() {
        val values = MyWorldSafetyDefaultsPolicy.gameRuleAssignments
            .associate { it.key to it.value }

        assertEquals("true", values["keepInventory"])
        assertEquals("0", values["randomTickSpeed"])
        assertEquals("false", values["doVinesSpread"])
        assertEquals("false", values["tntExplodes"])
    }

    @Test
    fun `WorldGuard defaults fill only missing flags and preserve existing region state`() {
        val region = GlobalProtectedRegion("__global__")
        val owner = UUID.randomUUID()
        val member = UUID.randomUUID()
        region.owners.addPlayer(owner)
        region.members.addPlayer(member)
        region.setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY)
        region.setFlag(Flags.PASSTHROUGH.regionGroupFlag, RegionGroup.NON_MEMBERS)
        region.setFlag(Flags.TNT, StateFlag.State.ALLOW)
        region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY)

        assertTrue(MyWorldSafetyDefaultsPolicy.applyWorldGuardDefaults(region))
        assertEquals(StateFlag.State.ALLOW, region.getFlag(Flags.TNT))
        assertEquals(StateFlag.State.DENY, region.getFlag(Flags.OTHER_EXPLOSION))
        assertEquals(StateFlag.State.DENY, region.getFlag(Flags.CREEPER_EXPLOSION))
        assertEquals(StateFlag.State.DENY, region.getFlag(Flags.CORAL_FADE))
        assertEquals(StateFlag.State.DENY, region.getFlag(Flags.VINE_GROWTH))
        assertEquals(StateFlag.State.DENY, region.getFlag(Flags.PASSTHROUGH))
        assertEquals(RegionGroup.NON_MEMBERS, region.getFlag(Flags.PASSTHROUGH.regionGroupFlag))
        assertTrue(region.owners.contains(owner))
        assertTrue(region.members.contains(member))

        assertFalse(MyWorldSafetyDefaultsPolicy.applyWorldGuardDefaults(region))
    }
}
