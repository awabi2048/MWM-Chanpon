package me.awabi2048.mwmchanpon.ui

import com.awabi2048.ccsystem.api.localization.generated.ChanponSubmissionKeys
import com.awabi2048.ccsystem.api.localization.LocalizationKey

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.gui.GuiLoreLine
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.storage.SubmissionState
import org.bukkit.entity.Player
import java.util.UUID

object SubmissionProgressDisplay {
    fun line(player: Player?, storage: ChanponWorldStorage, worldUuid: UUID): String {
        if (!storage.isProductionWorld(worldUuid)) return ""
        val state = runCatching { storage.getSubmissionState(worldUuid) }.getOrNull() ?: SubmissionState.UNSUBMITTED
        val stateName = CCSystem.getAPI().getLocalized(player, progressKey(state))
        return "§7${CCSystem.getAPI().getLocalized(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_PROGRESS_LABEL)}: §e$stateName"
    }

    fun loreLine(player: Player?, storage: ChanponWorldStorage, worldUuid: UUID): GuiLoreLine? {
        if (!storage.isProductionWorld(worldUuid)) return null
        val state = runCatching { storage.getSubmissionState(worldUuid) }.getOrNull() ?: SubmissionState.UNSUBMITTED
        val stateName = CCSystem.getAPI().getLocalized(player, progressKey(state))
        val label = CCSystem.getAPI().getLocalized(player, ChanponSubmissionKeys.CHANPON_SUBMISSION_PROGRESS_LABEL)
        return GuiLoreLine.Data(label, stateName, "§e")
    }

    private fun progressKey(state: SubmissionState): LocalizationKey<String> = when (state) {
        SubmissionState.UNSUBMITTED -> ChanponSubmissionKeys.CHANPON_SUBMISSION_PROGRESS_UNSUBMITTED
        SubmissionState.SUBMITTED -> ChanponSubmissionKeys.CHANPON_SUBMISSION_PROGRESS_SUBMITTED
        SubmissionState.COMPLETED -> ChanponSubmissionKeys.CHANPON_SUBMISSION_PROGRESS_COMPLETED
    }
}
