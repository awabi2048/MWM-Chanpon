package me.awabi2048.mwmchanpon

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveOperationGuardSourceContractTest {
    @Test
    fun `archive lock keeps read menus available and blocks mutating operations`() {
        val guard = read("src/main/kotlin/me/awabi2048/mwmchanpon/service/ArchiveOperationGuard.kt")
        val playerMenu = read("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldMenuProvider.kt")
        val access = read("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponWorldAccessPolicy.kt")

        assertTrue(guard.contains("ChanponOperation.READ -> true"))
        assertTrue(guard.contains("ChanponOperation.CREATE,"))
        assertTrue(guard.contains("ChanponOperation.WARP,"))
        assertFalse(playerMenu.contains("globalStorage.isArchiveLocked()"))
        assertFalse(access.contains("canUseMyWorldSystem"))
    }

    @Test
    fun `creation permission uses the world creation capability`() {
        val access = read("src/main/kotlin/me/awabi2048/mwmchanpon/extension/ChanponWorldAccessPolicy.kt")
        assertTrue(access.contains("myworldmanager.world.create"))
        assertFalse(access.contains("myworldmanager.command.mwm.create"))
    }

    private fun read(path: String): String = Files.readString(Path.of(path))
}
