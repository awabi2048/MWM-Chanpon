package me.awabi2048.mwmchanpon.backup

import java.time.LocalDateTime
import java.util.UUID

enum class BackupKind(val storageValue: String, val i18nKey: String) {
    USER_MANUAL("user_manual", "user_manual"),
    ADMIN_MANUAL("admin_manual", "admin_manual"),
    SCHEDULED("scheduled", "scheduled"),
    RESTORE_POINT("restore_point", "restore_point"),
    PRE_ROLLBACK("pre_rollback", "pre_rollback");

    companion object {
        fun fromStorageValue(value: String?): BackupKind? = entries.firstOrNull { it.storageValue == value }
    }
}

enum class BackupScope(val storageValue: String) {
    WORLD("world"),
    ALL("all");

    companion object {
        fun fromStorageValue(value: String?): BackupScope? = entries.firstOrNull { it.storageValue == value }
    }
}

enum class BackupOperationStatus {
    SUCCESS,
    FAILED,
    RECOVERED,
    RECOVERY_FAILED
}

data class BackupEntry(
    val id: String,
    val scope: BackupScope,
    val kind: BackupKind,
    val createdAt: LocalDateTime,
    val worldUuids: List<UUID>,
    val actorUuid: UUID?,
    val actorName: String?,
    val reason: String?,
    val sizeBytes: Long
)

data class BackupOperationResult(
    val status: BackupOperationStatus,
    val backup: BackupEntry?,
    val message: String
)

data class RestoreOperationResult(
    val status: BackupOperationStatus,
    val restoredWorlds: List<UUID>,
    val failedWorlds: List<UUID>,
    val preRollbackBackup: BackupEntry?,
    val message: String
)
