package me.awabi2048.mwmchanpon.ui

import me.awabi2048.mwmchanpon.protection.SubmittedWorldProtectionService
import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.MyWorldManager
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.model.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/** Reapplies MWM's setting guards to Chanpon-owned replacement menus and stale inventories. */
internal class WorldSettingsAccessGuard(
    private val storage: ChanponWorldStorage,
    private val protectionService: SubmittedWorldProtectionService,
) {
    fun allow(
        player: Player,
        worldData: WorldData,
        ownerOnly: Boolean,
        requireTargetWorld: Boolean,
    ): Boolean {
        val mwm = JavaPlugin.getPlugin(MyWorldManager::class.java)
        val sessionAdmin = mwm.settingsSessionManager.getSession(player)?.isAdminFlow == true
        val admin = sessionAdmin || player.hasPermission("myworldmanager.admin")
        val owner = worldData.owner == player.uniqueId
        val moderator = worldData.moderators.contains(player.uniqueId)
        val authorized = if (ownerOnly) owner || admin else owner || moderator || admin

        if (!authorized || storage.isImportedArchive(worldData.uuid) || protectionService.isProtected(worldData)) {
            player.sendMessage(Component.text(mwm.languageManager.getMessage(player, "general.no_permission")))
            denySound(player)
            return false
        }

        if (requireTargetWorld) {
            val targetWorld = MyWorldManagerApi.getWorldService()?.getWorldFolderName(worldData)
                ?: worldData.customWorldName
                ?: "my_world.${worldData.uuid}"
            if (player.world.name != targetWorld) {
                player.sendMessage(
                    Component.text(mwm.languageManager.getMessage(player, "gui.settings.common.must_be_in_world"))
                )
                denySound(player)
                return false
            }
        }
        return true
    }

    private fun denySound(player: Player) {
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
    }
}
