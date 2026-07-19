package me.awabi2048.mwmchanpon

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreationConfirmationOwnershipSourceContractTest {

    @Test
    fun `chanpon owns the complete creation confirmation menu`() {
        val plugin = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/MWMChanponPlugin.kt")
        )
        val provider = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponCreationConfirmationMenuProvider.kt")
        )
        val extension = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ProductionToggleExtension.kt")
        )
        val playerMenu = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldMenuProvider.kt")
        )
        val easyCreation = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/service/EasyVoidWorldCreationService.kt")
        )

        assertTrue(plugin.contains("registerCreationConfirmationMenuProvider(creationConfirmationMenuProvider)"))
        assertTrue(plugin.contains("unregisterCreationConfirmationMenuProvider(creationConfirmationMenuProvider)"))
        assertTrue(provider.contains("inventory.setItem(39, dimensionItem(player, session))"))
        assertTrue(provider.contains("inventory.setItem(40, productionToggle.createCreationItem("))
        assertTrue(provider.contains("inventory.setItem(41, spawnItem(player, session))"))
        assertFalse(provider.contains("isWorldPointEconomyEnabled"))
        assertFalse(extension.contains("\"creation_confirm\""))
        assertFalse(extension.contains("CreationConfirmationLayout"))
        assertTrue(playerMenu.contains("session.billingMode = WorldPointBillingMode.NONE"))
        assertTrue(easyCreation.contains("billingMode = WorldPointBillingMode.NONE"))
    }
}
