package com.iamode.app.domain.jobs

/** Where an application stands. [order] only ever increases from email evidence. */
enum class ApplicationStage(val label: String, val order: Int, val terminal: Boolean) {
    SAVED("Saved", 0, false),
    APPLIED("Applied", 1, false),
    ASSESSMENT("Assessment", 2, false),
    INTERVIEW("Interview", 3, false),
    SELECTED("Selected", 4, false),
    OFFER("Offer", 5, false),
    ACCEPTED("Accepted", 6, true),
    DECLINED("Declined", 6, true),
    REJECTED("Not selected", 6, true),
    GHOSTED("No response", 6, true);

    val active: Boolean get() = !terminal && this != SAVED

    companion object {
        /** Stage names used by the backend's mail intelligence ("application.stage" / opportunity status). */
        fun fromApi(v: String?): ApplicationStage? = when (v?.lowercase()) {
            "applied" -> APPLIED
            "assessment" -> ASSESSMENT
            "interview" -> INTERVIEW
            "selected" -> SELECTED
            "offer" -> OFFER
            "rejected" -> REJECTED
            else -> null
        }
    }
}

enum class ApplicationEventType(val label: String) {
    CREATED("Tracking started"), APPLIED("Application received"), ASSESSMENT("Assessment"), INTERVIEW("Interview"),
    SELECTED("Selected"), OFFER("Offer received"), REJECTED("Not selected"), GHOSTED("Marked as no response"),
    FOLLOW_UP_SENT("Follow-up sent"), STAGE_CHANGED("Stage changed"), NOTE("Note");

    companion object {
        fun forStage(s: ApplicationStage): ApplicationEventType = when (s) {
            ApplicationStage.APPLIED -> APPLIED
            ApplicationStage.ASSESSMENT -> ASSESSMENT
            ApplicationStage.INTERVIEW -> INTERVIEW
            ApplicationStage.SELECTED -> SELECTED
            ApplicationStage.OFFER -> OFFER
            ApplicationStage.REJECTED -> REJECTED
            ApplicationStage.GHOSTED -> GHOSTED
            else -> STAGE_CHANGED
        }
    }
}

enum class ApplicationSource { EMAIL, MANUAL, SHARED_LINK }

object StageMachine {
    /**
     * Email evidence only moves an application forward, so re-synced or out-of-order mail
     * ("application received" arriving after the interview invite) never moves it back.
     * A rejection ends any active application. New activity revives one marked "no response".
     * User choices (manual stage changes) are applied directly and are not handled here.
     */
    fun fromEvidence(current: ApplicationStage, evidence: ApplicationStage): ApplicationStage = when {
        current == ApplicationStage.GHOSTED -> evidence
        current.terminal -> current
        evidence == ApplicationStage.REJECTED -> ApplicationStage.REJECTED
        evidence.terminal -> current
        evidence.order > current.order -> evidence
        else -> current
    }
}

/** What IA Mode knows about one tracked application (for matching and planning). */
data class TrackedApplication(
    val id: String,
    val company: String?,
    val role: String?,
    val stage: ApplicationStage,
    val lastActivityAt: Long,
    val threadIds: Set<String>,
    val senderDomains: Set<String>,
    val referenceId: String?,
)

/** What one career email says. */
data class ApplicationEvidence(
    val threadId: String,
    val fromAddress: String,
    val company: String?,
    val role: String?,
    val referenceId: String?,
    val stage: ApplicationStage?,
)
