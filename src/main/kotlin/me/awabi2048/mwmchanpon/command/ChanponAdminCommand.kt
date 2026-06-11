package me.awabi2048.mwmchanpon.command

import me.awabi2048.mwmchanpon.service.ArchiveService
import me.awabi2048.mwmchanpon.ui.ChanponAdminMenu
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ChanponAdminCommand(
    private val adminMenu: ChanponAdminMenu,
    private val archiveService: ArchiveService
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mwmchanpon.admin")) {
            sender.sendMessage("§c権限がありません。")
            return true
        }
        if (args.isNotEmpty()) {
            return handleSubCommand(sender, args)
        }
        if (sender !is Player) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。")
            return true
        }
        adminMenu.open(sender)
        return true
    }

    private fun handleSubCommand(sender: CommandSender, args: Array<out String>): Boolean {
        return when (args[0].lowercase()) {
            "get_linked_portal" -> handleGetLinkedPortal(sender, args)
            "unzip_archive" -> handleUnzipArchive(sender, args)
            else -> {
                sender.sendMessage("§c不明なサブコマンドです。")
                true
            }
        }
    }

    private fun handleGetLinkedPortal(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。")
            return true
        }
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            sender.sendMessage("§c使用法: /mwmchanpon get_linked_portal <player>")
            return true
        }
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            sender.sendMessage("§c対象プレイヤーがオンラインではありません。")
            return true
        }
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByWorldName(target.world.name)
        if (worldData == null) {
            sender.sendMessage("§c対象プレイヤーはマイワールドにいません。")
            return true
        }
        val portal = MyWorldManagerApi.getWorldService()?.createLinkedWorldPortalItem(sender, worldData.uuid)
        if (portal == null) {
            sender.sendMessage("§cワールドポータルの取得に失敗しました。")
            return true
        }
        sender.inventory.addItem(portal).values.forEach { sender.world.dropItemNaturally(sender.location, it) }
        sender.sendMessage("§aワールドポータルを取得しました。")
        return true
    }

    private fun handleUnzipArchive(sender: CommandSender, args: Array<out String>): Boolean {
        val archiveName = args.getOrNull(1)
        if (archiveName == null) {
            sender.sendMessage("§c使用法: /mwmchanpon unzip_archive <アーカイブ名>")
            return true
        }
        val player = sender as? Player ?: run {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。")
            return true
        }
        archiveService.unzipArchive(archiveName, player).thenAccept { result ->
            Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("MWMChanpon") ?: return@thenAccept,
                Runnable {
                    if (result.success) {
                        sender.sendMessage("§aアーカイブを正常に展開しました: $archiveName")
                    } else {
                        sender.sendMessage("§cアーカイブ展開に失敗しました: ${result.message}")
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
            return listOf("get_linked_portal", "unzip_archive")
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("get_linked_portal", ignoreCase = true)) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
