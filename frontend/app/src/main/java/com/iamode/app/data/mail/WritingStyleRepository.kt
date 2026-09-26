package com.iamode.app.data.mail

import com.iamode.app.core.database.dao.MailWorkflowDao
import com.iamode.app.core.database.entity.WritingStyleEntity
import com.iamode.app.core.network.dto.StyleHintsDto
import com.iamode.app.data.gmail.GmailRepository
import com.iamode.app.domain.mail.Formality
import com.iamode.app.domain.mail.WritingStyle
import com.iamode.app.domain.mail.WritingStyleAnalyzer
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Learns the user's writing style on the phone from their own sent mail, and keeps only the compact profile. */
@Singleton
class WritingStyleRepository @Inject constructor(
    private val gmail: GmailRepository,
    private val outlook: com.iamode.app.data.outlook.OutlookRepository,
    private val dao: MailWorkflowDao,
    private val json: Json,
) {
    @Serializable private data class Stored(
        val greeting: String? = null, val signOff: String? = null, val averageWords: Int = 80,
        val formality: String = "NEUTRAL", val usesEmoji: Boolean = false, val samples: List<String> = emptyList(),
    )

    val style = dao.observeStyle().map { it?.let(::decode) }

    suspend fun current(): WritingStyle? = dao.style()?.let(::decode)

    /** True if the profile is missing or older than a week. */
    suspend fun isStale(): Boolean = dao.style()?.let { System.currentTimeMillis() - it.updatedAt > 7 * DAY } ?: true

    /** Reads up to 40 recent sent emails, analyzes them on the phone, stores only the profile. */
    suspend fun refresh(): WritingStyle? {
        val sent = gmail.fetchSentBodies(40).let { g -> g + outlook.fetchSentBodies((40 - g.size).coerceAtLeast(0)) }
        val style = WritingStyleAnalyzer.analyze(sent) ?: return null
        dao.upsertStyle(WritingStyleEntity("me", json.encodeToString(Stored.serializer(), Stored(
            style.greeting, style.signOff, style.averageWords, style.formality.name, style.usesEmoji, style.samples,
        )), style.sampleCount, System.currentTimeMillis()))
        return style
    }

    suspend fun forget() = dao.deleteStyle()

    fun hints(s: WritingStyle) = StyleHintsDto(
        greeting = s.greeting, signOff = s.signOff, averageWords = s.averageWords,
        formality = s.formality.name.lowercase(), usesEmoji = s.usesEmoji, samples = s.samples,
    )

    private fun decode(e: WritingStyleEntity): WritingStyle? = runCatching {
        val s = json.decodeFromString(Stored.serializer(), e.profileJson)
        WritingStyle(s.greeting, s.signOff, s.averageWords, Formality.valueOf(s.formality), s.usesEmoji, s.samples, e.sampleCount)
    }.getOrNull()

    private companion object { const val DAY = 86_400_000L }
}
