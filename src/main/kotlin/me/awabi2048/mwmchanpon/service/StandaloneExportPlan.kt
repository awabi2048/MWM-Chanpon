package me.awabi2048.mwmchanpon.service

import java.util.UUID
import me.awabi2048.myworldmanager.api.service.ApiPortalSnapshot

internal data class ExportWorldIdentity(val uuid: UUID, val name: String)
internal data class StandaloneExportArchive(val fileName: String, val worldUuids: List<UUID>)

/** 出力単位を先に確定し、個別ZIP間を同じセーブデータと誤認しないようにします。 */
internal data class StandaloneExportPlan(val fileName: String, val archives: List<StandaloneExportArchive>) {
    companion object {
        fun create(year: Int, worlds: List<ExportWorldIdentity>, individual: Boolean): StandaloneExportPlan {
            require(worlds.isNotEmpty()) { "出力対象がありません。" }
            require(worlds.distinctBy { it.uuid }.size == worlds.size) { "出力対象が重複しています。" }
            val combinedName = StandaloneExportFileName.create(year, worlds.map { it.name })
            if (!individual) return StandaloneExportPlan(combinedName, listOf(StandaloneExportArchive(combinedName, worlds.map { it.uuid })))
            val names = worlds.map { StandaloneExportFileName.create(year, listOf(it.name)) }
            val archives = worlds.mapIndexed { index, world ->
                // 同名・大文字小文字だけ異なるワールドもWindowsで展開できるようUUIDで区別します。
                val base = names[index]
                val duplicate = names.count { it.equals(base, ignoreCase = true) } > 1
                val name = if (duplicate) base.removeSuffix(".zip") + "［${world.uuid}］.zip" else base
                StandaloneExportArchive(name, listOf(world.uuid))
            }
            require(archives.map { it.fileName.lowercase(java.util.Locale.ROOT) }.distinct().size == archives.size) {
                "個別ZIPの出力名が衝突しています。"
            }
            return StandaloneExportPlan("［さばちゃんぽん$year］個別ワールド一括.zip", archives)
        }
    }
}

internal object StandalonePortalTargets {
    /** 判定は同一セーブに入るワールドだけで行い、manifestと変換の条件を一致させます。 */
    fun resolve(portal: ApiPortalSnapshot, includedWorldKeys: Map<UUID, String>): UUID? =
        portal.worldUuid?.takeIf { it in includedWorldKeys }
            ?: if (portal.worldUuid == null) includedWorldKeys.entries.firstOrNull {
                it.value == portal.targetWorldKey
            }?.key else null
}
