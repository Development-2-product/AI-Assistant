package com.iamode.app.domain

import com.iamode.app.domain.mail.ActionCheck
import com.iamode.app.domain.mail.ActionStateMachine
import com.iamode.app.domain.mail.Actor
import com.iamode.app.domain.mail.AttachmentInfo
import com.iamode.app.domain.mail.AutomationRuleType
import com.iamode.app.domain.mail.CalendarProposal
import com.iamode.app.domain.mail.CelebrationState
import com.iamode.app.domain.mail.CelebrationType
import com.iamode.app.domain.mail.DocumentFile
import com.iamode.app.domain.mail.DocumentRequest
import com.iamode.app.domain.mail.DocumentResolver
import com.iamode.app.domain.mail.DocumentType
import com.iamode.app.domain.mail.IdempotencyKeys
import com.iamode.app.domain.mail.MailActionEvent
import com.iamode.app.domain.mail.MailActionPlanner
import com.iamode.app.domain.mail.MailActionStatus
import com.iamode.app.domain.mail.MailActionType
import com.iamode.app.domain.mail.MailCategory
import com.iamode.app.domain.mail.MailClassification
import com.iamode.app.domain.mail.MailPolicyEngine
import com.iamode.app.domain.mail.MailPriority
import com.iamode.app.domain.mail.MailTab
import com.iamode.app.domain.mail.MailTabs
import com.iamode.app.domain.mail.MatchLabel
import com.iamode.app.domain.mail.OpportunityStatus
import com.iamode.app.domain.mail.PolicyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MailWorkflowTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    // ---------- documents ----------
    private val experienceRequest = DocumentRequest(DocumentType.WORK_EXPERIENCE, "current experience and work updates", "PDF",
        "vinnu@abc.com", null, true)

    private fun file(name: String, ageDays: Long = 10, folder: String = "Documents", size: Long = 200_000) =
        DocumentFile("content://docs/$name", name, if (name.endsWith(".pdf")) "application/pdf" else null, size, now - ageDays * day, folder)

    @Test fun `correct file is recommended`() {
        val ranked = DocumentResolver.rank(experienceRequest, listOf(
            file("Current_Experience_and_Work_Updates.pdf"), file("Resume_2026.pdf"), file("Holiday_photos.pdf"),
        ), now)
        assertEquals("Current_Experience_and_Work_Updates.pdf", ranked.first().file.name)
        assertEquals(MatchLabel.RECOMMENDED, ranked.first().label)
        assertTrue(ranked.none { it.file.name == "Holiday_photos.pdf" }) // unrelated files are not suggested
    }

    @Test fun `close candidates are all possible matches, not recommended`() {
        val ranked = DocumentResolver.rank(experienceRequest, listOf(file("Work_Experience.pdf"), file("Work_Experience_v2.pdf")), now)
        assertTrue(ranked.size == 2 && ranked.all { it.label == MatchLabel.POSSIBLE })
    }

    @Test fun `no matching file returns nothing`() {
        assertTrue(DocumentResolver.rank(experienceRequest, listOf(file("Groceries.pdf"), file("Movie.mp4")), now).isEmpty())
    }

    @Test fun `wrong format is never recommended`() {
        val ranked = DocumentResolver.rank(experienceRequest, listOf(file("Current_Work_Experience_Updates.docx")), now)
        assertTrue(ranked.single().label == MatchLabel.POSSIBLE && !ranked.single().formatMatches)
    }

    @Test fun `unsupported and dangerous files are excluded`() {
        assertTrue(DocumentResolver.rank(experienceRequest, listOf(file("Experience.exe"), file("Experience.apk")), now).isEmpty())
    }

    @Test fun `previous selection is preferred`() {
        val chosen = file("KasiCV.pdf")
        val ranked = DocumentResolver.rank(experienceRequest, listOf(chosen, file("Experience_old.pdf", ageDays = 900)), now,
            previousSelections = mapOf(chosen.uri to 2))
        assertEquals("KasiCV.pdf", ranked.first().file.name)
    }

    // ---------- policy / security ----------
    private fun check(
        type: MailActionType = MailActionType.SEND_DOCUMENT_REPLY, status: MailActionStatus = MailActionStatus.APPROVED,
        approvedAt: Long? = now - 60_000, recipient: String = "vinnu@abc.com",
        attachments: List<AttachmentInfo> = listOf(AttachmentInfo("Experience.pdf", "application/pdf", 300_000, true)),
        calendar: CalendarProposal? = null, by: Actor = Actor.USER,
    ) = ActionCheck(type, status, approvedAt, by, recipient, setOf("Vinnu@ABC.com"), "Hello", attachments, calendar)

    @Test fun `approved document send is allowed`() = assertEquals(PolicyDecision.Allowed, MailPolicyEngine.canExecute(check(), now, ist))

    @Test fun `action without approval is denied`() =
        assertTrue(MailPolicyEngine.canExecute(check(status = MailActionStatus.REVIEW_REQUIRED), now, ist) is PolicyDecision.Denied)

    @Test fun `expired approval is denied`() =
        assertTrue(MailPolicyEngine.canExecute(check(approvedAt = now - 2 * day), now, ist) is PolicyDecision.Denied)

    @Test fun `unauthorized recipient is denied`() {
        val d = MailPolicyEngine.canExecute(check(recipient = "attacker@evil.com"), now, ist)
        assertTrue(d is PolicyDecision.Denied && d.reason.contains("sender"))
    }

    @Test fun `unconfirmed attachment is denied`() = assertTrue(MailPolicyEngine.canExecute(
        check(attachments = listOf(AttachmentInfo("a.pdf", null, 10, userConfirmed = false))), now, ist) is PolicyDecision.Denied)

    @Test fun `malicious attachment is denied`() = assertTrue(MailPolicyEngine.canExecute(
        check(attachments = listOf(AttachmentInfo("cv.pdf.exe", null, 10, true))), now, ist) is PolicyDecision.Denied)

    @Test fun `oversized attachment is denied`() = assertTrue(MailPolicyEngine.canExecute(
        check(attachments = listOf(AttachmentInfo("big.pdf", null, 30L * 1024 * 1024, true))), now, ist) is PolicyDecision.Denied)

    @Test fun `automation can never send email`() =
        assertTrue(MailPolicyEngine.canExecute(check(by = Actor.AUTOMATION), now, ist) is PolicyDecision.Denied)

    // ---------- calendar ----------
    private fun cal(date: String? = "2026-09-30", start: String? = "10:30", end: String? = "11:15", tz: String? = "Asia/Kolkata",
                    text: String? = "September 30 10:30", ambiguous: Boolean = false) =
        CalendarProposal.parse("Interview: Android Developer", date, start, end, tz, null, "https://meet.google.com/x", null, null, text, ambiguous, null)

    @Test fun `complete date and time resolves in its timezone`() {
        val r = cal().resolve(ZoneId.of("UTC"))!!
        assertEquals(ist, r.start.zone)
        assertEquals(45L, java.time.Duration.between(r.start, r.end).toMinutes())
        assertTrue(!r.timezoneAssumed)
    }

    @Test fun `missing timezone uses the device zone and says so`() = assertTrue(cal(tz = null).resolve(ist)!!.timezoneAssumed)

    @Test fun `missing time or ambiguous date never resolves`() {
        assertEquals(null, cal(start = null).resolve(ist))
        assertEquals(null, cal(ambiguous = true, text = "next Friday").resolve(ist))
        assertEquals("No start time in the email", cal(start = null).ambiguityReason)
    }

    @Test fun `ambiguous event cannot be created even when approved`() = assertTrue(MailPolicyEngine.canExecute(
        check(type = MailActionType.CREATE_CALENDAR_EVENT, calendar = cal(start = null), attachments = emptyList()), now, ist) is PolicyDecision.Denied)

    @Test fun `duplicate event has the same idempotency key`() {
        val a = IdempotencyKeys.calendarEvent("m1", cal().eventHash("m1"))
        val b = IdempotencyKeys.calendarEvent("m1", cal().eventHash("m1"))
        assertEquals(a, b)
        assertTrue(a != IdempotencyKeys.calendarEvent("m1", cal(start = "11:00").eventHash("m1")))
    }

    @Test fun `interview automation needs the rule, high confidence and a complete event`() {
        val rules = setOf(AutomationRuleType.AUTO_ADD_INTERVIEWS)
        fun auto(c: CalendarProposal?, conf: String = "high", r: Set<AutomationRuleType> = rules) = MailPolicyEngine.shouldAutoExecute(
            MailActionType.CREATE_CALENDAR_EVENT, MailCategory.INTERVIEW_INVITATION, conf, c, r, ist, suspicious = false)
        assertTrue(auto(cal()))
        assertTrue(!auto(cal(), r = emptySet()))
        assertTrue(!auto(cal(), conf = "medium"))
        assertTrue(!auto(cal(tz = null)))
        assertTrue(!auto(cal(start = null)))
    }

    // ---------- state machines ----------
    @Test fun `action lifecycle only allows legal moves`() {
        assertEquals(MailActionStatus.APPROVED, ActionStateMachine.next(MailActionStatus.REVIEW_REQUIRED, MailActionEvent.APPROVE))
        assertEquals(null, ActionStateMachine.next(MailActionStatus.REVIEW_REQUIRED, MailActionEvent.START)) // no skipping approval
        assertEquals(null, ActionStateMachine.next(MailActionStatus.COMPLETED, MailActionEvent.START))      // no double send
        assertEquals(MailActionStatus.REVIEW_REQUIRED, ActionStateMachine.next(MailActionStatus.FAILED, MailActionEvent.RETRY))
        assertEquals(MailActionStatus.REJECTED, ActionStateMachine.next(MailActionStatus.REVIEW_REQUIRED, MailActionEvent.REJECT))
    }

    @Test fun `celebration state never moves backwards`() {
        assertEquals(CelebrationState.CELEBRATION_SHOWN, CelebrationState.CELEBRATION_SHOWN.advanceTo(CelebrationState.CELEBRATION_ELIGIBLE))
        assertEquals(CelebrationState.DETAILS_VIEWED, CelebrationState.CELEBRATION_SHOWN.advanceTo(CelebrationState.DETAILS_VIEWED))
    }

    @Test fun `same email and celebration share one key`() = assertEquals(
        IdempotencyKeys.celebration("m1", CelebrationType.SELECTION), IdempotencyKeys.celebration("m1", CelebrationType.SELECTION))

    // ---------- planning + tabs ----------
    private fun classification(primary: MailCategory, reply: Boolean = false, doc: Boolean = false, calendar: CalendarProposal? = null,
                               suspicious: List<String> = emptyList(), celebration: CelebrationType? = null) = MailClassification(
        "m1", primary, emptySet(), MailPriority.NORMAL, reply, doc,
        if (doc) experienceRequest else null, calendar, OpportunityStatus.NONE, suspicious, celebration, "high")

    @Test fun `pdf request plans a document send`() = assertEquals(listOf(MailActionType.SEND_DOCUMENT_REPLY),
        MailActionPlanner.plan(classification(MailCategory.DOCUMENT_REQUEST, reply = true, doc = true)).map { it.type })

    @Test fun `interview plans calendar and reply`() = assertEquals(
        listOf(MailActionType.CREATE_CALENDAR_EVENT, MailActionType.SEND_REPLY),
        MailActionPlanner.plan(classification(MailCategory.INTERVIEW_INVITATION, reply = true, calendar = cal())).map { it.type })

    @Test fun `promotions and notifications plan nothing`() {
        assertTrue(MailActionPlanner.plan(classification(MailCategory.PROMOTION, reply = true)).isEmpty())
        assertTrue(MailActionPlanner.plan(classification(MailCategory.NOTIFICATION, reply = true)).isEmpty())
    }

    @Test fun `suspicious mail never gets a send proposal or celebration`() {
        val c = classification(MailCategory.DOCUMENT_REQUEST, reply = true, doc = true, suspicious = listOf("different domain"),
            celebration = CelebrationType.SELECTION)
        assertEquals(listOf(MailActionType.REVIEW_EMAIL), MailActionPlanner.plan(c).map { it.type })
        assertEquals(null, MailActionPlanner.celebration(c))
    }

    @Test fun `tabs place mail where users expect it`() {
        val interview = MailTabs.tabsFor(MailCategory.INTERVIEW_INVITATION, emptySet(), MailPriority.HIGH, true, false, true)
        assertTrue(interview.containsAll(listOf(MailTab.INBOX, MailTab.INTERVIEWS, MailTab.OPPORTUNITIES, MailTab.CALENDAR, MailTab.IMPORTANT)))
        val promo = MailTabs.tabsFor(MailCategory.PROMOTION, emptySet(), MailPriority.LOW, false, false, false)
        assertEquals(setOf(MailTab.PROMOTIONS), promo) // promotions don't clutter the inbox
        assertEquals(setOf(MailTab.ARCHIVE), MailTabs.tabsFor(MailCategory.JOB_OFFER, emptySet(), MailPriority.HIGH, true, true, false))
    }
}

