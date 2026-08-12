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
    private var configurationAvailable: () -> Boolean = { true }

    fun setConfigurationAvailability(gate: () -> Boolean) {
        configurationAvailable = gate
    }

    fun canPerform(sender: CommandSender, operation: ChanponOperation): Boolean {
        if (!configurationAvailable() &&
            operation != ChanponOperation.READ &&
            operation != ChanponOperation.DIAGNOSE
        ) {
            return false
        }
        return ArchiveOperationPolicy.canPerform(
            globalStorage.isArchiveLocked(),
            operation,
            sender.hasPermission("myworldmanager.admin"),
            sender.hasPermission("mwmchanpon.admin")
        )
    }
}

internal object ArchiveOperationPolicy {
    fun canPerform(
        archiveLocked: Boolean,
        operation: ChanponOperation,
        coreAdmin: Boolean,
        chanponAdmin: Boolean
    ): Boolean {
        if (!archiveLocked) return true
        return when (operation) {
            ChanponOperation.READ -> true
            ChanponOperation.EVACUATE,
            ChanponOperation.DIAGNOSE -> coreAdmin || chanponAdmin
            ChanponOperation.RESTORE,
            ChanponOperation.ARCHIVE -> chanponAdmin
            ChanponOperation.CREATE,
            ChanponOperation.EDIT,
            ChanponOperation.WARP,
            ChanponOperation.UNLOAD,
            ChanponOperation.BACKUP,
            ChanponOperation.DELETE -> false
        }
    }
}
