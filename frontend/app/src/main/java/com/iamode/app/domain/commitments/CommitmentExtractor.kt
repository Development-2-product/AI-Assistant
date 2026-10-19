package com.iamode.app.domain.commitments

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ExtractedCommitment(val action: String, val dueAt: Long?, val precision: String, val needsConfirmation: Boolean, val explanation: String)

/** Local conservative parser: only first-person/imperative future promises become commitments. */
object CommitmentExtractor {
    fun extract(text: String, timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): ExtractedCommitment? {
        val s = text.trim().lowercase()
        if (s.contains("maybe") || s.contains("hope to") || s.startsWith("can you") || s.startsWith("did you") || s.startsWith("i sent")) return null
        val english = Regex("^(?:i(?:'ll| will)\\s+|call you\\s+)(.+?)\\s*[.!]?$").find(s)?.groupValues?.getOrNull(1)
        // Conservative Roman Telugu/Hindi/Hinglish promise forms. Ambiguous words such as Hindi
        // "kal" deliberately never become a calendar date without user confirmation.
        val telugu = Regex("^nenu\\s+(.+?)(?:\\s+(?:repu|ivala))?\\s+(?:pampistanu|pamputhanu|call chestanu|chesestanu)\\s*[.!]?$").find(s)?.groupValues?.getOrNull(1)
        val hindi = Regex("^main\\s+(.+?)\\s+(?:bhej dunga|bhejungi|call karunga|call karungi|kar dunga|karungi)\\s*[.!]?$").find(s)?.groupValues?.getOrNull(1)
        val promised = english ?: telugu ?: hindi ?: return null
        val action = promised.replace(Regex("\\s+(?:tomorrow|today|(?:by\\s+)?friday|before monday|next week|soon|after 6(?:\\s*(?:am|pm))?|repu|ivala|kal).*$"), "")
            .trim().ifBlank { return null }
        val date = Instant.ofEpochMilli(timestamp).atZone(zone)
        return when {
            "tomorrow" in s || "repu" in s -> ExtractedCommitment(action, date.plusDays(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), "DAY", false, "First-person future action with an unambiguous relative day.")
            "friday" in s -> ExtractedCommitment(action, date.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY)).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), "DAY", false, "First-person future action with a weekday.")
            "before monday" in s -> ExtractedCommitment(action, date.with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), "DAY", false, "First-person future action with a weekday deadline.")
            "next week" in s || "soon" in s || "after 6" in s || "kal" in s -> ExtractedCommitment(action, null, if ("next week" in s) "WEEK" else "UNKNOWN", true, "Future action has no unambiguous due date and needs review.")
            else -> null
        }
    }
}