class DocumentContentTest {
    private val now = 1_790_000_000_000L
    private val request = DocumentRequest(DocumentType.WORK_EXPERIENCE, "current experience and work updates", "PDF", null, null, true)
    private fun f(name: String) = DocumentFile("content://d/$name", name, "application/pdf", 100_000, now - 5 * 86_400_000L, "Docs")

    @Test fun `badly named file is found by its content`() {
        val scan = f("Scan_0042.pdf")
        val ranked = DocumentResolver.rank(request, listOf(scan, f("Holiday.pdf")), now,
            contentText = mapOf(scan.uri to "Kasi Viswanath — Work experience. Current role: Android developer. Recent updates: shipped IA Mode."))
        assertEquals(listOf("Scan_0042.pdf"), ranked.map { it.file.name })
        assertTrue(ranked.single().contentMatch)
    }

    @Test fun `content breaks a tie between similar names`() {
        val a = f("Work_Experience.pdf"); val b = f("Work_Experience_v2.pdf")
        val ranked = DocumentResolver.rank(request, listOf(a, b), now,
            contentText = mapOf(b.uri to "Current experience and work updates, employment summary, career"))
        assertEquals("Work_Experience_v2.pdf", ranked.first().file.name)
        assertEquals(MatchLabel.RECOMMENDED, ranked.first().label)
    }

