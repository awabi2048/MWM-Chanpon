package me.awabi2048.mwmchanpon.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorldSettingsViewerPolicyTest {
    @Test
    fun `viewer role prefers owner then moderator then member`() {
        assertEquals(
            WorldSettingsViewerRole.OWNER,
            worldSettingsViewerRole(owner = true, moderator = true, member = true),
        )
        assertEquals(
            WorldSettingsViewerRole.MODERATOR,
            worldSettingsViewerRole(owner = false, moderator = true, member = true),
        )
        assertEquals(
            WorldSettingsViewerRole.MEMBER,
            worldSettingsViewerRole(owner = false, moderator = false, member = true),
        )
    }

    @Test
    fun `outside-world warning takes precedence over role warnings`() {
        assertEquals(
            "common.must_be_in_world_warning",
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MEMBER, outsideTargetWorld = true),
        )
        assertEquals(
            "common.must_be_in_world_warning",
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MODERATOR, outsideTargetWorld = true),
        )
    }

    @Test
    fun `in-world warning identifies the role that cannot change the item`() {
        assertEquals(
            "common.member_cannot_change_warning",
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MEMBER, outsideTargetWorld = false),
        )
        assertEquals(
            "common.moderator_cannot_change_warning",
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MODERATOR, outsideTargetWorld = false),
        )
        assertNull(worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.OWNER, outsideTargetWorld = false))
    }
}
