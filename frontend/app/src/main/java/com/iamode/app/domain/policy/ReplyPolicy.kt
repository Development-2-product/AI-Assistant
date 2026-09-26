package com.iamode.app.domain.policy

import com.iamode.app.domain.model.Analysis
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.ConversationState
import com.iamode.app.domain.model.MoneyMode
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.ReplyMode
import com.iamode.app.domain.model.UserSettings

/**
 * Decides what happens with an AI-written reply. Deterministic and fully unit-tested:
 * the AI analyses and writes, but it never decides whether a message is sent.
 */
object ReplyPolicy {

    data class Input(
        val relationship: Relationship,
        val channel: Channel,
        val contactName: String,
        val autopilot: Boolean,
        val autoTurns: Int,
        val hasOurReplies: Boolean,
    )

    sealed interface Decision {
        val reason: String

        data class AutoSend(override val reason: String, val followUp: Boolean = false) : Decision
        data class NeedsApproval(override val reason: String, val paused: Boolean = false) : Decision
        data class Crisis(override val reason: String) : Decision
        data class End(override val reason: String) : Decision
        data class Skip(override val reason: String) : Decision
    }

    fun decide(input: Input, analysis: Analysis, settings: UserSettings): Decision {
        if (analysis.crisis) {
            return Decision.Crisis("Message may signal they are in danger, so IA Mode will not reply")
        }
        if (!analysis.needsReply) {
            val reason = "No reply needed (newsletter, promotion or automated message)"
            return if (input.hasOurReplies) Decision.End(reason) else Decision.Skip(reason)
        }
        if (analysis.conversationState == ConversationState.ENDED) {
            return Decision.End("${input.contactName.firstWord()} closed the conversation, so IA Mode stopped without replying")
        }

        if (input.relationship == Relationship.GROUP) {
            return Decision.NeedsApproval("Someone mentioned you in a group, so you approve the reply")
        }

        // Email can involve contracts, documents and calendar commitments.  The dedicated
        // mail workflow is approval-first; no broad "auto email" switch is a trusted rule.
        if (input.channel == Channel.GMAIL) {
            return Decision.NeedsApproval("Review this email before anything is sent")
        }

        val risky = analysis.mentionsMoney || analysis.asksCommitment
        val limitReached = input.autoTurns >= settings.maxAutoReplies
        val policyAuto = settings.modeFor(input.relationship) == ReplyMode.AUTO

        if (policyAuto) {
            if (risky && settings.moneyMode == MoneyMode.ASK) {
                return Decision.NeedsApproval("Mentions money or a commitment, so you approve this one", paused = true)
            }
            if (limitReached) {
                return Decision.NeedsApproval("Reached ${settings.maxAutoReplies} automatic replies in this chat", paused = true)
            }
            return Decision.AutoSend("${input.relationship.label} contacts get automatic replies", followUp = risky)
        }

        if (input.autopilot) {
            if (risky) return Decision.NeedsApproval("Autopilot paused: mentions money or a commitment", paused = true)
            if (limitReached) {
                return Decision.NeedsApproval("Autopilot paused: reached ${settings.maxAutoReplies} automatic replies", paused = true)
            }
            return Decision.AutoSend("You let IA Mode handle this chat")
        }

        return Decision.NeedsApproval("${input.relationship.label} messages need your approval")
    }

    private fun String.firstWord() = trim().split(" ", "(").firstOrNull().orEmpty().ifBlank { "They" }
}