    @Test fun `drive full-text hit counts as a content match`() {
        val drive = DocumentFile("gdrive://me@gmail.com/abc", "Document 7.pdf", "application/pdf", 90_000, now, "Google Drive")
        val ranked = DocumentResolver.rank(request, listOf(drive), now, contentHits = setOf(drive.uri))
        assertTrue(ranked.single().contentMatch)
    }

    @Test fun `unrelated content adds nothing`() {
        val x = f("Scan_1.pdf")
        assertTrue(DocumentResolver.rank(request, listOf(x), now, contentText = mapOf(x.uri to "Grocery list: rice, dal, milk")).isEmpty())
    }
}

class WritingStyleTest {
    private val sent = listOf(
        "Hi Ravi,\n\nThanks for the update, I'll check the build tonight and share the APK.\n\nRegards,\nKasi Viswanath",
        "Hi team,\n\nPlease find the report attached. Call me on +91 98765 43210 if anything is unclear.\n\nRegards,\nKasi Viswanath",
        "Hi Vinnu,\n\nSure, I can join the interview on Monday at 10. Let me know the link.\n\nRegards,\nKasi Viswanath\n\nOn Mon, Sep 21, 2026 at 9:00 AM Vinnu <v@abc.com> wrote:\n> Can you join?",
        "Hello Sir,\n\nI have completed the assignment and pushed it to the repo at https://github.com/kasi/x.\n\nRegards,\nKasi Viswanath",
    )

