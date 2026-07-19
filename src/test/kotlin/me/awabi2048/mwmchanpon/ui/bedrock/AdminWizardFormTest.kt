package me.awabi2048.mwmchanpon.ui.bedrock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AdminWizardFormTest {
    @Test
    fun `name input is trimmed and bounded`() {
        assertEquals("My World", AdminWizardForm.normalizeName("  My World  "))
        assertNull(AdminWizardForm.normalizeName(" "))
        assertNull(AdminWizardForm.normalizeName("x".repeat(33)))
    }

    @Test
    fun `template detail validates and previews through the core api`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/bedrock/AdminWizardForm.kt")
        )

        assertTrue(source.contains("repository.isUsable(template.id)"))
        assertTrue(source.contains("service.previewTemplate(player, template.id)"))
        assertTrue(source.contains("service.createFromTemplate(template.id"))
    }
}
