package me.awabi2048.mwmchanpon.command

import me.awabi2048.mwmchanpon.service.LocalizationService
import me.awabi2048.mwmchanpon.service.PlatformUiService
import me.awabi2048.mwmchanpon.service.WorldService
import me.awabi2048.mwmchanpon.ui.WorldSettingsBackTarget
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class WorldMenuCommand(
    private val messages: LocalizationService,
    private val worldService: WorldService,
    private val uiService: PlatformUiService,
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mwmchanpon.command.worldmenu")) {
            sender.sendMessage(messages.getString(sender as? Player, "messages.no_permission"))
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(messages.getString(null, "messages.player_only"))
            return true
        }

        val worldData = worldService.getWorldByCustomWorldName(sender.world.name)
        if (worldData == null) {
            sender.sendMessage(messages.getString(sender, "messages.worldmenu_not_myworld"))
            return true
        }

        if (!worldService.canOpenSettings(sender, worldData)) {
            sender.sendMessage(messages.getString(sender, "messages.worldmenu_no_access"))
            return true
        }

        val fromMenuCommand = args.size == 1 && args[0] == "-menu"
        uiService.openWorldSettings(
            sender,
            worldData,
            if (fromMenuCommand) WorldSettingsBackTarget.MENU_COMMAND else WorldSettingsBackTarget.NONE,
        )
        return true
    }
}
