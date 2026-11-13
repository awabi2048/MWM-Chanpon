package me.awabi2048.mwmchanpon.service

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** 起動前に過ぎた定刻を実行せず、稼働中に観測した定刻だけを一度判定します。 */
internal class ScheduledExecutionGate {
    internal enum class Decision { WAIT, EXECUTE, SKIP }

    private var target: LocalDateTime? = null
    private var armed = false
    private var decided = false

    fun observe(now: LocalDateTime, time: LocalTime): Decision {
        val scheduled = scheduledDate(now, time).atTime(time)
        if (target != scheduled) {
            target = scheduled
            armed = false
            decided = false
        }
        if (decided) return Decision.WAIT
        if (now.isBefore(scheduled)) {
            armed = true
            return Decision.WAIT
        }
        decided = true
        return if (armed && Duration.between(scheduled, now) <= Duration.ofSeconds(5)) {
            Decision.EXECUTE
        } else {
            Decision.SKIP
        }
    }

    fun reset() {
        target = null
        armed = false
        decided = false
    }

    companion object {
        /** YAML 1.1 は引用符のない 22:46 を 1366 分として解釈するため両形式を扱います。 */
        fun parseConfiguredTime(value: Any?): LocalTime? = when (value) {
            is String -> runCatching { LocalTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
            is Int -> if (value in 0 until 24 * 60) LocalTime.of(value / 60, value % 60) else null
            else -> null
        }

        /** 0時の定刻は前日から監視し、日付が変わっても事前観測を維持します。 */
        fun scheduledDate(now: LocalDateTime, time: LocalTime): java.time.LocalDate =
            if (time == LocalTime.MIDNIGHT && now.toLocalTime() >= LocalTime.NOON) now.toLocalDate().plusDays(1)
            else now.toLocalDate()
    }
}
