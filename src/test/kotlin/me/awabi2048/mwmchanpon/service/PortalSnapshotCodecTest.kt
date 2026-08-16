package me.awabi2048.mwmchanpon.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PortalSnapshotCodecTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun roundTripsCurrentSnapshot() {
        val worldUuid = UUID.randomUUID()
        val snapshot = ApiPortalSnapshot(
            id = UUID.randomUUID(),
            worldKey = "minecraft:overworld",
            x = 10,
            y = 64,
            z = -4,
            worldUuid = worldUuid,
            targetWorldKey = "minecraft:target",
            showText = true,
            particleColorRgb = 0x00FFFF,
            ownerUuid = UUID.randomUUID(),
            createdAt = "2026-08-12 12:00:00",
            textDisplayUuid = UUID.randomUUID(),
            type = "gate",
            minX = 8,
            minY = 63,
            minZ = -6,
            maxX = 12,
            maxY = 65,
            maxZ = -2,
        )
        val file = tempDir.resolve("portals.yml").toFile()

        PortalSnapshotCodec.write(file, listOf(snapshot))

        assertEquals(
            listOf(snapshot),
            PortalSnapshotCodec.read(file, expectedWorldUuid = worldUuid, expectedWorldKey = "minecraft:overworld"),
        )
    }

    @Test
    fun rejectsMalformedRecordInsteadOfDroppingIt() {
        val id = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val file = tempDir.resolve("portals.yml")
        Files.writeString(
            file,
            """
            portals:
              - id: $id
                world_key: minecraft:overworld
                x: invalid
                y: 64
                z: 0
                particle_color_rgb: 65535
                owner_uuid: $owner
                created_at: '2026-08-12 12:00:00'
            """.trimIndent(),
        )

        assertThrows(RuntimeException::class.java) {
            PortalSnapshotCodec.read(file.toFile())
        }
    }

    @Test
    fun rejectsUnrelatedSnapshotBeforeRestore() {
        val snapshot = ApiPortalSnapshot(
            id = UUID.randomUUID(),
            worldKey = "minecraft:other",
            x = 0,
            y = 64,
            z = 0,
            worldUuid = UUID.randomUUID(),
            targetWorldKey = null,
            showText = true,
            particleColorRgb = 0,
            ownerUuid = UUID.randomUUID(),
            createdAt = "2026-08-12 12:00:00",
            textDisplayUuid = null,
            type = "portal",
            minX = null,
            minY = null,
            minZ = null,
            maxX = null,
            maxY = null,
            maxZ = null,
        )
        val file = tempDir.resolve("portals.yml").toFile()
        PortalSnapshotCodec.write(file, listOf(snapshot))

        assertThrows(RuntimeException::class.java) {
            PortalSnapshotCodec.read(
                file,
                expectedWorldUuid = UUID.randomUUID(),
                expectedWorldKey = "minecraft:target",
            )
        }
    }
}
