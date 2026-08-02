package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.gui.MenuAcceptedClicks
import com.awabi2048.ccsystem.api.gui.MenuCapabilitySource
import com.awabi2048.ccsystem.api.gui.MenuInteraction
import com.awabi2048.ccsystem.api.gui.MenuRuntimeInspectionInteractionSnapshot
import com.awabi2048.ccsystem.api.gui.MenuRuntimeInteractionKind
import java.util.UUID
import me.awabi2048.myworldmanager.api.extension.WorldSettingsCapabilityPlacements
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorldSettingsDisabledCapabilityInspectionContractTest {
    @Test
    fun `reasoned disabled world settings source reaches contract v4 inspection`() {
        val worldUuid = UUID.randomUUID().toString()
        val unavailable = MenuInteraction.Unavailable(
            acceptedClicks = MenuAcceptedClicks.STANDARD,
            message = Component.text("disabled"),
        )
        val source = MenuCapabilitySource(
            capabilityId = "mwm-chanpon:world-settings-environment",
            placement = WorldSettingsCapabilityPlacements.ENVIRONMENT_ACTION,
            arguments = mapOf("world_uuid" to worldUuid),
            attributes = emptyMap(),
        )
        attachSourceAsCcSystemDoes(unavailable, source)

        val snapshot = inspectAsCcSystemDoes(unavailable)

        assertEquals(MenuRuntimeInteractionKind.UNAVAILABLE, snapshot.kind)
        assertEquals(source.capabilityId, snapshot.capabilityId)
        assertEquals(source.placement, snapshot.sourcePlacement)
        assertEquals(source.arguments, snapshot.arguments)
        assertEquals(MenuAcceptedClicks.STANDARD, snapshot.acceptedClicks)
        assertNull(snapshot.actionId)
        assertEquals(emptyMap<String, Any?>(), snapshot.attributes)
        assertEquals(emptyMap<org.bukkit.event.inventory.ClickType, Any>(), snapshot.reversibleContractsByClick)
    }

    private fun attachSourceAsCcSystemDoes(
        unavailable: MenuInteraction.Unavailable,
        source: MenuCapabilitySource,
    ) {
        val setter = unavailable.javaClass.methods.single {
            it.name.startsWith("setSourceCapability\$") && it.parameterTypes.contentEquals(arrayOf(MenuCapabilitySource::class.java))
        }
        setter.invoke(unavailable, source)
    }

    private fun inspectAsCcSystemDoes(
        unavailable: MenuInteraction.Unavailable,
    ): MenuRuntimeInspectionInteractionSnapshot {
        val factoryClass = Class.forName(
            "com.awabi2048.ccsystem.core.gui.MenuRuntimeInspectionInteractionSnapshotFactory",
        )
        val instance = factoryClass.getField("INSTANCE").get(null)
        val create = factoryClass.methods.single {
            it.name == "create" && it.parameterTypes.contentEquals(arrayOf(MenuInteraction::class.java))
        }
        return create.invoke(instance, unavailable) as MenuRuntimeInspectionInteractionSnapshot
    }
}
