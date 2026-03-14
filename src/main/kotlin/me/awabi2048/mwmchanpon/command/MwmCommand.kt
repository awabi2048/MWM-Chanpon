package me.awabi2048.mwmchanpon.command

import me.awabi2048.mwmchanpon.service.LocalizationService
import me.awabi2048.mwmchanpon.service.PlatformUiService
import me.awabi2048.mwmchanpon.service.WizardSessionService
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class MwmCommand(
    private val messages: LocalizationService,
    private val sessionService: WizardSessionService,
    private val uiService: PlatformUiService,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(messages.getString(sender as? Player, "messages.usage_create"))
            return true
        }

        if (args[0].equals("create", ignoreCase = true)) {
            if (!sender.hasPermission("mwmchanpon.command.mwm.create")) {
                sender.sendMessage(messages.getString(sender as? Player, "messages.no_permission"))
                return true
            }

            if (sender !is Player) {
                sender.sendMessage(messages.getString(null, "messages.player_only"))
                return true
            }

            if (args.size < 2) {
                sender.sendMessage(messages.getString(sender, "messages.usage_create"))
                return true
            }

            val target = Bukkit.getPlayerExact(args[1])
            if (target == null || !target.isOnline) {
                sender.sendMessage(messages.getString(sender, "messages.target_offline"))
                return true
            }

            sessionService.startSession(sender.uniqueId, target.uniqueId)
            uiService.openMethodSelection(sender)
            sender.sendMessage(messages.getString(sender, "messages.wizard_started", mapOf("player" to target.name)))
            return true
        }

        sender.sendMessage(messages.getString(sender as? Player, "messages.usage_create"))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        return when (args.size) {
            1 -> listOf("create").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("create", ignoreCase = true)) {
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
            } else {
                emptyList()
            }

            else -> emptyList()
        }
    }
}
