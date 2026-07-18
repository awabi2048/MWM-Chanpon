package me.awabi2048.mwmchanpon.command

import com.awabi2048.ccsystem.CCSystem
import me.awabi2048.mwmchanpon.service.ArchiveService
import me.awabi2048.mwmchanpon.ui.ChanponAdminMenu
import me.awabi2048.mwmchanpon.ui.bedrock.AdminWizardForm
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ChanponAdminCommand(
    private val adminMenu: ChanponAdminMenu,
    private val archiveService: ArchiveService,
    private val adminWizardForm: AdminWizardForm
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mwmchanpon.admin")) {
            sender.sendMessage(message(sender, "chanpon.phase.command.no_permission"))
            return true
        }
        if (args.isNotEmpty()) {
            return handleSubCommand(sender, args)
        }
        if (sender !is Player) {
            sender.sendMessage(message(null, "chanpon.phase.command.player_only"))
            return true
        }
        return CCSystem.getAPI().getMenuCommandService().open(
            sender,
            sender,
            "mwm-chanpon:admin",
            emptyMap()
        )
    }

    private fun handleSubCommand(sender: CommandSender, args: Array<out String>): Boolean {
        return when (args[0].lowercase()) {
            "get_linked_portal" -> handleGetLinkedPortal(sender, args)
            "unzip_archive" -> handleUnzipArchive(sender, args)
            "create_world" -> handleCreateWorld(sender, args)
            else -> {
                sender.sendMessage(message(sender, "chanpon.phase.command.unknown_subcommand"))
                true
            }
        }
    }

    private fun handleGetLinkedPortal(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(message(null, "chanpon.phase.command.player_only"))
            return true
        }
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            sender.sendMessage(message(sender, "chanpon.phase.command.usage_get_linked_portal"))
            return true
        }
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            sender.sendMessage(message(sender, "chanpon.phase.command.player_not_found"))
            return true
        }
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByWorldName(target.world.name)
        if (worldData == null) {
            sender.sendMessage(message(sender, "chanpon.phase.command.target_not_in_world"))
            return true
        }
        val portal = MyWorldManagerApi.getWorldService()?.createLinkedWorldPortalItem(sender, worldData.uuid)
        if (portal == null) {
            sender.sendMessage(message(sender, "chanpon.phase.command.portal_failed"))
            return true
        }
        sender.inventory.addItem(portal).values.forEach { sender.world.dropItemNaturally(sender.location, it) }
        sender.sendMessage(message(sender, "chanpon.phase.command.portal_received"))
        return true
    }

    private fun handleUnzipArchive(sender: CommandSender, args: Array<out String>): Boolean {
        val archiveName = args.getOrNull(1)
        if (archiveName == null) {
            sender.sendMessage(message(sender, "chanpon.phase.command.usage_unzip_archive"))
            return true
        }
        val player = sender as? Player ?: run {
            sender.sendMessage(message(null, "chanpon.phase.command.player_only"))
            return true
        }
        archiveService.unzipArchive(archiveName, player).thenAccept { result ->
            Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("MWMChanpon") ?: return@thenAccept,
                Runnable {
                    if (result.success) {
                        sender.sendMessage(message(sender, "chanpon.phase.command.archive_unzipped", "archive" to archiveName))
                    } else {
                        sender.sendMessage(message(sender, "chanpon.phase.command.archive_unzip_failed", "reason" to result.message))
                    }
                }
            )
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission("mwmchanpon.admin")) return emptyList()
        if (args.size == 1) {
            return listOf("get_linked_portal", "unzip_archive", "create_world")
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("get_linked_portal", ignoreCase = true)) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("create_world", ignoreCase = true)) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }

    private fun handleCreateWorld(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(message(null, "chanpon.phase.command.player_only"))
            return true
        }
        val ownerName = args.getOrNull(1)
        if (ownerName == null) {
            sender.sendMessage(message(player, "chanpon.admin_wizard.usage"))
            return true
        }
        val owner = Bukkit.getPlayerExact(ownerName)
        if (owner == null) {
            sender.sendMessage(message(player, "chanpon.phase.command.player_not_found"))
            return true
        }
        adminWizardForm.open(
            player,
            AdminWizardForm.Request(owner.uniqueId, owner.name, adminMenu::open)
        )
        return true
    }

    private fun message(sender: CommandSender?, key: String, vararg placeholders: Pair<String, Any?>) =
        CCSystem.getAPI().getI18nComponent(
            sender as? Player,
            key,
            placeholders.filter { it.second != null }.associate { it.first to it.second!! }
        )
}
