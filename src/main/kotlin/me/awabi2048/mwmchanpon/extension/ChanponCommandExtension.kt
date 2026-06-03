package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.ui.AdminWizardGui
import me.awabi2048.myworldmanager.api.extension.CommandPolicy
import me.awabi2048.myworldmanager.api.extension.CreateCommandHandler
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class ChanponCommandExtension(
    private val plugin: MWMChanponPlugin,
    private val adminWizardGui: AdminWizardGui
) : CommandPolicy, CreateCommandHandler {
    override fun getId(): String = "mwmchanpon.command"

    override fun canExecuteMwmSubCommand(
        sender: CommandSender,
        subCommand: String,
        args: List<String>
    ): Boolean {
        return when (subCommand.lowercase()) {
            "stats" -> false
            "update-day" -> archiveEnabled()
            "give" -> !isWorldSeedGive(args)
            else -> true
        }
    }

    override fun canSuggestMwmSubCommand(
        sender: CommandSender,
        subCommand: String,
        args: List<String>
    ): Boolean {
        return when (subCommand.lowercase()) {
            "stats" -> false
            "update-day" -> archiveEnabled()
            else -> true
        }
    }

    override fun canSuggestMwmGiveItem(sender: CommandSender, itemId: String): Boolean {
        return !itemId.equals("world_seed", ignoreCase = true)
    }

    override fun handleCreateCommand(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(message(null, "player_only"))
            return true
        }
        if (!sender.hasPermission("mwmchanpon.admin")) {
            sender.sendMessage(message(sender, "no_permission"))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(message(sender, "usage_create"))
            return true
        }

        val bridge = plugin.getCoreApiBridge()
        if (!bridge.isCoreAvailable()) {
            sender.sendMessage(message(sender, "core_unavailable"))
            return true
        }

        val targetPlayerName = args[0]
        val targetUuid = resolvePlayerUuid(targetPlayerName, sender) ?: return true
        val templates = bridge.getTemplateRepository()?.findAll()
        if (templates.isNullOrEmpty()) {
            sender.sendMessage(message(sender, "no_templates"))
            return true
        }

        val session = plugin.wizardSessionService.createSession(sender.uniqueId, targetPlayerName)
        session.targetPlayerUuid = targetUuid
        adminWizardGui.openTemplateSelection(sender, session)
        return true
    }

    override fun tabCompleteCreateCommand(sender: CommandSender, args: List<String>): List<String>? {
        if (args.size != 1 || sender !is Player || !sender.hasPermission("mwmchanpon.admin")) return emptyList()
        return Bukkit.getOnlinePlayers().map { it.name }
    }

    private fun resolvePlayerUuid(name: String, player: Player): java.util.UUID? {
        val target = plugin.server.getPlayer(name)
        if (target != null) return target.uniqueId

        val offline = Bukkit.getOfflinePlayer(name)
        if (!offline.hasPlayedBefore()) {
            player.sendMessage(message(player, "player_not_found", "player" to name))
            return null
        }
        return offline.uniqueId
    }

    private fun message(player: Player?, key: String, vararg placeholders: Pair<String, Any>): String {
        return plugin.getMessage(player, "chanpon.command.$key", *placeholders)
    }

    private fun isWorldSeedGive(args: List<String>): Boolean {
        return args.size >= 3 && args[2].equals("world_seed", ignoreCase = true)
    }

    private fun archiveEnabled(): Boolean = plugin.config.getBoolean("archive.enabled", false)
}
