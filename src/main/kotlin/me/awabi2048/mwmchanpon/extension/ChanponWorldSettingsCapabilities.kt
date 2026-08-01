package me.awabi2048.mwmchanpon.extension

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionContext
import com.awabi2048.ccsystem.api.gui.MenuActionSafety
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAction
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionHandler
import com.awabi2048.ccsystem.api.gui.MenuCapabilityActionTextProvider
import com.awabi2048.ccsystem.api.gui.MenuCapabilityTrigger
import com.awabi2048.ccsystem.api.gui.MenuCapabilityAvailability
import com.awabi2048.ccsystem.api.gui.MenuCapabilityContext
import com.awabi2048.ccsystem.api.gui.MenuCapabilityDefinition
import com.awabi2048.ccsystem.api.gui.MenuCapabilityPresentationProvider
import com.awabi2048.ccsystem.api.gui.MenuAcceptedClicks
import me.awabi2048.mwmchanpon.ui.ChanponWorldSettingsMenu
import me.awabi2048.mwmchanpon.ui.WorldSettingsFeature
import me.awabi2048.mwmchanpon.ui.chanponCapabilityAction
import me.awabi2048.mwmchanpon.service.ChanponReversibleContracts
import me.awabi2048.mwmchanpon.service.ChanponReversibleStateProviders
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.extension.WorldSettingsCapabilityPlacements
import me.awabi2048.myworldmanager.model.WorldData

internal class ChanponWorldSettingsCapabilities(
    private val menu: ChanponWorldSettingsMenu,
    storage: ChanponWorldStorage,
) {
    init {
        ChanponReversibleStateProviders.registerPublication(
            CCSystem.getAPI().getMenuReversibleStateProviderRegistry(),
            storage,
        )
        ChanponReversibleStateProviders.registerNotification(
            CCSystem.getAPI().getMenuReversibleStateProviderRegistry(),
        )
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
                presentationProvider = MenuCapabilityPresentationProvider { context ->
                    menu.renderFeature(
                        context.player,
                        requireNotNull(worldData(context)),
                        feature,
                    )
                },
                actions = listOf(
                    chanponCapabilityAction(
                        id = feature.name.lowercase(),
                        trigger = menu.actionTrigger(feature),
                        textProvider = MenuCapabilityActionTextProvider { context ->
                            menu.featureActionText(context.player, feature)
                        },
                        availability = MenuCapabilityAvailability { context ->
                            worldData(context)?.let {
                                menu.isFeatureActionable(context.player, it, feature)
                            } == true
                        },
                        handler = MenuCapabilityActionHandler { context ->
                            menu.handleFeature(
                                context.player,
                                requireNotNull(worldData(context)),
                                feature,
                                context.click,
                            )
                        },
                        safety = featureActionSafety(feature),
                        reversibleContract = featureReversibleContract(feature),
                    ),
                ),
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

    private fun featureActionSafety(feature: WorldSettingsFeature): MenuActionSafety = when (feature) {
        WorldSettingsFeature.EXPANSION -> MenuActionSafety.INPUT_OR_EXTERNAL_SURFACE
        WorldSettingsFeature.PUBLISH, WorldSettingsFeature.NOTIFICATION -> MenuActionSafety.REVERSIBLE
        WorldSettingsFeature.ENVIRONMENT, WorldSettingsFeature.CRITICAL -> MenuActionSafety.NAVIGATION_ONLY
    }

    private fun featureReversibleContract(feature: WorldSettingsFeature) = when (feature) {
        WorldSettingsFeature.PUBLISH -> ChanponReversibleContracts.publication()
        WorldSettingsFeature.NOTIFICATION -> ChanponReversibleContracts.notification()
        else -> null
    }

    companion object {
        private const val OWNER = "mwm-chanpon"
        private const val WORLD_UUID = "world_uuid"
    }
}
