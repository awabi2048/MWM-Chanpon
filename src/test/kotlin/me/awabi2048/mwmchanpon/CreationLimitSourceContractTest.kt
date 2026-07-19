package me.awabi2048.mwmchanpon

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreationLimitSourceContractTest {

    @Test
    fun `normal and easy creation share the chanpon limit and self permission`() {
        val config = Files.readString(Path.of("src/main/resources/config.yml"))
        val guard = Files.readString(Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponWorldCreationGuard.kt"
        ))
        val playerMenu = Files.readString(Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldMenuProvider.kt"
        ))
        val easyCreation = Files.readString(Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/service/EasyVoidWorldCreationService.kt"
        ))

        assertTrue(config.contains("max_worlds_per_player: 50"))
        assertTrue(guard.contains("creation.max_worlds_per_player"))
        assertTrue(playerMenu.contains("myworldmanager.world.create"))
        assertTrue(easyCreation.contains("myworldmanager.world.create"))
        assertTrue(easyCreation.contains("createManagedWorld("))
        assertFalse(easyCreation.contains("Bukkit.createWorld"))
        assertFalse(easyCreation.contains("worldConfigRepository.save"))
        assertFalse(easyCreation.contains("MwmWorldCreatedEvent("))
        assertFalse(playerMenu.contains("player.hasPermission(\"myworldmanager.command.mwm.create\")"))
    }
}
