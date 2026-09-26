package com.iamode.app.domain.mail

/** Lifecycle of every AI-proposed action. Only these transitions are legal. */
enum class MailActionStatus {
    PROPOSED, REVIEW_REQUIRED, APPROVED, EXECUTING, COMPLETED, FAILED, REJECTED, EXPIRED;

    val isFinal: Boolean get() = this == COMPLETED || this == REJECTED || this == EXPIRED
    val isPending: Boolean get() = this == PROPOSED || this == REVIEW_REQUIRED || this == APPROVED || this == FAILED
}

enum class MailActionEvent { SUBMIT_FOR_REVIEW, APPROVE, START, SUCCEED, FAIL, REJECT, EXPIRE, RETRY }

object ActionStateMachine {
    private val table: Map<Pair<MailActionStatus, MailActionEvent>, MailActionStatus> = mapOf(
        (MailActionStatus.PROPOSED to MailActionEvent.SUBMIT_FOR_REVIEW) to MailActionStatus.REVIEW_REQUIRED,
        (MailActionStatus.PROPOSED to MailActionEvent.APPROVE) to MailActionStatus.APPROVED,
        (MailActionStatus.REVIEW_REQUIRED to MailActionEvent.APPROVE) to MailActionStatus.APPROVED,
        (MailActionStatus.PROPOSED to MailActionEvent.REJECT) to MailActionStatus.REJECTED,
        (MailActionStatus.REVIEW_REQUIRED to MailActionEvent.REJECT) to MailActionStatus.REJECTED,
        (MailActionStatus.APPROVED to MailActionEvent.REJECT) to MailActionStatus.REJECTED,
        (MailActionStatus.APPROVED to MailActionEvent.START) to MailActionStatus.EXECUTING,
        (MailActionStatus.APPROVED to MailActionEvent.EXPIRE) to MailActionStatus.EXPIRED,
        (MailActionStatus.REVIEW_REQUIRED to MailActionEvent.EXPIRE) to MailActionStatus.EXPIRED,
        (MailActionStatus.EXECUTING to MailActionEvent.SUCCEED) to MailActionStatus.COMPLETED,
        (MailActionStatus.EXECUTING to MailActionEvent.FAIL) to MailActionStatus.FAILED,
        // A failed action goes back to review: the user approves again before any retry.
        (MailActionStatus.FAILED to MailActionEvent.RETRY) to MailActionStatus.REVIEW_REQUIRED,
        (MailActionStatus.FAILED to MailActionEvent.REJECT) to MailActionStatus.REJECTED,
    )

    fun next(from: MailActionStatus, event: MailActionEvent): MailActionStatus? = table[from to event]
}

/** One email's journey from detection to a completed action, including the once-only celebration. */
enum class CelebrationState {
    DETECTED, CLASSIFIED, CELEBRATION_ELIGIBLE, CELEBRATION_SHOWN, DETAILS_VIEWED,
    ACTION_REQUIRED, ACTION_APPROVED, ACTION_COMPLETED;

    /** States only move forward, so a replay or a re-sync can never re-arm the blast. */
    fun advanceTo(target: CelebrationState): CelebrationState = if (target.ordinal > ordinal) target else this
}
