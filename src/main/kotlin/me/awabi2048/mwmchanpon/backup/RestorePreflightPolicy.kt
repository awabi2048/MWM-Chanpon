package me.awabi2048.mwmchanpon.backup

data class RestorePreflightTarget(
    val includedInBackup: Boolean,
    val productionWorld: Boolean,
    val worldDataAvailable: Boolean,
    val archiveReadable: Boolean,
    val targetDirectoryWritable: Boolean,
    val worldMigrationCurrent: Boolean,
    val chanponDataCurrent: Boolean,
)

object RestorePreflightPolicy {
    fun failureReason(
        backupLocked: Boolean,
        coreAvailable: Boolean,
        globalMigrationPending: Boolean,
        targets: List<RestorePreflightTarget>,
        evacuationRequired: Boolean,
        evacuationAvailable: Boolean
    ): String? {
        if (backupLocked) return "backup is locked"
        if (!coreAvailable) return "core api unavailable"
        if (globalMigrationPending) return "global migration is pending"
        if (targets.any { !it.worldMigrationCurrent }) return "world migration is pending"
        if (targets.any { !it.chanponDataCurrent }) return "Chanpon world data requires /mwm migration"
        if (targets.isEmpty() || targets.none { it.productionWorld }) return "no production worlds"
        if (targets.any { !it.includedInBackup }) return "world is not included in backup"
        if (targets.any { !it.productionWorld }) return "world is not a production world"
        if (targets.any { !it.worldDataAvailable }) return "world data missing"
        if (targets.any { !it.archiveReadable }) return "backup world archive is missing or unreadable"
        if (targets.any { !it.targetDirectoryWritable }) return "world directory is not writable"
        if (evacuationRequired && !evacuationAvailable) {
            return "spawn world is not configured or unavailable"
        }
        return null
    }
}
