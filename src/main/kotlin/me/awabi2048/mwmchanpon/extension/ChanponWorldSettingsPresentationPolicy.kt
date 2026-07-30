package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.extension.WorldSettingsLayoutMode
import me.awabi2048.myworldmanager.api.extension.WorldSettingsPresentationContext
import me.awabi2048.myworldmanager.api.extension.WorldSettingsPresentationDecision
import me.awabi2048.myworldmanager.api.extension.WorldSettingsPresentationPolicy

class ChanponWorldSettingsPresentationPolicy(
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
) : WorldSettingsPresentationPolicy {
    override fun getId(): String = "mwm-chanpon:world-settings-presentation"

    override fun evaluate(
        context: WorldSettingsPresentationContext,
    ): WorldSettingsPresentationDecision? {
        val imported = storage.isImportedArchive(context.worldData.uuid)
        val protected = protectionService.isProtected(context.worldData)
        val mode = when {
            protected && context.isOwner ->
                WorldSettingsLayoutMode.COMPACT_LIMITED_OWNER
            protected ->
                WorldSettingsLayoutMode.READ_ONLY_COMPACT
            imported && context.isMember ->
                WorldSettingsLayoutMode.READ_ONLY_COMPACT
            imported ->
                WorldSettingsLayoutMode.READ_ONLY_DEFAULT
            else -> return null
        }
        return WorldSettingsPresentationDecision(mode)
    }
}
