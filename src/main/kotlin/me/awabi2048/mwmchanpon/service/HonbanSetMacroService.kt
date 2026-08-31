package me.awabi2048.mwmchanpon.service

import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import me.awabi2048.myworldmanager.api.service.ApiMacroService
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit

/**
 * 既存ワールドを本番用へ切り替えたときに渡す実行時引数です。
 *
 * 外部マクロAPIは汎用的な文字列Mapを受け取るため、引数名と値の変換は
 * このイベント固有の境界に閉じ込めます。ワールド作成時の自動本番化は、
 * 「既存ワールドの切替」というイベントの対象外なので、このサービスから
 * は実行しません。
 */
data class HonbanSetMacroArguments(
    val ownerName: String,
    val ownerUuid: UUID,
    val actorName: String,
    val actorUuid: UUID,
    val worldUuid: UUID,
    val worldName: String,
) {
    fun toMacroParameters(): Map<String, String> = linkedMapOf(
        "owner" to ownerName,
        "owner_uuid" to ownerUuid.toString(),
        "actor" to actorName,
        "actor_uuid" to actorUuid.toString(),
        "world_uuid" to worldUuid.toString(),
        "world_name" to worldName,
    )
}

/**
 * 既存ワールドが本番用ワールドへ切り替わった後に、Chanpon所有の外部マクロを実行します。
 *
 * 本番状態の永続化後に呼び出し、マクロの失敗によって確定済みの本番化を取り消さないように
 * します。コマンド展開・遅延・コンソール実行はMyWorldManagerの既存基盤へ委譲します。
 */
class HonbanSetMacroService(
    private val macroService: ApiMacroService,
    private val logger: Logger,
    private val ownerNameResolver: (UUID) -> String? = { Bukkit.getOfflinePlayer(it).name },
) {
    /** 本番化の永続化が完了した直後に実行します。 */
    fun execute(
        worldData: WorldData,
        actorUuid: UUID,
        actorName: String,
    ) {
        runCatching {
            val ownerUuid = worldData.owner
            val ownerName = ownerNameResolver(ownerUuid) ?: ownerUuid.toString()
            macroService.execute(
                TRIGGER,
                HonbanSetMacroArguments(
                    ownerName = ownerName,
                    ownerUuid = ownerUuid,
                    actorName = actorName,
                    actorUuid = actorUuid,
                    worldUuid = worldData.uuid,
                    worldName = worldData.name,
                ).toMacroParameters(),
            )
        }.onFailure { error ->
            logger.log(
                Level.SEVERE,
                "Failed to execute Chanpon honban set macro for ${worldData.uuid}",
                error,
            )
        }
    }

    companion object {
        /** MWM-Chanponのmacro.ymlで使用する本番化トリガーです。 */
        const val TRIGGER = "on_honban_set"
    }
}
