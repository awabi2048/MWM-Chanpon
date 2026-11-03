package me.awabi2048.mwmchanpon.storage

import java.nio.file.Files
import java.util.UUID
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubmissionWithdrawalTest {
    @Test
    fun `提出と取り下げを繰り返して公開範囲と履歴を保持する`() {
        val folder = Files.createTempDirectory("submission-withdrawal").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val world = UUID.randomUUID()
            val actor = UUID.randomUUID()
            storage.setProductionWorld(world, true)
            storage.setVisibility(world, ChanponWorldVisibility.DEVELOPMENT)
            assertTrue(storage.submitWorld(world, actor, "owner"))
            assertEquals(ChanponWorldVisibility.PUBLIC, storage.getVisibility(world))
            assertTrue(storage.withdrawWorld(world, actor, "owner"))
            assertEquals(ChanponWorldVisibility.DEVELOPMENT, storage.getVisibility(world))
            assertTrue(storage.submitWorld(world, actor, "owner"))
            storage.setVisibility(world, ChanponWorldVisibility.PRIVATE)
            assertTrue(storage.withdrawWorld(world, actor, "admin"))
            assertEquals(ChanponWorldVisibility.PRIVATE, storage.getVisibility(world))
            val config = YamlConfiguration.loadConfiguration(storage.worldFile(world))
            assertEquals(listOf("submit", "withdraw", "submit", "withdraw"), config.getMapList("submission.history").map { it["action"] })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `提出前の記録がない場合は非公開へ戻し完成済みは変更しない`() {
        val folder = Files.createTempDirectory("submission-existing").toFile()
        try {
            val storage = ChanponWorldStorage(folder)
            val world = UUID.randomUUID()
            val actor = UUID.randomUUID()
            storage.setProductionWorld(world, true)
            storage.setVisibility(world, ChanponWorldVisibility.PUBLIC)
            storage.setSubmissionState(world, SubmissionState.SUBMITTED, actor, "owner", "submit")
            assertTrue(storage.withdrawWorld(world, actor, "owner"))
            assertEquals(ChanponWorldVisibility.PRIVATE, storage.getVisibility(world))
            storage.setSubmissionState(world, SubmissionState.COMPLETED, actor, "admin", "mark_completed")
            val before = storage.worldFile(world).readBytes()
            assertFalse(storage.withdrawWorld(world, actor, "admin"))
            assertArrayEquals(before, storage.worldFile(world).readBytes())
        } finally {
            folder.deleteRecursively()
        }
    }
}
