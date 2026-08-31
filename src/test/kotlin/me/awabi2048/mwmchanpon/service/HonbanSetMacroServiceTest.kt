package me.awabi2048.mwmchanpon.service

import java.util.UUID
import java.util.logging.Logger
import me.awabi2048.myworldmanager.api.service.ApiMacroService
import me.awabi2048.myworldmanager.model.ManagedDimension
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HonbanSetMacroServiceTest {
    @Test
    fun `groups runtime values by honban set macro argument type`() {
        val ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000023")
        val worldUuid = UUID.fromString("00000000-0000-0000-0000-000000000021")

        val arguments = HonbanSetMacroArguments(
            ownerName = "owner-name",
            ownerUuid = ownerUuid,
            actorName = "actor-name",
            actorUuid = actorUuid,
            worldUuid = worldUuid,
            worldName = "sample-world",
        )

        assertEquals(
            mapOf(
                "owner" to "owner-name",
                "owner_uuid" to ownerUuid.toString(),
                "actor" to "actor-name",
                "actor_uuid" to actorUuid.toString(),
                "world_uuid" to worldUuid.toString(),
                "world_name" to "sample-world",
            ),
            arguments.toMacroParameters(),
        )
    }

    @Test
    fun `passes existing world context to the existing macro service`() {
        val worldData = worldData()
        val actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000003")
        var capturedTrigger: String? = null
        var capturedParams: Map<String, String>? = null
        val service = HonbanSetMacroService(
            macroService = ApiMacroService { trigger, params ->
                capturedTrigger = trigger
                capturedParams = params
            },
            logger = Logger.getLogger("HonbanSetMacroServiceTest"),
            ownerNameResolver = { "owner-name" },
        )

        service.execute(worldData, actorUuid, "actor-name")

        assertEquals(HonbanSetMacroService.TRIGGER, capturedTrigger)
        assertEquals(
            mapOf(
                "owner" to "owner-name",
                "owner_uuid" to worldData.owner.toString(),
                "actor" to "actor-name",
                "actor_uuid" to actorUuid.toString(),
                "world_uuid" to worldData.uuid.toString(),
                "world_name" to worldData.name,
            ),
            capturedParams,
        )
    }

    @Test
    fun `does not propagate macro failures after production state is persisted`() {
        val service = HonbanSetMacroService(
            macroService = ApiMacroService { _, _ -> error("macro command failed") },
            logger = Logger.getLogger("HonbanSetMacroServiceTest"),
            ownerNameResolver = { "owner-name" },
        )

        assertDoesNotThrow {
            service.execute(
                worldData(),
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                "actor-name",
            )
        }
    }

    private fun worldData(): WorldData = WorldData(
        uuid = UUID.fromString("00000000-0000-0000-0000-000000000011"),
        dimension = ManagedDimension.OVERWORLD,
        name = "sample-world",
        description = "",
        icon = Material.GRASS_BLOCK,
        sourceWorld = "source-world",
        expireDate = "",
        owner = UUID.fromString("00000000-0000-0000-0000-000000000012"),
    )
}
