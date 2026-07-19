package me.awabi2048.mwmchanpon.service

import me.awabi2048.mwmchanpon.storage.ChanponGlobalStorage
import org.bukkit.command.CommandSender

enum class ChanponOperation {
    READ,
    CREATE,
    EDIT,
    WARP,
    EVACUATE,
    DIAGNOSE,
    UNLOAD,
    BACKUP,
    RESTORE,
    ARCHIVE,
    DELETE
}

class ArchiveOperationGuard(
    private val globalStorage: ChanponGlobalStorage
) {
    fun canPerform(sender: CommandSender, operation: ChanponOperation): Boolean {
        if (!globalStorage.isArchiveLocked()) return true
        return when (operation) {
            ChanponOperation.READ -> true
            ChanponOperation.EVACUATE,
            ChanponOperation.DIAGNOSE ->
                sender.hasPermission("myworldmanager.admin") ||
                    sender.hasPermission("mwmchanpon.admin")
            ChanponOperation.RESTORE,
            ChanponOperation.ARCHIVE ->
                sender.hasPermission("mwmchanpon.admin")
            ChanponOperation.CREATE,
            ChanponOperation.EDIT,
            ChanponOperation.WARP,
            ChanponOperation.UNLOAD,
            ChanponOperation.BACKUP,
            ChanponOperation.DELETE -> false
        }
    }
}
