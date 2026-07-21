package me.awabi2048.mwmchanpon.ui.bedrock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AdminWizardFormTest {
    @Test
    fun `name input is trimmed and bounded`() {
        assertEquals("My World", AdminWizardForm.normalizeName("  My World  "))
        assertNull(AdminWizardForm.normalizeName(" "))
        assertNull(AdminWizardForm.normalizeName("x".repeat(33)))
    }
}
