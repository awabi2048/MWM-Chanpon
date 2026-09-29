package me.awabi2048.mwmchanpon.tour

import com.awabi2048.ccsystem.CCSystem
import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponPreviewTourKeys
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * ツアー参加者向けの状態確認・離脱コマンドです。
 *
 * `/preview-tour` で現在の巡業位置を表示し、`/preview-tour leave` で個人として離脱します。
 */
class PreviewTourCommand(
    private val service: PreviewTourService,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        val sub = args.firstOrNull()?.lowercase()
        when (sub) {
            null -> showStatus(player)
            "leave" -> {
                if (!service.leaveRun(player)) {
                    player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_NOT_IN_TOUR))
                }
            }
            else -> showStatus(player)
        }
        return true
    }

    private fun showStatus(player: Player) {
        val run = service.runOf(player.uniqueId)
        if (run == null) {
            player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_NOT_IN_TOUR))
            return
        }
        val stop = run.tour.stops.getOrNull(run.legIndex)
        val worldName = stop?.let { MyWorldManagerApi.getWorldRepository()?.findByUuid(it.worldUuid)?.name }
            ?: stop?.worldUuid?.toString()
            ?: "-"
        val remaining = ((run.legEndsAtMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt()
        player.sendMessage(
            i18nComponent(
                player,
                ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_STATUS,
                mapOf(
                    "tour" to run.tour.title,
                    "current" to (run.legIndex + 1).toString(),
                    "total" to run.tour.stops.size.toString(),
                    "world" to worldName,
                    "remaining" to PreviewTourService.formatDuration(remaining),
                ),
            ),
        )
        player.sendMessage(i18nComponent(player, ChanponPreviewTourKeys.CHANPON_PREVIEW_TOUR_MESSAGE_LEAVE_HINT))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        if (args.size == 1) listOf("leave").filter { it.startsWith(args[0].lowercase()) } else emptyList()

    private fun i18nComponent(player: Player?, key: LocalizationKey<String>, placeholders: Map<String, Any> = emptyMap()): net.kyori.adventure.text.Component =
        CCSystem.getAPI().getI18nComponent(player, key, placeholders)
}
