package me.awabi2048.mwmchanpon.ui

import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerWorldMoveToTopAvailabilityTest {
    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000003")

    @Test
    fun `already first world cannot expose the move-to-top operation`() {
        assertFalse(isMoveToTopAvailable(viewer, viewer, listOf(first, second), first))
    }

    @Test
    fun `non-first world owned by the viewed player exposes the operation`() {
        assertTrue(isMoveToTopAvailable(viewer, viewer, listOf(first, second), second))
    }

    @Test
    fun `another player's list never exposes the operation`() {
        assertFalse(isMoveToTopAvailable(viewer, UUID.randomUUID(), listOf(first, second), second))
    }

    @Test
    fun `missing world never exposes the operation`() {
        assertFalse(isMoveToTopAvailable(viewer, viewer, listOf(first), second))
    }

    @Test
    fun `render and handler share the guard while non-top action remains reversible`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/me/awabi2048/mwmchanpon/ui/ChanponPlayerWorldCapability.kt"),
        )

        assertEquals(2, Regex("canPrioritizeWorld\\(it\\)|canPrioritizeWorld\\(context\\)").findAll(source).count())
        assertTrue(source.contains("if (!canPrioritizeWorld(context)) return MenuActionResult.Ignored"))
        assertTrue(source.contains("MyWorldManagerApi.prioritizePlayerWorld(player.uniqueId, worldData.uuid)"))
        assertEquals(2, source.split("reversibleContract = MwmReversibleContracts.displayOrder()").size - 1)
        assertEquals(2, source.split("safety = MenuActionSafety.REVERSIBLE").size - 1)
    }
}
