package me.awabi2048.mwmchanpon.extension

import me.awabi2048.mwmchanpon.storage.ChanponWorldStorage
import me.awabi2048.myworldmanager.api.extension.WorldDeleteGuard
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.command.CommandSender

class ProductionWorldGuard(private val storage: ChanponWorldStorage) : WorldDeleteGuard {
    override fun getId(): String = "chanpon.production_guard"

    override fun canDelete(worldData: WorldData, caller: CommandSender?): String? {
        if (!storage.hasCurrentWorldData(worldData.uuid)) {
            return "このワールドのChanponデータが未移行または利用不能のため変更できません"
        }
        if (storage.isImportedArchive(worldData.uuid)) {
            return "このワールドはアーカイブから復元されたワールドのため変更できません"
        }
        return if (storage.isProductionWorld(worldData.uuid)) {
            "このワールドは本番用に設定されているため削除できません"
        } else {
            null
        }
    }
}
