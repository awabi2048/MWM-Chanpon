package me.awabi2048.mwmchanpon.ui

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldDataBackupScreenContractTest {
    @Test
    fun `一括操作と対象ワールド専用操作を別項目として公開する`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/WorldDataManagementMenu.kt",
        ).readText()

        assertTrue("ACTION_BULK_BACKUP" in source)
        assertTrue("worldBackupEntry(" in source)
        assertTrue("ACTION_WORLD_BACKUP" in source)
        assertTrue("ITEMS_PER_WORLD_STRIDE = 2" in source)
        assertTrue("CHANPON_BACKUP_WORLD_DATA_WORLD_BACKUP_DISPLAY" in source)
        assertFalse("world_data.world.backup_action" in source)
    }

    @Test
    fun `対象ワールド用バックアップ一覧の再確認は同じ一覧のスコープに限定する`() {
        val source = Path.of(
            "src/main/kotlin/me/awabi2048/mwmchanpon/ui/BackupListMenu.kt",
        ).readText()

        assertTrue("return backups(source(route)).firstOrNull { it.id == backupId }" in source)
        assertTrue("BackupListMode.WORLD -> source.worldUuid" in source)
        assertTrue("BackupListMode.ALL -> backupService.listBackups().filter { it.scope == BackupScope.ALL }" in source)
    }
}
