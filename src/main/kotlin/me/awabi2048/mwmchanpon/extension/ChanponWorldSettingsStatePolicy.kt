package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.extension.WorldSettingsRestriction
import me.awabi2048.myworldmanager.api.extension.WorldSettingsStateContext
import me.awabi2048.myworldmanager.api.extension.WorldSettingsStatePolicy

class ChanponWorldSettingsStatePolicy(
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
) : WorldSettingsStatePolicy {
    override fun getId(): String = "mwm-chanpon:world-settings-state"

    override fun restriction(
        context: WorldSettingsStateContext,
    ): WorldSettingsRestriction? = when {
        protectionService.isProtected(context.worldData) ->
            WorldSettingsRestriction.SUBMISSION_LOCKED
        storage.isImportedArchive(context.worldData.uuid) ->
            WorldSettingsRestriction.IMPORTED_ARCHIVE
        else -> null
    }
}
