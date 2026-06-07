package me.awabi2048.mwmchanpon.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BackupDisplay {
    const val DEFAULT_OWNER_MANUAL_NAME = "手動バックアップ"
    const val ADMIN_MANUAL_NAME = "スタッフによる手動バックアップ"
    const val SCHEDULED_NAME = "定時バックアップ"
    const val PRE_ROLLBACK_NAME = "ロールバック時バックアップ"

    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm")

    fun displayName(entry: BackupEntry): String {
        return entry.name?.takeIf { it.isNotBlank() } ?: defaultName(entry.kind) ?: entry.id.take(18)
    }

    fun defaultName(kind: BackupKind): String? {
        return when (kind) {
            BackupKind.USER_MANUAL -> DEFAULT_OWNER_MANUAL_NAME
            BackupKind.ADMIN_MANUAL -> ADMIN_MANUAL_NAME
            BackupKind.SCHEDULED -> SCHEDULED_NAME
            BackupKind.PRE_ROLLBACK -> PRE_ROLLBACK_NAME
            BackupKind.RESTORE_POINT -> null
        }
    }

    fun normalizeManualName(input: String?): String {
        return input?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_OWNER_MANUAL_NAME
    }

    fun formatCreatedAt(value: LocalDateTime): String {
        return value.format(dateTimeFormatter)
    }

    fun formatDuration(seconds: Long): String {
        val totalMinutes = ((seconds.coerceAtLeast(1L)) + 59L) / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}時間${minutes}分"
            hours > 0L -> "${hours}時間"
            else -> "${minutes.coerceAtLeast(1L)}分"
        }
    }
}
