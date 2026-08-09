package me.awabi2048.mwmchanpon.backup

import java.time.LocalDateTime
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupScopeContractTest {
    @Test
    fun `対象ワールド一覧は専用バックアップだけを受け付ける`() {
        val worldUuid = UUID.randomUUID()
        val dedicated = entry(BackupScope.WORLD, listOf(worldUuid))
        val bulk = entry(BackupScope.ALL, listOf(worldUuid))
        val otherWorld = entry(BackupScope.WORLD, listOf(UUID.randomUUID()))

        assertTrue(dedicated.isDedicatedWorldBackupFor(worldUuid))
        assertFalse(bulk.isDedicatedWorldBackupFor(worldUuid))
        assertFalse(otherWorld.isDedicatedWorldBackupFor(worldUuid))
    }

    private fun entry(scope: BackupScope, worlds: List<UUID>) = BackupEntry(
        id = UUID.randomUUID().toString(),
        scope = scope,
        kind = BackupKind.ADMIN_MANUAL,
        createdAt = LocalDateTime.MIN,
        worldUuids = worlds,
        actorUuid = null,
        actorName = null,
        reason = null,
        name = null,
        sizeBytes = 0L,
    )
}
