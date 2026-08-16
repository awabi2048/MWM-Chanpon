package me.awabi2048.mwmchanpon.backup

import com.awabi2048.ccsystem.api.localization.LocalizationKey
import com.awabi2048.ccsystem.api.localization.generated.ChanponBackupKeys
import java.time.LocalDateTime
import java.util.UUID

enum class BackupKind(val storageValue: String, val displayKey: LocalizationKey<String>) {
    USER_MANUAL("user_manual", ChanponBackupKeys.CHANPON_BACKUP_KIND_USER_MANUAL),
    ADMIN_MANUAL("admin_manual", ChanponBackupKeys.CHANPON_BACKUP_KIND_ADMIN_MANUAL),
    SCHEDULED("scheduled", ChanponBackupKeys.CHANPON_BACKUP_KIND_SCHEDULED),
    RESTORE_POINT("restore_point", ChanponBackupKeys.CHANPON_BACKUP_KIND_RESTORE_POINT),
    PRE_ROLLBACK("pre_rollback", ChanponBackupKeys.CHANPON_BACKUP_KIND_PRE_ROLLBACK);

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
    val name: String?,
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

/** 対象ワールド専用画面に表示できるのは、単一ワールドとして保存されたバックアップだけです。 */
internal fun BackupEntry.isDedicatedWorldBackupFor(worldUuid: UUID): Boolean =
    scope == BackupScope.WORLD && worldUuid in worldUuids
