package me.awabi2048.mwmchanpon.service

import java.util.UUID
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.api.service.ApiMacroService
import me.awabi2048.myworldmanager.model.ManagedDimension
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubmissionCompletionMacroServiceTest {
    @Test
    fun `groups runtime values by completion macro argument type`() {
        val ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val adminUuid = UUID.fromString("00000000-0000-0000-0000-000000000023")

        val arguments = SubmissionCompletionMacroArguments(
            ownerName = "owner-name",
            ownerUuid = ownerUuid,
            adminName = "admin-name",
            adminUuid = adminUuid,
            worldUuid = UUID.fromString("00000000-0000-0000-0000-000000000021"),
            worldName = "sample-world",
            previousState = SubmissionState.UNSUBMITTED,
        )

        assertEquals("owner-name", arguments.toMacroParameters()["owner"])
        assertEquals(ownerUuid.toString(), arguments.toMacroParameters()["owner_uuid"])
        assertEquals("admin-name", arguments.toMacroParameters()["admin"])
        assertEquals(adminUuid.toString(), arguments.toMacroParameters()["admin_uuid"])
        assertEquals("unsubmitted", arguments.toMacroParameters()["previous_state"])
        assertEquals("completed", arguments.toMacroParameters()["state"])
    }

    @Test
    fun `passes completion context to the existing macro service`() {
        val worldUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val adminUuid = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val worldData = WorldData(
            uuid = worldUuid,
            dimension = ManagedDimension.OVERWORLD,
            name = "sample-world",
            description = "",
            icon = Material.GRASS_BLOCK,
            sourceWorld = "source-world",
            expireDate = "",
            owner = ownerUuid,
        )
        var capturedTrigger: String? = null
        var capturedParams: Map<String, String>? = null
        val service = SubmissionCompletionMacroService(
            macroService = ApiMacroService { trigger, params ->
                capturedTrigger = trigger
                capturedParams = params
            },
            logger = Logger.getLogger("SubmissionCompletionMacroServiceTest"),
            ownerNameResolver = { "owner-name" },
        )

        service.execute(worldData, SubmissionState.SUBMITTED, adminUuid, "admin-name")

        assertEquals(SubmissionCompletionMacroService.TRIGGER, capturedTrigger)
        assertEquals(
            mapOf(
                "owner" to "owner-name",
                "owner_uuid" to ownerUuid.toString(),
                "admin" to "admin-name",
                "admin_uuid" to adminUuid.toString(),
                "world_uuid" to worldUuid.toString(),
                "world_name" to "sample-world",
                "previous_state" to "submitted",
                "state" to "completed",
            ),
            capturedParams,
        )
    }

    @Test
    fun `does not propagate macro failures after completion is persisted`() {
        val worldData = WorldData(
            uuid = UUID.fromString("00000000-0000-0000-0000-000000000011"),
            dimension = ManagedDimension.OVERWORLD,
            name = "sample-world",
            description = "",
            icon = Material.GRASS_BLOCK,
            sourceWorld = "source-world",
            expireDate = "",
            owner = UUID.fromString("00000000-0000-0000-0000-000000000012"),
        )
        val service = SubmissionCompletionMacroService(
            macroService = ApiMacroService { _, _ -> error("macro command failed") },
            logger = Logger.getLogger("SubmissionCompletionMacroServiceTest"),
            ownerNameResolver = { "owner-name" },
        )

        assertDoesNotThrow {
            service.execute(
                worldData,
                SubmissionState.UNSUBMITTED,
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                "admin-name",
            )
        }
    }
}
