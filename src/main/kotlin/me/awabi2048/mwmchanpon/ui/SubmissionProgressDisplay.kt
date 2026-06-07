package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import org.bukkit.entity.Player
import java.util.UUID

object SubmissionProgressDisplay {
    fun line(player: Player?, storage: ChanponWorldStorage, worldUuid: UUID): String {
        if (!storage.isProductionWorld(worldUuid)) return ""
        val state = runCatching { storage.getSubmissionState(worldUuid) }.getOrNull() ?: SubmissionState.UNSUBMITTED
        val stateName = CCSystem.getAPI().getI18nString(player, "chanpon.submission.progress.${state.i18nKey}")
        return CCSystem.getAPI().getI18nString(
            player,
            "chanpon.submission.progress.line",
            mapOf("state" to stateName)
        )
    }
}
