package me.awabi2048.mwmchanpon.service

import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import me.awabi2048.mwmchanpon.storage.SubmissionState
import me.awabi2048.myworldmanager.api.service.ApiMacroService
import me.awabi2048.myworldmanager.model.WorldData
import org.bukkit.Bukkit

/**
 * 完成設定マクロで使用する実行時引数です。
 *
 * マクロ種類ごとにこのような引数型を持たせ、汎用APIへ渡す文字列Mapへの変換を
 * その種類の境界へ閉じ込めます。将来別のマクロを追加しても引数の意味が混ざりません。
 */
data class SubmissionCompletionMacroArguments(
    val ownerName: String,
    val ownerUuid: UUID,
    val adminName: String,
    val adminUuid: UUID,
    val worldUuid: UUID,
    val worldName: String,
    val previousState: SubmissionState,
) {
    fun toMacroParameters(): Map<String, String> = linkedMapOf(
        "owner" to ownerName,
        "owner_uuid" to ownerUuid.toString(),
        "admin" to adminName,
        "admin_uuid" to adminUuid.toString(),
        "world_uuid" to worldUuid.toString(),
        "world_name" to worldName,
        "previous_state" to previousState.storageValue,
        "state" to SubmissionState.COMPLETED.storageValue,
    )
}

/**
 * 管理者がワールドの完成フラグを設定した後に、Chanpon所有のマクロを実行します。
 *
 * コマンド展開とコンソール実行はMyWorldManagerの既存MacroManagerへ委譲し、
 * 遅延が必要な場合はマクロ文字列からCC-Systemのdelayコマンドを利用します。
 * このクラスではChanpon固有のトリガーとプレースホルダーだけを管理します。
 */
class SubmissionCompletionMacroService(
    private val macroService: ApiMacroService,
    private val logger: Logger,
    private val ownerNameResolver: (UUID) -> String? = { Bukkit.getOfflinePlayer(it).name },
) {
    /**
     * 完成状態の永続化後に呼び出します。マクロは外部副作用なので、実行失敗しても
     * すでに保存した完成状態を取り消さず、サーバーログへ原因だけを残します。
     */
    fun execute(
        worldData: WorldData,
        previousState: SubmissionState,
        adminUuid: UUID,
        adminName: String,
    ) {
        runCatching {
            val ownerUuid = worldData.owner
            val ownerName = ownerNameResolver(ownerUuid) ?: ownerUuid.toString()
            macroService.execute(
                TRIGGER,
                SubmissionCompletionMacroArguments(
                    ownerName = ownerName,
                    ownerUuid = ownerUuid,
                    adminName = adminName,
                    adminUuid = adminUuid,
                    worldUuid = worldData.uuid,
                    worldName = worldData.name,
                    previousState = previousState,
                ).toMacroParameters(),
            )
        }.onFailure { error ->
            logger.log(
                Level.SEVERE,
                "Failed to execute Chanpon submission completion macro for ${worldData.uuid}",
                error,
            )
        }
    }

    companion object {
        /** MWM-Chanponのmacro.ymlで使用する完成状態設定トリガーです。 */
        const val TRIGGER = "on_submission_complete"
    }
}
