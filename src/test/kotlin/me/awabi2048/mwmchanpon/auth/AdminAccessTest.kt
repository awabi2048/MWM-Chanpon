package me.awabi2048.mwmchanpon.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminAccessTest {
    @Test
    fun `いずれかの管理系を満たせば管理者として扱う`() {
        assertTrue(AdminAccess.isAdmin(mwmAdminAccess = true, chanponAdmin = false))
        assertTrue(AdminAccess.isAdmin(mwmAdminAccess = false, chanponAdmin = true))
        assertTrue(AdminAccess.isAdmin(mwmAdminAccess = true, chanponAdmin = true))
        assertFalse(AdminAccess.isAdmin(mwmAdminAccess = false, chanponAdmin = false))
    }

    @Test
    fun `オーナーまたは管理者であれば操作できる`() {
        assertTrue(AdminAccess.isOwnerOrAdmin(isOwner = true, mwmAdminAccess = false, chanponAdmin = false))
        assertTrue(AdminAccess.isOwnerOrAdmin(isOwner = false, mwmAdminAccess = true, chanponAdmin = false))
        assertTrue(AdminAccess.isOwnerOrAdmin(isOwner = false, mwmAdminAccess = false, chanponAdmin = true))
        assertFalse(AdminAccess.isOwnerOrAdmin(isOwner = false, mwmAdminAccess = false, chanponAdmin = false))
    }
}
