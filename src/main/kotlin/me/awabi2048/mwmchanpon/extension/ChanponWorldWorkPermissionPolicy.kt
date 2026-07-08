package me.awabi2048.mwmchanpon.extension

import java.util.UUID
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.api.extension.WorldWorkPermissionPolicy
import me.awabi2048.myworldmanager.model.WorldData

/** Keeps Chanpon lifecycle state separate from MWM's ownership of LuckPerms assignments. */
class ChanponWorldWorkPermissionPolicy(
    private val storage: ChanponWorldStorage,
    private val autoRollbackLocked: () -> Boolean
) : WorldWorkPermissionPolicy {
    override fun getId(): String = "mwm-chanpon:world-work-permission"

    override fun canAssign(worldData: WorldData, playerUuid: UUID): Boolean {
        if (storage.isImportedArchive(worldData.uuid)) return false
        if (storage.isProductionWorld(worldData.uuid) && autoRollbackLocked()) return false
        if (storage.getProtectionMode(worldData.uuid) == ChanponWorldProtectionMode.OWNER_ONLY &&
            playerUuid != worldData.owner
        ) {
            return false
        }
        return try {
            storage.getSubmissionState(worldData.uuid) == SubmissionState.UNSUBMITTED
        } catch (_: IllegalStateException) {
            true
        }
    }
}