    @Test fun `learns greeting, sign-off and length`() {
        val s = com.iamode.app.domain.mail.WritingStyleAnalyzer.analyze(sent)!!
        assertEquals("Hi", s.greeting)
        assertEquals("Regards,\nKasi Viswanath", s.signOff)
        assertTrue(s.averageWords in 10..30)
        assertEquals(4, s.sampleCount)
    }

    @Test fun `quoted replies are not learned`() {
        val s = com.iamode.app.domain.mail.WritingStyleAnalyzer.analyze(sent)!!
        assertTrue(s.samples.none { it.contains("Can you join") || it.contains("wrote:") })
    }

    @Test fun `samples never contain phone numbers, emails or links`() {
        val s = com.iamode.app.domain.mail.WritingStyleAnalyzer.analyze(sent)!!
        val all = s.samples.joinToString(" ")
        assertTrue(!all.contains("98765") && !all.contains("github.com") && !all.contains("@"))
    }

    @Test fun `needs at least three emails`() =
        assertEquals(null, com.iamode.app.domain.mail.WritingStyleAnalyzer.analyze(sent.take(2)))

    @Test fun `sender that mostly sends promotions is bulk`() {
        assertTrue(com.iamode.app.domain.mail.SenderHistory.from(mapOf("promotion" to 5, "general" to 1)).mostlyBulk)
        assertTrue(!com.iamode.app.domain.mail.SenderHistory.from(mapOf("promotion" to 1, "reply_needed" to 3)).mostlyBulk)
        assertTrue(!com.iamode.app.domain.mail.SenderHistory.from(mapOf("promotion" to 2)).mostlyBulk) // too little history
    }
}

