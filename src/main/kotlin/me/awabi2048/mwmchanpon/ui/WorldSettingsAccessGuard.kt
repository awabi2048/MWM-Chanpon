package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.generated.ChanponSystemKeys
import com.awabi2048.ccsystem.api.localization.generated.CommonKeys
import com.awabi2048.ccsystem.api.localization.generated.MyworldGuiSettingsKeys

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.ManagedMenuInteraction
import com.awabi2048.ccsystem.api.gui.ManagedMenuInteractionOutcome
import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Sound
import org.bukkit.entity.Player

/** Reapplies MWM's setting guards to Chanpon-owned replacement menus and stale inventories. */
internal class WorldSettingsAccessGuard(
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
) {
    fun allow(
        player: Player,
        worldData: WorldData,
        ownerOnly: Boolean,
        requireTargetWorld: Boolean,
        protectedSettings: ProtectedSettingsKind = ProtectedSettingsKind.NONE,
    ): Boolean {
        val admin = MyWorldManagerApi.hasWorldSettingsAdminAccess(player)
        val owner = worldData.owner == player.uniqueId
        val moderator = worldData.moderators.contains(player.uniqueId)
        val authorized = if (ownerOnly) owner || admin else owner || moderator || admin

        if (!authorized || storage.isImportedArchive(worldData.uuid)) {
            player.sendMessage(CCSystem.getAPI().getI18nComponent(player, CommonKeys.GENERAL_NO_PERMISSION))
            denySound(player)
            return false
        }

        // 完成済み/提出済みの保護。提出後は原則として設定変更を認めないが、
        // 公開レベル・訪問通知・ポータル取得の3項目は管理者/所有者が事後調整できるよう例外として許可する。
        if (protectionService.isProtected(worldData) && protectedSettings == ProtectedSettingsKind.NONE) {
            player.sendMessage(
                CCSystem.getAPI().getI18nComponent(player, ChanponSystemKeys.CHANPON_MESSAGE_PROTECTED_WORLD),
            )
            denySound(player)
            return false
        }

        if (requireTargetWorld) {
            if (MyWorldManagerApi.getWorldService()?.isPlayerInWorld(player, worldData) != true) {
                player.sendMessage(
                    CCSystem.getAPI().getI18nComponent(
                        player,
                        MyworldGuiSettingsKeys.GUI_SETTINGS_COMMON_MUST_BE_IN_WORLD,
                    ),
                )
                denySound(player)
                return false
            }
        }
        return true
    }

    /**
     * 完成済み/提出済みでも操作を許可する設定の種別。
     * 許可対象は「公開レベル」「訪問通知」「ポータル取得」の3つ。
     */
    enum class ProtectedSettingsKind {
        NONE,
        PUBLISH,
        NOTIFICATION,
        PORTAL,
    }

    private fun denySound(player: Player) {
        CCSystem.getAPI().getMenuRuntimeService().feedback(
            player,
            ManagedMenuInteraction(ManagedMenuInteractionOutcome.REJECTED),
        )
    }
}
