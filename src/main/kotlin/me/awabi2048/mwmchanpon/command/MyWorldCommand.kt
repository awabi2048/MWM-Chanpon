package me.awabi2048.mwmchanpon.command

import me.awabi2048.mwmchanpon.service.LocalizationService
import me.awabi2048.mwmchanpon.service.MemberManagementService
import me.awabi2048.mwmchanpon.service.PlatformUiService
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

class MyWorldCommand(
    private val messages: LocalizationService,
    private val uiService: PlatformUiService,
    private val memberManagementService: MemberManagementService,
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mwmchanpon.command.myworld")) {
            sender.sendMessage(messages.getString(sender as? Player, "messages.no_permission"))
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(messages.getString(null, "messages.player_only"))
            return true
        }

        if (args.isNotEmpty()) {
            when (args[0].lowercase()) {
                "accept" -> {
                    val inviteId = if (args.size > 1) parseInviteId(sender, args[1]) ?: return true else null
                    memberManagementService.handleAccept(sender, inviteId)
                        .onFailure { error -> sender.sendMessage(error.message ?: messages.getString(sender, "messages.member_invite_none")) }
                    return true
                }

                "deny" -> {
                    val inviteId = if (args.size > 1) parseInviteId(sender, args[1]) ?: return true else null
                    memberManagementService.handleDeny(sender, inviteId)
                        .onFailure { error -> sender.sendMessage(error.message ?: messages.getString(sender, "messages.member_invite_none")) }
                    return true
                }
            }
        }

        val fromMenuCommand = args.size == 1 && args[0] == "-menu"
        uiService.openMyWorldList(sender, fromMenuCommand = fromMenuCommand)
        return true
    }

    private fun parseInviteId(player: Player, raw: String): UUID? {
        return runCatching { UUID.fromString(raw) }.getOrElse {
            player.sendMessage(messages.getString(player, "messages.member_invite_invalid_id"))
            null
        }
    }
}
