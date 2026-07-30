package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityItemRenderer
import me.awabi2048.mwmchanpon.ui.ChanponWorldSettingsMenuProvider
import me.awabi2048.mwmchanpon.ui.WorldSettingsFeature
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsCapabilityPlacements
import me.awabi2048.myworldmanager.model.WorldData

internal class ChanponWorldSettingsCapabilities(
    private val menu: ChanponWorldSettingsMenuProvider,
) {
    init {
        register(
            WorldSettingsFeature.EXPANSION,
            WorldSettingsCapabilityPlacements.EXPANSION_ACTION,
        )
        register(
            WorldSettingsFeature.PUBLISH,
            WorldSettingsCapabilityPlacements.PUBLISH_ACTION,
        )
        register(
            WorldSettingsFeature.NOTIFICATION,
            WorldSettingsCapabilityPlacements.NOTIFICATION_ACTION,
        )
        register(
            WorldSettingsFeature.ENVIRONMENT,
            WorldSettingsCapabilityPlacements.ENVIRONMENT_ACTION,
        )
        register(
            WorldSettingsFeature.CRITICAL,
            WorldSettingsCapabilityPlacements.CRITICAL_ACTION,
        )
    }

    private fun register(feature: WorldSettingsFeature, placement: String) {
        CCSystem.getAPI().getMenuCapabilityService().register(
            MenuCapabilityDefinition(
                owner = OWNER,
                id = "world-settings-${feature.name.lowercase()}",
                placement = placement,
                availability = MenuCapabilityAvailability { context ->
                    worldData(context)?.let {
                        menu.isFeatureVisible(context.player, it, feature)
                    } == true
                },
                itemRenderer = MenuCapabilityItemRenderer { context ->
                    menu.renderFeature(
                        context.player,
                        requireNotNull(worldData(context)),
                        feature,
                    )
                },
                actionAvailability = MenuCapabilityAvailability { context ->
                    worldData(context)?.let {
                        menu.isFeatureActionable(context.player, it, feature)
                    } == true
                },
                actionHandler = MenuCapabilityActionHandler { context ->
                    menu.handleFeature(
                        context.player,
                        requireNotNull(worldData(context)),
                        feature,
                        context.click,
                    )
                },
            ),
        )
    }

    private fun worldData(context: MenuCapabilityContext): WorldData? =
        context.arguments[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }

    private fun worldData(context: MenuCapabilityActionContext): WorldData? =
        context.arguments[WORLD_UUID]
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it) }

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val WORLD_UUID = "world_uuid"
    }
}
