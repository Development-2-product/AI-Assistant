package com.iamode.app.domain

import com.iamode.app.domain.commitments.CommitmentExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class CommitmentExtractorTest {
    private val timestamp = 1_735_171_200_000L
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun `recognizes clear dated promises`() {
        listOf("I'll send it tomorrow.", "I'll share it Friday.", "I'll send the document before Monday.").forEach {
            val result = CommitmentExtractor.extract(it, timestamp, zone)
            assertNotNull(it, result)
            assertFalse(it, result!!.needsConfirmation)
            assertNotNull(it, result.dueAt)
        }
    }

    @Test fun `marks ambiguous future promises for review`() {
        listOf("I'll call you after 6.", "I'll do it next week.", "I'll get back to you soon.").forEach {
            val result = CommitmentExtractor.extract(it, timestamp, zone)
            assertNotNull(it, result)
            assertEquals(it, true, result!!.needsConfirmation)
        }
    }

    @Test fun `rejects non commitments`() {
        listOf("I sent the report yesterday.", "Can you send the report?", "Maybe I'll call him.").forEach {
            assertNull(it, CommitmentExtractor.extract(it, timestamp, zone))
        }
    }

    @Test fun `recognizes conservative Roman Telugu promises`() {
        val result = CommitmentExtractor.extract("Nenu report repu pampistanu", timestamp, zone)
        assertNotNull(result)
        assertFalse(result!!.needsConfirmation)
    }

    @Test fun `keeps ambiguous Roman Hindi dates in review`() {
        val result = CommitmentExtractor.extract("Main report kal bhej dunga", timestamp, zone)
        assertNotNull(result)
        assertEquals(true, result!!.needsConfirmation)
        assertEquals(null, result.dueAt)
    }
}
