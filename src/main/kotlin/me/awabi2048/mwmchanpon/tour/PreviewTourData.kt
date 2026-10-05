package me.awabi2048.mwmchanpon.tour

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** ツアーが立ち寄る1区画です。ワールドUUIDとその滞在時間（秒）を保持します。 */
data class PreviewTourStop(
    val worldUuid: UUID,
    val durationSeconds: Int,
)

/**
 * 完成済みワールドを順に巡る観光ツアーの定義です。
 * stops は登録順に巡業し、実行時にはこの定義の複製を各実行セッションが保持します。
 */
data class PreviewTourData(
    val uuid: UUID,
    var title: String,
    var description: String,
    val stops: MutableList<PreviewTourStop> = mutableListOf(),
    val createdBy: UUID,
    val createdAt: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
) {
    val totalDurationSeconds: Int
        get() = stops.sumOf { it.durationSeconds }
}

/**
 * ツアー開始の入口となる看板の登録情報です。
 * 看板は管理対象外のワールドにも設置できるため、Bukkitワールド名で設置場所を保持します。
 */
data class PreviewTourSignData(
    val uuid: UUID,
    val tourUuid: UUID,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
)
