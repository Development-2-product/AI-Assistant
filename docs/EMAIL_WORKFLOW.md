# Email workflow (v1.4)

IA Mode understands incoming Gmail, proposes actions, and executes them only after you approve.

```
Gmail inbox ─► MailSyncWorker ─► POST /v1/mail/intelligence (Gemini + deterministic rules)
                                        │  typed proposal only, the server has no execution tools
                                        ▼
              phone: MailIntelligenceRepository ─► store metadata, opportunity, calendar extraction
                                        │           plan proposals (MailActionPlanner), celebration (once)
                                        ▼
              ⚡ AI Actions / Mail screens ─► user reviews, picks file, edits reply, confirms date
                                        ▼
              MailActionExecutor ─► MailPolicyEngine.canExecute ─► atomic APPROVED→EXECUTING
                                        ▼                              ─► Gmail send / Calendar insert
                                   audit log (mail_audit)
```

## Where each rule lives

| Concern | Code |
|---|---|
| Categories, tabs, no duplicate labels | `domain/mail/MailModels.kt` (`MailTabs`), `backend/app/services/mail_rules.py` |
| No-reply logic (sender + Reply-To + Auto-Submitted + Precedence + intent) | `mail_rules.apply` |
| Promotions (List-Unsubscribe, Gmail category, marketing never celebrates) | `mail_rules.apply` |
| Suspicious (concrete reasons only) | `mail_rules.suspicious_signals` |
| Never invent dates (relative → ambiguous, guessed date dropped) | `mail_rules._validate_calendar`, `CalendarProposal.parse/resolve` |
| Document ranking (on the phone, filenames/format/recency/history) | `domain/mail/DocumentResolver.kt` |
| Authorized file sources only (SAF folders, IA Mode folder, picker grants) | `data/mail/DocumentSources.kt` |
| Policy: approval, 24 h expiry, recipient = sender/Reply-To, confirmed + safe attachments, resolved event | `domain/mail/MailPolicyEngine.kt` |
| Automation (opt-in, calendar only, high confidence, complete event with timezone) | `MailPolicyEngine.shouldAutoExecute` |
| State machines (actions, celebration journey) | `domain/mail/MailStateMachines.kt` |
| Idempotency | `IdempotencyKeys` + own `Message-ID` checked with `rfc822msgid:` before resend, calendar `UID_2445` checked before insert, DB compare-and-set transitions, `mail_celebrations` primary key + `markShown` CAS |
| Audit (who, when requested/approved/executed, attachment names, event id, error; no content) | `mail_audit`, `MailActionExecutor.audit` |
| 3D celebration | `ui/celebration/ParticleEngine3D.kt`, `CelebrationOverlay.kt` |

## Privacy

Email bodies are sent to the IA Mode server only to classify or draft, and are not stored on the phone or server.
The phone stores subject, sender, the AI summary and extracted fields for 90 days. File contents never leave the
phone except as the attachment you approved.

## Gmail, Drive and documents (v1.5)

- **Gmail scope:** only `gmail.modify` (read, send, labels, archive, mark read, move; no permanent delete).
  Accounts connected with v1.4 or earlier are asked to reconnect once.
- **Gmail-side actions:** Archive / Move to Inbox mirror to Gmail (Settings › Mirror to Gmail), Mark as read,
  Move to label (existing or new). Optional "IA Mode/…" labels (off by default; adds labels, never moves).
  Muting a sender archives their existing and future mail. Every change is written to the audit log.
- **Content ranking:** first 2 pages of PDFs (pdfbox-android), .docx text (built-in zip), .txt/.md; on the phone,
  in memory only, max 40 files per request, 1.5 s per file.
- **Google Drive (opt-in):** Settings › Email intelligence › Connect Google Drive asks for `drive.readonly` separately.
  Search uses Drive name + full-text search; a file downloads only when attached or previewed; Google Docs export as PDF.
- **Multiple attachments:** up to 5 files, 18 MB total; changing attachments clears approval and offers to rewrite the reply.

### Google Cloud consent screen (project ia-mode-509514)

