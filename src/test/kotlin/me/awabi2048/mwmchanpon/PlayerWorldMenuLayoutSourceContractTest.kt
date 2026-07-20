package me.awabi2048.mwmchanpon

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerWorldMenuLayoutSourceContractTest {

    @Test
    fun `chanpon menu keeps the historical notification and next page slots`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldMenuProvider.kt")
        )

        assertTrue(source.contains("footerStart + 7, createPendingButton(player)"))
        assertTrue(source.contains("footerStart + 8, createPageItem(player, page + 1, true)"))
        assertTrue(source.contains("ItemTag.TYPE_GUI_PENDING_BUTTON ->"))
        assertTrue(source.contains("mwm.pendingInteractionGui.open("))
    }
}
