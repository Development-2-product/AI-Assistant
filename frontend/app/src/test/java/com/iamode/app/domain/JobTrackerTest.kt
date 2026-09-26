package com.iamode.app.domain

import com.iamode.app.domain.jobs.ApplicationEvidence
import com.iamode.app.domain.jobs.ApplicationInsights
import com.iamode.app.domain.jobs.ApplicationMatcher
import com.iamode.app.domain.jobs.ApplicationStage
import com.iamode.app.domain.jobs.FollowUpPlanner
import com.iamode.app.domain.jobs.InsightInput
import com.iamode.app.domain.jobs.MatchResult
import com.iamode.app.domain.jobs.PlannerInput
import com.iamode.app.domain.jobs.ReminderType
import com.iamode.app.domain.jobs.StageMachine
import com.iamode.app.domain.jobs.TrackedApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTrackerTest {
    private val day = FollowUpPlanner.DAY
    private val now = 1_790_000_000_000L

    private fun app(id: String, company: String?, role: String?, stage: ApplicationStage = ApplicationStage.APPLIED,
                    threads: Set<String> = emptySet(), domains: Set<String> = emptySet(), ref: String? = null) =
        TrackedApplication(id, company, role, stage, now, threads, domains, ref)

    private fun ev(from: String, company: String? = null, role: String? = null, thread: String = "t-new", ref: String? = null,
                   stage: ApplicationStage? = ApplicationStage.INTERVIEW) = ApplicationEvidence(thread, from, company, role, ref, stage)

    // ---------- stages ----------
    @Test fun `evidence only moves forward`() {
        assertEquals(ApplicationStage.INTERVIEW, StageMachine.fromEvidence(ApplicationStage.INTERVIEW, ApplicationStage.APPLIED))
        assertEquals(ApplicationStage.OFFER, StageMachine.fromEvidence(ApplicationStage.INTERVIEW, ApplicationStage.OFFER))
    }

    @Test fun `rejection ends an active application, and nothing reopens a finished one`() {
        assertEquals(ApplicationStage.REJECTED, StageMachine.fromEvidence(ApplicationStage.INTERVIEW, ApplicationStage.REJECTED))
        assertEquals(ApplicationStage.REJECTED, StageMachine.fromEvidence(ApplicationStage.REJECTED, ApplicationStage.OFFER))
    }

    @Test fun `new activity revives an application marked no response`() =
        assertEquals(ApplicationStage.INTERVIEW, StageMachine.fromEvidence(ApplicationStage.GHOSTED, ApplicationStage.INTERVIEW))

    // ---------- matching ----------
    @Test fun `same thread always matches`() {
        val r = ApplicationMatcher.match(ev("x@whatever.com", thread = "t1"), listOf(app("a", "ABC", "Dev", threads = setOf("t1"))))
        assertEquals(MatchResult.Existing("a", "same email thread"), r)
    }

    @Test fun `company from sender domain matches the company name`() {
        assertEquals("abc", ApplicationMatcher.companyKeyFromDomain("careers@careers.abc-tech.com"))
        assertEquals("abc", ApplicationMatcher.normalizeCompany("ABC Technologies Pvt. Ltd."))
        val r = ApplicationMatcher.match(ev("hr@abc-tech.com"), listOf(app("a", "ABC Technologies", "Android Developer")))
        assertTrue(r is MatchResult.Existing && r.applicationId == "a")
    }

    @Test fun `platform senders rely on the extracted company and role`() {
        assertEquals(null, ApplicationMatcher.companyKeyFromDomain("no-reply@myworkday.com"))
        val apps = listOf(app("a", "Infosys", "Java Developer"), app("b", "Wipro", "Java Developer"))
        val r = ApplicationMatcher.match(ev("noreply@myworkday.com", company = "Wipro Limited", role = "Java Developer"), apps)
        assertEquals("b", (r as MatchResult.Existing).applicationId)
    }

    @Test fun `different role at the same company is a new application`() {
        val r = ApplicationMatcher.match(ev("hr@abc.com", company = "ABC", role = "Data Analyst"), listOf(app("a", "ABC", "Android Developer")))
        assertEquals(MatchResult.New, r)
    }

    @Test fun `two roles at one company with no role in the email is ambiguous`() {
        val apps = listOf(app("a", "ABC", "Android Developer"), app("b", "ABC", "Backend Developer"))
        assertTrue(ApplicationMatcher.match(ev("hr@abc.com"), apps) is MatchResult.Ambiguous)
    }

    @Test fun `role abbreviations still match`() {
        val r = ApplicationMatcher.match(ev("hr@abc.com", role = "Sr. Android Dev"), listOf(app("a", "ABC", "Senior Android Developer")))
        assertTrue(r is MatchResult.Existing)
    }

    @Test fun `reference id matches across platforms`() {
        val r = ApplicationMatcher.match(ev("jobs@greenhouse.io", company = "ABC", ref = "REQ-4521"),
            listOf(app("a", "ABC", "Dev", ref = "req-4521"), app("b", "ABC", "QA")))
        assertEquals(MatchResult.Existing("a", "same reference ID"), r)
    }

    @Test fun `similar but different companies do not match`() {
        assertEquals(MatchResult.New, ApplicationMatcher.match(ev("hr@tcs.com", company = "TCS"), listOf(app("a", "TCP Systems", "Dev"))))
    }

    @Test fun `nothing to go on is untrackable`() =
        assertEquals(MatchResult.Untrackable, ApplicationMatcher.match(ev("jobs@naukri.com"), emptyList()))

    // ---------- reminders ----------
    private fun input(stage: ApplicationStage, idleDays: Int, next: Long? = null, followUps: Int = 0, lastFollowUpDays: Int? = null) =
        PlannerInput("a", stage, now - idleDays * day, next, lastFollowUpDays?.let { now - it * day }, followUps)

    @Test fun `follow up after 14 quiet days, not before`() {
        assertEquals(ReminderType.FOLLOW_UP_APPLICATION, FollowUpPlanner.plan(listOf(input(ApplicationStage.APPLIED, 14)), now).single().type)
        assertTrue(FollowUpPlanner.plan(listOf(input(ApplicationStage.APPLIED, 13)), now).isEmpty())
    }

    @Test fun `follow ups are capped and spaced`() {
        assertTrue(FollowUpPlanner.plan(listOf(input(ApplicationStage.APPLIED, 20, followUps = 2)), now).isEmpty())
        assertTrue(FollowUpPlanner.plan(listOf(input(ApplicationStage.APPLIED, 20, followUps = 1, lastFollowUpDays = 3)), now).isEmpty())
    }

    @Test fun `interview follow up after 5 days`() = assertEquals(ReminderType.FOLLOW_UP_INTERVIEW,
        FollowUpPlanner.plan(listOf(input(ApplicationStage.INTERVIEW, 5)), now).single().type)

    @Test fun `deadline within a day wins over follow up`() = assertEquals(ReminderType.DEADLINE_SOON,
        FollowUpPlanner.plan(listOf(input(ApplicationStage.ASSESSMENT, 20, next = now + 6 * 3_600_000L)), now).single().type)

    @Test fun `thirty silent days suggests no response, finished ones are left alone`() {
        assertEquals(ReminderType.MARK_GHOSTED, FollowUpPlanner.plan(listOf(input(ApplicationStage.APPLIED, 31)), now).single().type)
        assertTrue(FollowUpPlanner.plan(listOf(input(ApplicationStage.REJECTED, 90), input(ApplicationStage.SAVED, 90)), now).isEmpty())
    }

    // ---------- insights ----------
    @Test fun `funnel counts reached stages and response rate`() {
        val f = ApplicationInsights.funnel(listOf(
            InsightInput(ApplicationStage.OFFER, ApplicationStage.OFFER, now - 20 * day, now - 18 * day),
            InsightInput(ApplicationStage.INTERVIEW, ApplicationStage.REJECTED, now - 20 * day, now - 16 * day),
            InsightInput(ApplicationStage.APPLIED, ApplicationStage.GHOSTED, now - 40 * day, null),
            InsightInput(ApplicationStage.APPLIED, ApplicationStage.APPLIED, now - 2 * day, null),
            InsightInput(ApplicationStage.SAVED, ApplicationStage.SAVED, null, null),
        ))
        assertEquals(4, f.total); assertEquals(4, f.applied); assertEquals(2, f.interview); assertEquals(1, f.offer)
        assertEquals(1, f.rejected); assertEquals(1, f.noResponse); assertEquals(50, f.responseRatePercent)
        assertEquals(3.0, f.averageDaysToResponse!!, 0.001)
    }
}

class SharedJobTest {
    @Test fun `linkedin share text`() {
        val j = com.iamode.app.domain.jobs.SharedJobParser.parse(
            "Check out this job at ABC Technologies: Android Developer\nhttps://www.linkedin.com/jobs/view/123456")
        assertEquals("ABC Technologies", j.company); assertEquals("Android Developer", j.role); assertEquals("linkedin", j.source)
    }

    @Test fun `role at company with naukri link`() {
        val j = com.iamode.app.domain.jobs.SharedJobParser.parse("Java Developer at Infosys https://www.naukri.com/job-listings-123")
        assertEquals("Infosys", j.company); assertEquals("Java Developer", j.role); assertEquals("naukri", j.source)
    }

    @Test fun `only a link keeps the url and leaves the rest for the user`() {
        val j = com.iamode.app.domain.jobs.SharedJobParser.parse("https://careers.abc.com/jobs/42")
        assertEquals("https://careers.abc.com/jobs/42", j.url); assertEquals(null, j.company)
    }
}
