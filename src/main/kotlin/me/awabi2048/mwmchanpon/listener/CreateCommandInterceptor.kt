package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.MWMChanponPlugin
import me.awabi2048.mwmchanpon.ui.AdminWizardGui
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent

class CreateCommandInterceptor(
    private val plugin: MWMChanponPlugin,
    private val adminWizardGui: AdminWizardGui
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val message = event.message.trimStart('/')
        val parts = message.split(" ", limit = 3)

        if (parts.size < 3) return
        if (parts[0].substringAfter(':') != "mwm") return
        if (!parts[1].equals("create", ignoreCase = true)) return

        val targetPlayerName = parts[2]
        val player = event.player
        event.isCancelled = true

        if (!player.hasPermission("mwmchanpon.admin")) {
            player.sendMessage("§c権限がありません")
            return
        }

        val bridge = plugin.getCoreApiBridge()
        if (!bridge.isCoreAvailable()) {
            player.sendMessage("§cMyWorldManager が利用できません")
            return
        }

        val targetUuid = resolvePlayerUuid(targetPlayerName, player)
            ?: return

        val session = plugin.wizardSessionService.createSession(player.uniqueId, targetPlayerName)
        session.targetPlayerUuid = targetUuid

        val templates = bridge.getTemplateRepository()?.findAll()
        if (templates.isNullOrEmpty()) {
            player.sendMessage("§cテンプレートがありません")
            return
        }

        adminWizardGui.openTemplateSelection(player, session)
    }

    private fun resolvePlayerUuid(name: String, player: org.bukkit.entity.Player): java.util.UUID? {
        val target = plugin.server.getPlayer(name)
        if (target != null) return target.uniqueId

        val offline = Bukkit.getOfflinePlayer(name)
        if (!offline.hasPlayedBefore()) {
            player.sendMessage("§cプレイヤーが見つかりません: $name")
            return null
        }
        return offline.uniqueId
    }
}
