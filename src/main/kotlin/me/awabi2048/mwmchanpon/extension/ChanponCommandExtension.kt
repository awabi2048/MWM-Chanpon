package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.service.ArchiveOperationGuard
import me.awabi2048.mwmchanpon.service.ChanponOperation
import me.awabi2048.myworldmanager.api.extension.CommandPolicy
import org.bukkit.command.CommandSender

class ChanponCommandExtension(
    private val archiveOperationGuard: ArchiveOperationGuard
) : CommandPolicy {
    override fun getId(): String = "mwmchanpon.command"

    override fun canExecuteMwmSubCommand(
        sender: CommandSender,
        subCommand: String,
        args: List<String>
    ): Boolean {
        // 移行は旧設定・残留ロックを解消する復旧経路そのものです。通常操作のゲートで
        // 遮断すると、管理者が自力で復旧できなくなります。
        if (subCommand.equals("migration", ignoreCase = true)) return true
        if (!archiveOperationGuard.canPerform(sender, operationFor(subCommand, args))) return false
        return when (subCommand.lowercase()) {
            "stats" -> false
            else -> true
        }
    }

    override fun canSuggestMwmSubCommand(
        sender: CommandSender,
        subCommand: String,
        args: List<String>
    ): Boolean {
        if (subCommand.equals("migration", ignoreCase = true)) return true
        if (!archiveOperationGuard.canPerform(sender, operationFor(subCommand, args))) return false
        return when (subCommand.lowercase()) {
            "stats" -> false
            else -> true
        }
    }

    private fun operationFor(subCommand: String, args: List<String>): ChanponOperation =
        when (subCommand.lowercase()) {
            "list" -> ChanponOperation.READ
            "migration" -> if (args.getOrNull(1).equals("status", ignoreCase = true)) {
                ChanponOperation.DIAGNOSE
            } else {
                ChanponOperation.EDIT
            }
            "create" -> ChanponOperation.CREATE
            "reload", "update-day", "give", "stats" -> ChanponOperation.EDIT
            else -> ChanponOperation.EDIT
        }
}