Keep only: `.../auth/gmail.modify` and, if you use Drive search, `.../auth/drive.readonly`. Enable the **Drive API**.
Delete every other Gmail scope (mail.google.com, messages.delete, insert, settings.*, addons.*, metadata, compose,
drafts, labels*, notifications, readonly, send). Requesting unused scopes causes verification rejection.

### v1.6 additions

- **OCR:** PDFs without a text layer (first 2 pages) and JPG/PNG photos, max 10 per request, 4 s each.
- **Locked PDFs:** badge + send warning; IA Mode never asks for the password.
- **Writing style:** `WritingStyleAnalyzer` (tested) on up to 40 sent emails from the last 180 days; weekly refresh;
  stored encrypted in `writing_style`; only the profile + ≤3 masked excerpts (no emails/links/numbers) go to the server
  while drafting.
- **Sender history:** counts per sender from `mail_intelligence`; server rule: ≥3 emails and ≥75 % bulk → promotion,
  unless it's career mail or explicitly asks for a reply.
- **Real-time:** Gmail push (Pub/Sub + server DB + FCM) is intentionally not used; the Gmail app's own push notification
  triggers IA Mode within seconds, with a 15-minute fallback.

### Still outside the app

- Google OAuth verification + CASA assessment for restricted scopes before public release (100 test users until then).
- Gmail filters (server-side mute) would need `gmail.settings.basic`; not used on purpose.

## On-device test plan (required before release)

These scenarios need a real phone, Gmail account and backend. They were not run in the build environment.

**A. PDF request.** From another account send: "Please send your current experience and work updates as PDF."
Add a folder containing `Current_Experience_and_Work_Updates.pdf` in Settings › Email intelligence.
Expect: Mail › Documents shows the email → "Choose document & reply" → the file is marked Recommended →
Preview renders page 1 → reply is drafted mentioning the attachment → edit → Approve & Send → the recipient gets
the PDF in the same thread → AI Actions › Activity shows SUCCESS with the file name. Tap Approve again: "Already done".

**B. Job selection.** Send: "Congratulations! You have been selected for the Software Engineer position at ABC Technologies."
Expect: with the app open, the gold blast plays once; Opportunities › Selected shows the record. Reopen the app and the Mail
screen: no second blast. Email detail › "Replay celebration" plays it again. With the app closed: a notification, and
the blast plays on opening. Turn on Settings › Accessibility › Remove animations: a static card instead.

**C. Interview.** Send: "You have been selected for an interview for the Android Developer role. September 30, 10:30 AM, Google Meet
https://meet.google.com/abc-defg-hij". Expect: 🎯 in Interviews + AI Actions → preview with date/time → allow calendar →
Add to Calendar → event with 1-day and 1-hour reminders in Google Calendar. Tap again: "Already in your calendar".
Variant: "next Friday at 2 PM" → "Confirmation needed", no date prefilled, Add is disabled until you pick a date.

**v1.5 checks.** Reconnect Gmail once (new scope). Archive in IA Mode → gone from Gmail inbox; Move to Inbox → back.
Move to a new label → label created in Gmail. Rename a PDF to `Scan_1.pdf` → still ranked by content. Connect Drive,
put an experience PDF there → appears with "Google Drive · matches its content"; attach it and send. Tick 2 files → both arrive.

**v1.6 checks.** Scan a paper document to PDF (no text layer) → found by content. Put a password PDF in a folder → 🔒.
Settings › Writing style › Learn now → greeting/sign-off shown; new drafts use them. Open a 10-page PDF in the viewer →
scroll, pinch, double-tap. Swipe a mail card left → archived with Undo. Pull down on Mail → sync runs.

**Security spot checks.** Approve, wait 24 h, execute → blocked ("Approval expired"). Edit the draft after approving →
approval is cleared. Pick an `.apk` → refused. A phishing-style mail (credential request + different Reply-To domain) →
Suspicious tab with reasons, only "Review carefully", no send proposal, no celebration.

**Room migration.** Install v1.3, use it, then install v1.4 over it: the app must open with old data intact
(the migration was verified against the v3 schema with SQLite in the build environment, but not with Room on a device).
