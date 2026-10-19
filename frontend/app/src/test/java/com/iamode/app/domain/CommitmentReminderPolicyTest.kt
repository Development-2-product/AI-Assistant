package com.iamode.app.domain

import com.iamode.app.service.worker.CommitmentReminderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentReminderPolicyTest {
    @Test fun `only an active commitment with its current reminder may notify`() {
        assertTrue(CommitmentReminderPolicy.shouldDeliver("CONFIRMED", 100L, 100L))
        assertTrue(CommitmentReminderPolicy.shouldDeliver("SNOOZED", 200L, 200L))
        assertFalse(CommitmentReminderPolicy.shouldDeliver("COMPLETED", 100L, 100L))
        assertFalse(CommitmentReminderPolicy.shouldDeliver("DISMISSED", 100L, 100L))
        assertFalse(CommitmentReminderPolicy.shouldDeliver("CONFIRMED", null, 100L))
        assertFalse(CommitmentReminderPolicy.shouldDeliver("CONFIRMED", 200L, 100L))
    }

    @Test fun `each commitment has one deterministic work name`() {
        assertEquals("commitment_reminder_abc-123", CommitmentReminderPolicy.uniqueWorkName("abc-123"))
    }
}
