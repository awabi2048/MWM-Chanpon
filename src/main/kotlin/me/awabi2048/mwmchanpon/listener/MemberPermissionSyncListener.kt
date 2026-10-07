package me.awabi2048.mwmchanpon.listener

import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.mwmchanpon.tool.ToolPermissionController
import me.awabi2048.myworldmanager.api.MyWorldManagerApi
import me.awabi2048.myworldmanager.api.event.MwmMemberAddedEvent
import me.awabi2048.myworldmanager.api.event.MwmMemberRemovedEvent
import me.awabi2048.myworldmanager.api.event.MwmMemberRoleChangedEvent
import me.awabi2048.myworldmanager.api.event.MwmOwnerTransferredEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class MemberPermissionSyncListener(
    private val controller: ToolPermissionController,
    private val storage: ChanponWorldStorage
) : Listener {
    @EventHandler
    fun onMemberAdded(event: MwmMemberAddedEvent) {
        if (storage.isImportedArchive(event.worldUuid)) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(event.worldUuid) ?: return
        controller.syncWorld(worldData)
    }

    @EventHandler
    fun onMemberRemoved(event: MwmMemberRemovedEvent) {
        if (storage.isImportedArchive(event.worldUuid)) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(event.worldUuid) ?: return
        controller.cleanupPlayer(worldData, event.memberUuid)
        controller.syncWorld(worldData)
    }

    @EventHandler
    fun onMemberRoleChanged(event: MwmMemberRoleChangedEvent) {
        if (storage.isImportedArchive(event.worldUuid)) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(event.worldUuid) ?: return
        controller.syncWorld(worldData)
    }

    @EventHandler
    fun onOwnerTransferred(event: MwmOwnerTransferredEvent) {
        if (storage.isImportedArchive(event.worldUuid)) return
        val worldData = MyWorldManagerApi.getWorldRepository()?.findByUuid(event.worldUuid) ?: return
        if (event.oldOwnerUuid != worldData.owner && event.oldOwnerUuid !in worldData.members) {
            controller.cleanupPlayerPermissions(worldData, event.oldOwnerUuid)
        }
        controller.syncWorld(worldData)
    }
}
