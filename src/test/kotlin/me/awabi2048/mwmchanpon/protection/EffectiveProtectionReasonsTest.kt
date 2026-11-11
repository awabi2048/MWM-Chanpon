package me.awabi2048.mwmchanpon.protection

import me.awabi2048.mwmchanpon.storage.SubmissionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EffectiveProtectionReasonsTest {
    @Test
    fun `バイパスは提出と完成のみ解除しシミュレーションと復元は維持する`() {
        for (state in listOf(SubmissionState.SUBMITTED, SubmissionState.COMPLETED)) {
            assertEquals(emptySet<ProtectionReason>(), effectiveProtectionReasons(true, state, false, true, false))
            assertEquals(setOf(ProtectionReason.AUTO_ROLLBACK), effectiveProtectionReasons(true, state, true, true, false))
            assertEquals(setOf(ProtectionReason.SIMULATION), effectiveProtectionReasons(true, state, false, true, true))
        }
        assertEquals(setOf(ProtectionReason.SUBMITTED), effectiveProtectionReasons(true, SubmissionState.SUBMITTED, false, false, false))
        assertEquals(setOf(ProtectionReason.COMPLETED), effectiveProtectionReasons(true, SubmissionState.COMPLETED, false, false, false))
        assertEquals(setOf(ProtectionReason.INVALID_STATE), effectiveProtectionReasons(true, null, false, true, false))
        assertEquals(setOf(ProtectionReason.SIMULATION), effectiveProtectionReasons(false, null, false, true, true))
    }
}
