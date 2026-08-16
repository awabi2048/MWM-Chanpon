package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.generated.ChanponSystemKeys
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
            ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING,
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MEMBER, outsideTargetWorld = true),
        )
        assertEquals(
            ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MUST_BE_IN_WORLD_WARNING,
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MODERATOR, outsideTargetWorld = true),
        )
    }

    @Test
    fun `in-world warning identifies the role that cannot change the item`() {
        assertEquals(
            ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MEMBER_CANNOT_CHANGE_WARNING,
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MEMBER, outsideTargetWorld = false),
        )
        assertEquals(
            ChanponSystemKeys.CHANPON_SETTINGS_COMMON_MODERATOR_CANNOT_CHANGE_WARNING,
            worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.MODERATOR, outsideTargetWorld = false),
        )
        assertNull(worldSettingsRestrictionWarningKey(WorldSettingsViewerRole.OWNER, outsideTargetWorld = false))
    }
}
