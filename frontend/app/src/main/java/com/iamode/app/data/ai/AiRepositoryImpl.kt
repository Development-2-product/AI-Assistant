package com.iamode.app.data.ai

import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.core.network.IAModeApi
import com.iamode.app.core.network.dto.AnalysisDto
import com.iamode.app.core.network.dto.ChatMessageDto
import com.iamode.app.core.network.dto.ContactDto
import com.iamode.app.core.network.dto.LanguageHintDto
import com.iamode.app.core.network.dto.MissedCallRequest
import com.iamode.app.core.network.dto.ProcessMessageRequest
import com.iamode.app.core.network.dto.RecapRequest
import com.iamode.app.core.network.dto.RestyleRequest
import com.iamode.app.core.network.dto.SituationDto
import com.iamode.app.core.network.dto.UserContextDto
import com.iamode.app.domain.model.AiException
import com.iamode.app.domain.model.AiFailure
import com.iamode.app.domain.model.AiResult
import com.iamode.app.domain.model.Analysis
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationState
import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.MessageKind
import com.iamode.app.domain.model.MoneyMode
import com.iamode.app.domain.model.ReplyStyle
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.model.Situation
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.repository.AiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the IA Mode backend, which calls Gemini. Only the last few messages of one
 * conversation are sent, and the backend stores nothing.
 */
@Singleton
class AiRepositoryImpl @Inject constructor(
    private val api: IAModeApi,
    private val log: DiagnosticsLog,
) : AiRepository {

    override suspend fun process(conversation: Conversation, messages: List<Message>, settings: UserSettings, situation: Situation) =
        call {
            val res = api.process(
                ProcessMessageRequest(
                    channel = conversation.channel.name.lowercase(),
                    contact = conversation.toContactDto(),
                    user = user(settings, situation),
                    subject = conversation.subject,
                    messages = messages.takeLast(MAX_CONTEXT).map { it.toDto() },
                    moneyMode = if (settings.moneyMode == MoneyMode.ASK) "ask" else "safe_reply",
                )
            )
            AiResult(res.analysis.toDomain(), res.reply)
        }

    override suspend fun restyle(conversation: Conversation, messages: List<Message>, style: ReplyStyle, settings: UserSettings, situation: Situation) =
        call {
            api.restyle(
                RestyleRequest(
                    channel = conversation.channel.name.lowercase(), contact = conversation.toContactDto(),
                    user = user(settings, situation), subject = conversation.subject,
                    messages = messages.takeLast(MAX_CONTEXT).map { it.toDto() }, style = style.apiValue,
                )
            ).reply
        }

    override suspend fun missedCallReply(
        conversation: Conversation, history: List<Message>, callsLast10Min: Int,
        language: Language, languageSource: String, settings: UserSettings, situation: Situation,
    ) = call {
        api.missedCallReply(
            MissedCallRequest(
                contact = conversation.toContactDto(), user = user(settings, situation),
                history = history.takeLast(10).map { it.toDto() }, missedCallsLast10Min = callsLast10Min.coerceAtLeast(1),
                language = LanguageHintDto(language.code.apiValue, language.script.name.lowercase()),
                languageSource = languageSource,
                localTime = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date()),
            )
        ).reply
    }

    override suspend fun recap(conversation: Conversation, messages: List<Message>) = call {
        api.recap(RecapRequest(conversation.toContactDto(), messages.takeLast(60).map { it.toDto() })).recap
    }

    /**
     * Retries busy/timeout errors twice (2 s, 5 s), which also covers a free-tier server waking up.
     * Every failure is mapped to an [AiFailure] the UI can explain.
     */
    private suspend fun <T> call(block: suspend () -> T): Result<T> {
        var last: AiFailure = AiFailure.UNKNOWN
        var cause: Throwable? = null
        for (attempt in 0..2) {
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cause = e
                last = classify(e)
                if (last != AiFailure.SERVER_BUSY || attempt == 2) break
                delay(if (attempt == 0) 2_000L else 5_000L)
            }
        }
        log.record("AI", false, "${last.name}: ${describe(cause)}")
        return Result.failure(AiException(last, cause))
    }

    /** The real reason in one line, e.g. "HTTP 401: Dev token rejected…" or "ConnectException: Failed to connect". */
    private fun describe(e: Throwable?): String = when (e) {
        null -> "unknown"
        is HttpException -> {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            val detail = Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: body.take(160)
            "HTTP ${e.code()}${if (detail.isNotBlank()) ": $detail" else ""}"
        }
        else -> "${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}"
    }

    private fun classify(e: Exception): AiFailure = when (e) {
        is UnknownHostException -> AiFailure.OFFLINE
        is SocketTimeoutException, is ConnectException -> AiFailure.SERVER_BUSY
        is HttpException -> when (e.code()) {
            401, 403 -> AiFailure.AUTH
            400, 404, 413, 422 -> AiFailure.REJECTED
            408, 429, 500, 502, 503, 504 -> AiFailure.SERVER_BUSY
            else -> AiFailure.UNKNOWN
        }
        is IOException -> AiFailure.OFFLINE
        else -> AiFailure.UNKNOWN
    }

    private fun Conversation.toContactDto() = ContactDto(displayName, relationship.apiValue)

    private fun user(s: UserSettings, situation: Situation) = UserContextDto(
        name = s.myName.trim(), gender = s.gender.name.lowercase(),
        situation = SituationDto(situation.status.apiValue, situation.reason),
    )

    private fun Message.toDto() = ChatMessageDto(
        sender = if (fromMe) "me" else "them",
        text = text.take(6000),
        kind = when (kind) {
            MessageKind.TEXT -> "text"
            MessageKind.CALL -> "call"
            MessageKind.CALL_REPLY -> "call_reply"
        },
    )

    private fun AnalysisDto.toDomain() = Analysis(
        language = Language(LanguageCode.fromApi(language), if (script == "native") Script.NATIVE else Script.ROMAN),
        tone = tone, summary = summary, mentionsMoney = mentionsMoney, asksCommitment = asksCommitment,
        crisis = crisis, needsReply = needsReply,
        conversationState = when (conversationState) {
            "ended" -> ConversationState.ENDED
            "wrapping_up" -> ConversationState.WRAPPING_UP
            else -> ConversationState.ONGOING
        },
        style = ReplyStyle.fromApi(style),
    )

    private companion object { const val MAX_CONTEXT = 14 }
}
