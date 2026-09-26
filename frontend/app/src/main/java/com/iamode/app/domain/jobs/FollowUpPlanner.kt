package com.iamode.app.domain.jobs

enum class ReminderType(val title: String) {
    FOLLOW_UP_APPLICATION("No reply yet. Send a polite follow-up?"),
    FOLLOW_UP_INTERVIEW("It's been a few days since your interview. Follow up?"),
    DEADLINE_SOON("Deadline within 24 hours"),
    OFFER_DEADLINE("Offer response due soon"),
    MARK_GHOSTED("No response for 30 days"),
}

data class PlannerInput(
    val id: String,
    val stage: ApplicationStage,
    val lastActivityAt: Long,
    val nextStepAt: Long?,
    val lastFollowUpAt: Long?,
    val followUpsSent: Int,
)

data class Reminder(val applicationId: String, val type: ReminderType)

/** Daily: what deserves a nudge. Every suggestion still needs the user's approval to act. */
object FollowUpPlanner {
    const val DAY = 86_400_000L
    const val FOLLOW_UP_AFTER_APPLY_DAYS = 14
    const val FOLLOW_UP_AFTER_INTERVIEW_DAYS = 5
    const val GHOSTED_AFTER_DAYS = 30
    const val MAX_FOLLOW_UPS = 2

    fun plan(apps: List<PlannerInput>, now: Long): List<Reminder> = apps.mapNotNull { a ->
        val idle = (now - a.lastActivityAt) / DAY
        val sinceFollowUp = a.lastFollowUpAt?.let { (now - it) / DAY }
        val canFollowUp = a.followUpsSent < MAX_FOLLOW_UPS && (sinceFollowUp == null || sinceFollowUp >= FOLLOW_UP_AFTER_APPLY_DAYS)
        val dueIn = a.nextStepAt?.let { it - now }
        when {
            a.stage.terminal || a.stage == ApplicationStage.SAVED -> null
            a.stage == ApplicationStage.OFFER && dueIn != null && dueIn in 0..(2 * DAY) -> Reminder(a.id, ReminderType.OFFER_DEADLINE)
            (a.stage == ApplicationStage.ASSESSMENT || a.stage == ApplicationStage.INTERVIEW) && dueIn != null && dueIn in 0..DAY ->
                Reminder(a.id, ReminderType.DEADLINE_SOON)
            a.stage.order <= ApplicationStage.INTERVIEW.order && idle >= GHOSTED_AFTER_DAYS -> Reminder(a.id, ReminderType.MARK_GHOSTED)
            a.stage == ApplicationStage.INTERVIEW && idle >= FOLLOW_UP_AFTER_INTERVIEW_DAYS && canFollowUp ->
                Reminder(a.id, ReminderType.FOLLOW_UP_INTERVIEW)
            a.stage == ApplicationStage.APPLIED && idle >= FOLLOW_UP_AFTER_APPLY_DAYS && canFollowUp ->
                Reminder(a.id, ReminderType.FOLLOW_UP_APPLICATION)
            else -> null
        }
    }
}

data class InsightInput(val maxStage: ApplicationStage, val stage: ApplicationStage, val appliedAt: Long?, val firstResponseAt: Long?)

data class Funnel(
    val total: Int, val applied: Int, val assessment: Int, val interview: Int, val offer: Int,
    val rejected: Int, val noResponse: Int, val responseRatePercent: Int, val averageDaysToResponse: Double?,
)

object ApplicationInsights {
    /** "Reached" counts: an application that got to Interview also counts toward Applied and Assessment. */
    fun funnel(apps: List<InsightInput>): Funnel {
        val tracked = apps.filter { it.maxStage != ApplicationStage.SAVED }
        fun reached(s: ApplicationStage) = tracked.count { it.maxStage.order >= s.order }
        val responded = tracked.filter { it.appliedAt != null && it.firstResponseAt != null && it.firstResponseAt >= it.appliedAt }
        val base = tracked.count { it.appliedAt != null }
        return Funnel(
            total = tracked.size,
            applied = reached(ApplicationStage.APPLIED),
            assessment = reached(ApplicationStage.ASSESSMENT),
            interview = reached(ApplicationStage.INTERVIEW),
            offer = reached(ApplicationStage.OFFER),
            rejected = tracked.count { it.stage == ApplicationStage.REJECTED },
            noResponse = tracked.count { it.stage == ApplicationStage.GHOSTED },
            responseRatePercent = if (base == 0) 0 else responded.size * 100 / base,
            averageDaysToResponse = responded.takeIf { it.isNotEmpty() }
                ?.map { (it.firstResponseAt!! - it.appliedAt!!) / FollowUpPlanner.DAY.toDouble() }?.average(),
        )
    }
}
