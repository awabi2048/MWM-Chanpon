package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import me.awabi2048.myworldmanager.api.extension.CommandPolicy
import org.bukkit.command.CommandSender

class ChanponCommandExtension(
    private val plugin: MWMChanponPlugin,
    private val globalStorage: ChanponGlobalStorage
) : CommandPolicy {
    override fun getId(): String = "mwmchanpon.command"

    override fun canExecuteMwmSubCommand(
        sender: CommandSender,
        subCommand: String,
        args: List<String>
    ): Boolean {
        if (isArchiveLockedFor(sender)) return false
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
        if (isArchiveLockedFor(sender)) return false
        return when (subCommand.lowercase()) {
            "stats" -> false
            "update-day" -> archiveEnabled()
            else -> true
        }
    }

    override fun canSuggestMwmGiveItem(sender: CommandSender, itemId: String): Boolean {
        return !itemId.equals("world_seed", ignoreCase = true)
    }

    private fun isWorldSeedGive(args: List<String>): Boolean {
        return args.size >= 3 && args[2].equals("world_seed", ignoreCase = true)
    }

    private fun archiveEnabled(): Boolean = plugin.config.getBoolean("archive.enabled", false)

    private fun isArchiveLockedFor(sender: CommandSender): Boolean =
        globalStorage.isArchiveLocked() && !sender.hasPermission("mwmchanpon.admin")
}
