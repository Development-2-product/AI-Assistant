package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.AiException
import com.iamode.app.domain.model.AiFailure
import com.iamode.app.domain.model.AlertKind
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.ConversationState
import com.iamode.app.domain.model.ConversationStatus
import com.iamode.app.domain.model.IncomingMessage
import com.iamode.app.domain.model.Message
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.policy.ReplyPolicy
import com.iamode.app.domain.policy.ContactReplyRules
import com.iamode.app.domain.policy.ReplyPolicy.Decision
import com.iamode.app.domain.policy.SensitiveShareRequestDetector
import com.iamode.app.domain.policy.ReplyLanguagePolicy
import com.iamode.app.domain.repository.AiRepository
import com.iamode.app.domain.repository.AiRetryScheduler
import com.iamode.app.domain.repository.AlertRepository
import com.iamode.app.domain.repository.ConversationRepository
import com.iamode.app.domain.repository.Notifier
import com.iamode.app.domain.repository.ReplyScheduler
import com.iamode.app.domain.repository.SessionRepository
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.domain.repository.SituationProvider
import com.iamode.app.service.worker.MessageBurstCoordinator
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.entity.ChatCommitmentEntity
import com.iamode.app.domain.commitments.CommitmentExtractor
import com.iamode.app.service.worker.CommitmentReminderScheduler
import android.database.sqlite.SQLiteConstraintException
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** The main pipeline: store -> analyze + draft (AI) -> decide (policy) -> act. */
class ProcessIncomingMessageUseCase @Inject constructor(
    private val conversations: ConversationRepository,
    private val resolveContact: ResolveContactUseCase,
    private val settingsRepo: SettingsRepository,
    private val sessions: SessionRepository,
    private val ai: AiRepository,
    private val alerts: AlertRepository,
    private val notifier: Notifier,
    private val scheduler: ReplyScheduler,
    private val retryScheduler: AiRetryScheduler,
    private val situationProvider: SituationProvider,
    private val endConversation: EndConversationUseCase,
    private val burst: MessageBurstCoordinator,
    private val commitments: ChatCommitmentDao? = null,
    private val commitmentReminders: CommitmentReminderScheduler? = null,
) {
    suspend operator fun invoke(incoming: IncomingMessage) {
        // Mail is classified, proposed and approved exclusively by MailIntelligenceRepository.
        // This also ensures old generic Gmail conversations cannot suppress a new mail workflow run.
        if (incoming.channel == Channel.GMAIL) return
        val session = sessions.current() ?: return // IA Mode is off
        val settings = settingsRepo.current()
        val now = System.currentTimeMillis()

        val who = if (incoming.isGroup) ResolvedContact(incoming.displayName, Relationship.GROUP, null)
        else resolveContact(incoming.channel, incoming.address, incoming.displayName, settings)
        val policyReason = ContactReplyRules.restriction(who.contact, incoming.channel, incoming.timestamp)
        var conv = conversations.findOpen(incoming.channel, incoming.address, session.id)
        if (conv == null) {
            conv = Conversation(
                id = UUID.randomUUID().toString(), channel = incoming.channel, address = incoming.address,
                displayName = who.displayName, relationship = who.relationship,
                status = ConversationStatus.ANALYZING, sessionId = session.id, subject = incoming.subject,
                gmailAccount = incoming.gmail?.accountEmail, gmailThreadId = incoming.gmail?.threadId,
                forceApproval = policyReason != null, contactPolicyReason = policyReason,
                createdAt = now, updatedAt = now,
            )
            conversations.upsert(conv)
        } else if (conv.forceApproval != (policyReason != null) || conv.contactPolicyReason != policyReason) {
            // Persist the policy snapshot on every inbound message so later queued work cannot
            // use a now-revoked contact permission.
            conv = conv.copy(forceApproval = policyReason != null, contactPolicyReason = policyReason, updatedAt = now)
            conversations.upsert(conv)
        }
        // Earlier messages (email thread, unread group chat) give the AI context; they are never replied to.
        incoming.context.forEach { m ->
            conversations.addMessage(Message(conversationId = conv.id, fromMe = m.fromMe, text = m.text,
                timestamp = m.timestamp, externalId = m.externalId))
        }

        val inserted = conversations.addMessage(Message(conversationId = conv.id, fromMe = false, text = incoming.text,
            timestamp = incoming.timestamp, externalId = incoming.externalId))
        if (!inserted) return // duplicate notification

        // Local-only, incremental commitment extraction. It runs after the message
        // is durably stored and never affects the normal reply pipeline.
        incoming.externalId?.let { sourceId ->
            runCatching {
                CommitmentExtractor.extract(incoming.text, incoming.timestamp, ZoneId.systemDefault())?.let { x ->
                    val nowCommitment = System.currentTimeMillis()
                    val commitmentId = UUID.randomUUID().toString()
                    commitments?.upsert(ChatCommitmentEntity(
                        id = commitmentId,
                        contactId = who.contact?.id,
                        conversationId = conv.id, sourceMessageId = sourceId,
                        commitmentText = incoming.text.take(600), normalizedAction = x.action, dueAt = x.dueAt,
                        dueDatePrecision = x.precision, timezone = ZoneId.systemDefault().id,
                        status = if (x.needsConfirmation) "REVIEW_NEEDED" else "CONFIRMED",
                        confidence = if (x.needsConfirmation) "medium" else "high", needsConfirmation = x.needsConfirmation,
                        createdAt = nowCommitment, updatedAt = nowCommitment, completedAt = null, dismissedAt = null,
                        snoozedUntil = null, reminderAt = x.dueAt, reminderSentAt = null, followUpDraft = null,
                        sourceExcerpt = incoming.text.take(180), explanation = x.explanation,
                    ))
                    // Clear dates are confirmed by the conservative extractor; ambiguous dates remain review-only.
                    if (!x.needsConfirmation) commitmentReminders?.schedule(commitmentId, x.dueAt)
                }
            }.onFailure { error ->
                // Duplicate deliveries are expected; other failures must not break messaging.
                if (error !is SQLiteConstraintException) alerts.add(AlertKind.ERROR, "Commitment tracking unavailable", error.javaClass.simpleName, conv.id)
            }
        }

        if (conv.status == ConversationStatus.QUEUED) scheduler.cancel(conv.id)
        notifier.cancel(conv.id)
        conversations.upsert(conv.copy(
            status = ConversationStatus.ANALYZING, pendingReply = null, updatedAt = now,
            gmailMessageId = incoming.gmail?.messageIdHeader ?: conv.gmailMessageId,
            gmailReferences = incoming.gmail?.references ?: conv.gmailReferences,
            gmailThreadId = incoming.gmail?.threadId ?: conv.gmailThreadId,
        ))
        if (incoming.channel == Channel.GMAIL) {
            alerts.add(AlertKind.NEW_MAIL, "New mail from ${conv.displayName}",
                listOfNotNull(incoming.subject, incoming.text.take(200)).joinToString(": "), conv.id)
        }
        // Give a person room to finish a thought split across several short notifications.
        // Each arrival resets this timer; the guarded analyzer below still verifies it is latest.
        burst.schedule(conv.id) {
            if (sessions.current()?.id == conv.sessionId) analyzeAndAct(conv.id, incoming.text, incoming.externalId, isRetry = false)
        }
    }

    /**
     * Retries the AI for a conversation whose last attempt failed.
     * Returns false only if it failed again for a reason worth retrying later.
     */
    suspend fun retry(conversationId: String): Boolean {
        val c = conversations.get(conversationId) ?: return true
        val waitingForAi = c.status == ConversationStatus.PENDING_APPROVAL && !c.aiGenerated && c.pendingReply.isNullOrBlank()
        if (!waitingForAi || sessions.current()?.id != c.sessionId) return true // user acted, or IA Mode was turned off
        val last = conversations.recentMessages(c.id, 1).lastOrNull() ?: return true
        if (last.fromMe) return true
        return analyzeAndAct(c.id, last.text, last.externalId, isRetry = true)
    }

    private suspend fun analyzeAndAct(conversationId: String, incomingText: String, externalId: String?, isRetry: Boolean): Boolean {
        val settings = settingsRepo.current()
        val conv = conversations.get(conversationId) ?: return true
        val history = conversations.recentMessages(conv.id, 20)
        val result = ai.process(conv, history, settings, situationProvider.current())

        // If a newer message arrived meanwhile, that run wins.
        if (conversations.recentMessages(conv.id, 1).lastOrNull()?.externalId != externalId) return true
        val fresh = conversations.get(conv.id) ?: return true

        val out = result.getOrElse { error ->
            // Never auto-send without the AI's analysis. Hand it to the user, and retry if that can help.
            val failure = (error as? AiException)?.failure ?: AiFailure.UNKNOWN
            val c = fresh.copy(status = ConversationStatus.PENDING_APPROVAL, pendingReply = "", aiGenerated = false,
                reason = failure.userMessage, updatedAt = System.currentTimeMillis())
            conversations.upsert(c)
            if (!isRetry) {
                alerts.add(AlertKind.APPROVAL, "Reply to ${c.displayName} needs you", failure.userMessage, c.id)
                if (settings.approvalNotifications) notifier.approvalNeeded(c, incomingText)
                if (failure.retryable) retryScheduler.scheduleRetry(c.id)
            }
            return !failure.retryable
        }
        if (isRetry) notifier.cancel(fresh.id) // replace the "write it yourself" notification

        val a = out.analysis
        var decision = ReplyPolicy.decide(
            ReplyPolicy.Input(fresh.relationship, fresh.channel, fresh.displayName, fresh.autopilot, fresh.autoTurns,
                hasOurReplies = history.any { it.fromMe }, forceApproval = fresh.forceApproval,
                contactPolicyReason = fresh.contactPolicyReason),
            a, settings,
        )
        // A detected request for an email identity or location is always an explicit, per-message
        // decision. This overrides relationship/autopilot settings and prevents accidental sharing.
        SensitiveShareRequestDetector.detect(incomingText)?.let { request ->
            decision = Decision.NeedsApproval("${request.name.lowercase().replace('_', ' ')} sharing always needs your approval", paused = true)
        }
        // A language/style mismatch is not safe to auto-send. The draft remains available
        // for review, but it cannot silently switch a Telugu/Hindi/English conversation.
        if (!ReplyLanguagePolicy.matches(out.reply, a.language)) {
            decision = Decision.NeedsApproval("Reply language needs your review", paused = true)
        }
        var c = fresh.copy(
            tone = a.tone, style = a.style, language = a.language, summary = a.summary.ifBlank { fresh.summary },
            mentionsMoney = a.mentionsMoney, asksCommitment = a.asksCommitment, pendingReply = out.reply,
            closing = a.conversationState == ConversationState.WRAPPING_UP, reason = decision.reason,
            aiGenerated = true, isCallReply = false, updatedAt = System.currentTimeMillis(),
        )

        when (decision) {
            is Decision.AutoSend -> {
                if (out.reply.isBlank()) {
                    c = c.copy(status = ConversationStatus.PENDING_APPROVAL, reason = "The AI didn't write a reply, so this one needs you")
                    conversations.upsert(c)
                    if (settings.approvalNotifications) notifier.approvalNeeded(c, incomingText)
                } else {
                    val at = System.currentTimeMillis() + settings.undoSeconds * 1000L
                    c = c.copy(status = ConversationStatus.QUEUED, autopilot = true, followUp = decision.followUp, sendAt = at)
                    conversations.upsert(c)
                    alerts.add(AlertKind.AUTOMATION, "Automatic reply queued", "Queued with an undo delay", c.id)
                    scheduler.schedule(c.id, at)
                }
            }
            is Decision.NeedsApproval -> {
                c = c.copy(status = ConversationStatus.PENDING_APPROVAL, autopilot = if (decision.paused) false else c.autopilot)
                conversations.upsert(c)
                if (fresh.forceApproval) {
                    alerts.add(AlertKind.POLICY, "Automatic reply paused", decision.reason, c.id)
                }
                val title = if (decision.paused) "IA Mode paused the chat with ${c.displayName}" else "Reply to ${c.displayName} needs your approval"
                alerts.add(AlertKind.APPROVAL, title, "Draft ready for review", c.id)
                if (settings.approvalNotifications) notifier.approvalNeeded(c, incomingText)
            }
            is Decision.Crisis -> {
                c = c.copy(status = ConversationStatus.CRISIS, autopilot = false, pendingReply = null)
                conversations.upsert(c)
                alerts.add(AlertKind.CRISIS, "${c.displayName} may need you personally", "IA Mode did not reply. Consider calling them now.", c.id)
                notifier.crisis(c)
            }
            is Decision.End -> {
                conversations.upsert(c.copy(pendingReply = null))
                endConversation(c.id, decision.reason)
            }
            is Decision.Skip -> conversations.upsert(
                c.copy(status = ConversationStatus.SKIPPED, pendingReply = null, endedAt = System.currentTimeMillis())
            )
        }
        return true
    }
}
