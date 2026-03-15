package me.awabi2048.mwmchanpon.command

import me.awabi2048.mwmchanpon.service.LocalizationService
import me.awabi2048.mwmchanpon.service.PlatformUiService
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class MyWorldCommand(
    private val messages: LocalizationService,
    private val uiService: PlatformUiService,
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

        val fromMenuCommand = args.size == 1 && args[0] == "-menu"
        uiService.openMyWorldList(sender, fromMenuCommand = fromMenuCommand)
        return true
    }
}