class IndianStyleTest {
    @Test fun `learns Telugu greeting and sign-off`() {
        val sent = listOf(
            "నమస్కారం సార్,\n\nరేపు మీటింగ్‌కి వస్తాను. రిపోర్ట్ కూడా తీసుకువస్తాను.\n\nధన్యవాదాలు,\nకాశీ",
            "నమస్కారం,\n\nమీరు అడిగిన డాక్యుమెంట్ పంపిస్తున్నాను. ఏదైనా సందేహం ఉంటే చెప్పండి.\n\nధన్యవాదాలు,\nకాశీ",
            "నమస్కారం మేడమ్,\n\nఈ వారం ప్రాజెక్ట్ అప్డేట్ ఇదే. పని దాదాపు పూర్తయింది.\n\nధన్యవాదాలు,\nకాశీ",
        )
        val s = com.iamode.app.domain.mail.WritingStyleAnalyzer.analyze(sent)!!
        assertEquals("నమస్కారం", s.greeting)
        assertEquals("ధన్యవాదాలు,\nకాశీ", s.signOff)
    }

    @Test fun `learns Hindi greeting and sign-off`() {
        val sent = listOf(
            "नमस्ते सर,\n\nमैं कल की मीटिंग में आऊंगा और रिपोर्ट भी लाऊंगा।\n\nधन्यवाद,\nकाशी",
            "नमस्ते,\n\nआपके द्वारा मांगा गया दस्तावेज़ भेज रहा हूं। कोई सवाल हो तो बताइए।\n\nधन्यवाद,\nकाशी",
            "नमस्ते मैडम,\n\nइस हफ्ते का प्रोजेक्ट अपडेट यह है। काम लगभग पूरा हो गया है।\n\nधन्यवाद,\nकाशी",
        )
        val s = com.iamode.app.domain.mail.WritingStyleAnalyzer.analyze(sent)!!
        assertEquals("नमस्ते", s.greeting)
        assertEquals("धन्यवाद,\nकाशी", s.signOff)
    }
}

class MailIdsTest {
    @Test fun `outlook ids are prefixed and round-trip`() {
        val id = com.iamode.app.domain.mail.MailIds.outlook("AAMkAG=")
        assertEquals("ol:AAMkAG=", id)
        assertEquals(com.iamode.app.domain.mail.MailProviderKind.OUTLOOK, com.iamode.app.domain.mail.MailIds.providerOf(id))
        assertEquals("AAMkAG=", com.iamode.app.domain.mail.MailIds.raw(id))
        assertEquals("ol:AAMkAG=", com.iamode.app.domain.mail.MailIds.outlook(id)) // never double-prefixed
    }

    @Test fun `existing gmail ids stay gmail`() =
        assertEquals(com.iamode.app.domain.mail.MailProviderKind.GMAIL, com.iamode.app.domain.mail.MailIds.providerOf("18c2f9a0b1"))
}
