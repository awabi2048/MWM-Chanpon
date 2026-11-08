package me.awabi2048.mwmchanpon.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ScheduledExecutionGateTest {
    private val target = LocalDateTime.of(2026, 11, 8, 4, 0)
    private val time: LocalTime = target.toLocalTime()

    @Test
    fun `指定時刻より前には実行せず五秒以内に一度だけ実行する`() {
        val gate = ScheduledExecutionGate()
        assertEquals(ScheduledExecutionGate.Decision.WAIT, gate.observe(target.minusNanos(1), time))
        assertEquals(ScheduledExecutionGate.Decision.EXECUTE, gate.observe(target.plusSeconds(5), time))
        assertEquals(ScheduledExecutionGate.Decision.WAIT, gate.observe(target.plusSeconds(5), time))
    }

    @Test
    fun `五秒を超えた場合と定刻後に起動した場合はスキップする`() {
        val delayed = ScheduledExecutionGate()
        delayed.observe(target.minusSeconds(1), time)
        assertEquals(ScheduledExecutionGate.Decision.SKIP, delayed.observe(target.plusSeconds(5).plusNanos(1), time))
        assertEquals(ScheduledExecutionGate.Decision.SKIP, ScheduledExecutionGate().observe(target.plusSeconds(1), time))
    }

    @Test
    fun `翌日と時刻変更は新しい定刻として事前観測が必要`() {
        val gate = ScheduledExecutionGate()
        gate.observe(target.minusSeconds(1), time)
        assertEquals(ScheduledExecutionGate.Decision.EXECUTE, gate.observe(target, time))
        assertEquals(ScheduledExecutionGate.Decision.WAIT, gate.observe(target.plusDays(1).minusSeconds(1), time))
        assertEquals(ScheduledExecutionGate.Decision.EXECUTE, gate.observe(target.plusDays(1), time))
        assertEquals(ScheduledExecutionGate.Decision.SKIP, gate.observe(target.plusDays(1).plusHours(1), time.plusMinutes(30)))
    }
}
