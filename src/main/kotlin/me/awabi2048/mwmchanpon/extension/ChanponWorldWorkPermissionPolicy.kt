package me.awabi2048.mwmchanpon.extension

import java.util.UUID
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.ChanponWorldProtectionMode
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.api.extension.WorldWorkPermissionPolicy
import me.awabi2048.myworldmanager.model.WorldData

/** Keeps Chanpon lifecycle state separate from MWM's ownership of LuckPerms assignments. */
class ChanponWorldWorkPermissionPolicy(
    private val storage: ChanponWorldStorage,
    private val globalStorage: ChanponGlobalStorage,
    private val autoRollbackLocked: () -> Boolean,
    private val protectionMode: (WorldData) -> ChanponWorldProtectionMode,
) : WorldWorkPermissionPolicy {
    private var configurationAvailable: () -> Boolean = { true }

    fun setConfigurationAvailability(gate: () -> Boolean) {
        configurationAvailable = gate
    }
    override fun getId(): String = "mwm-chanpon:world-work-permission"

    override fun canAssign(worldData: WorldData, playerUuid: UUID): Boolean {
        if (!configurationAvailable()) return false
        if (!storage.hasCurrentWorldData(worldData.uuid)) return false
        // 全体の建築許可は作業グループ付与の可否としても扱います。
        // 迂回するのはグローバル設定だけであり、下記の個別判定は残ります。
        if (!globalStorage.isBuildingAllowed() &&
            !storage.isGlobalBypassAllowed(worldData.uuid, playerUuid)
        ) {
            return false
        }
        if (storage.isImportedArchive(worldData.uuid)) return false
        if (storage.isProductionWorld(worldData.uuid) && autoRollbackLocked()) return false
        if (protectionMode(worldData) == ChanponWorldProtectionMode.OWNER_ONLY &&
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
