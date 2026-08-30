package me.awabi2048.mwmchanpon.tool

import me.awabi2048.mwmchanpon.storage.SubmissionState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPermissionSubmissionPolicyTest {
    @Test
    fun `current non-production world without submission state remains editable`() {
        assertFalse(
            ToolPermissionSubmissionPolicy.isLocked(
                hasCurrentData = true,
                production = false,
                autoRollbackLocked = false,
                submissionState = null,
            ),
        )
    }

    @Test
    fun `unsubmitted world remains editable`() {
        assertFalse(isLocked(SubmissionState.UNSUBMITTED))
    }

    @Test
    fun `submitted and completed worlds are locked`() {
        assertTrue(isLocked(SubmissionState.SUBMITTED))
        assertTrue(isLocked(SubmissionState.COMPLETED))
    }

    @Test
    fun `production world without submission state is locked`() {
        assertTrue(
            ToolPermissionSubmissionPolicy.isLocked(
                hasCurrentData = true,
                production = true,
                autoRollbackLocked = false,
                submissionState = null,
            ),
        )
    }

    @Test
    fun `auto rollback lock and non-current data always lock`() {
        assertTrue(
            ToolPermissionSubmissionPolicy.isLocked(
                hasCurrentData = true,
                production = true,
                autoRollbackLocked = true,
                submissionState = SubmissionState.UNSUBMITTED,
            ),
        )
        assertTrue(
            ToolPermissionSubmissionPolicy.isLocked(
                hasCurrentData = false,
                production = false,
                autoRollbackLocked = false,
                submissionState = SubmissionState.UNSUBMITTED,
            ),
        )
    }

    private fun isLocked(state: SubmissionState): Boolean =
        ToolPermissionSubmissionPolicy.isLocked(
            hasCurrentData = true,
            production = false,
            autoRollbackLocked = false,
            submissionState = state,
        )
}
