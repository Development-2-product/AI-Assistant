# IA Mode v1.7.0 — what changed and where

**How to apply:** unzip `ia-mode-v1.7-changes.zip` and copy its `ia-mode/` folder **over** your project folder
(replace when asked). Every file inside is complete, so you replace whole files and don't edit lines by hand.
Your `.git`, `.env`, `local.properties`, keystores and `google-services.json` files are untouched.
`v1.7.patch` (next to this file) shows every changed line (unified diff) if you want to review it in Git or Android Studio.

After copying: **Android Studio → File → Sync Project with Gradle Files**, then build. Backend: `pytest` (76 tests).

## New files (11)

| File | What it is |
|---|---|
| `frontend/app/src/main/java/com/iamode/app/domain/jobs/JobModels.kt` | Stages (Saved → Applied → Assessment → Interview → Selected → Offer / Accepted / Declined / Not selected / No response), event types, forward-only `StageMachine` |
| `.../domain/jobs/ApplicationMatcher.kt` | Links an email to an application: same thread → reference ID → company (email or sender domain) + role. Jaro-Winkler fuzzy match, job-platform domains |
| `.../domain/jobs/FollowUpPlanner.kt` | Daily reminders (14 days after applying, 5 after interview, max 2, deadlines, offers, 30-day no response) + funnel insights |
| `.../domain/jobs/SharedJobParser.kt` | Parses text shared from LinkedIn/Naukri/Indeed into company, role, link |
| `.../core/database/dao/JobApplicationDao.kt` | Room DAO for applications and timeline events |
| `.../data/jobs/JobTrackerRepository.kt` | Tracker logic: career email → application, manual add, stage changes, notes, follow-up proposals |
| `.../data/jobs/JobReminderWorker.kt` | Daily WorkManager job for reminders (never sends anything) |
| `.../ui/feature/jobs/JobsDesign.kt` | Stage colours, glyphs, `StageChip`, `daysAgo` |
| `.../ui/feature/jobs/ApplicationsScreen.kt` | Applications list, animated funnel, filters, Add / Track-shared-job sheet |
| `.../ui/feature/jobs/ApplicationDetailScreen.kt` | Detail: stage picker, next step, Follow up, edit details, notes, timeline |
| `frontend/app/src/test/java/com/iamode/app/domain/JobTrackerTest.kt` | 21 tests (stages, matching, reminders, funnel, shared links) |

## Modified files (32) — what changed inside each

### Backend
| File | Change |
|---|---|
| `backend/app/schemas/mail.py` | + `ApplicationStage`, `ApplicationInfo` (stage, reference_id, ats_platform, interview_round, deadline, next_step) above `class MailIntelligence`; + `application`, `language` fields in `MailIntelligence`; `ReplyDraftRequest.purpose` + `"follow_up"`, + `language` field |
| `backend/app/services/mail_rules.py` | Hindi/Telugu/Tamil patterns added to `EXPLICIT_REPLY_REQUEST`, `CREDENTIAL_REQUEST`, `URGENCY`, `RELATIVE_DATE`; + `ATS_DOMAINS`, `STAGE_FOR_CATEGORY`; new block `# ---- job application (tracker) ----` before the celebration block; rejections never celebrate |
| `backend/app/services/assistant.py` | `draft_mail_reply`: + `"follow_up"` purpose text, + `language_instruction` passed to the prompt |
| `backend/app/prompts/mail_intelligence.md` | + APPLICATION and LANGUAGE sections after OPPORTUNITY |
| `backend/app/prompts/mail_reply.md` | + `LANGUAGE: $language_instruction` line before `Purpose:` |
| `backend/tests/test_mail.py` | + 10 tests at the end (`# ---- v1.7 ...`) |

### Android — database & settings
| File | Change |
|---|---|
| `core/database/entity/Entities.kt` | + `JobApplicationEntity`, `ApplicationEventEntity` at the end |
| `core/database/IAModeDatabase.kt` | `version = 6`; the 2 entities added to the list; `jobApplicationDao()`; + `MIGRATION_5_6` at the end of the companion |
| `core/di/AppModule.kt` | `.addMigrations(... , MIGRATION_5_6)`; + `jobApplicationDao` provider |
| `domain/model/UserSettings.kt` | + `mailReplyLanguage = "auto"`, `jobReminders = true` |
| `core/datastore/PreferenceKeys.kt` | + `MAIL_REPLY_LANGUAGE`, `JOB_REMINDERS` |
| `data/repository/SettingsRepositoryImpl.kt` | read/write the two new settings |
| `core/network/dto/ApiDtos.kt` | + `ApplicationDto`; `MailIntelligenceDto` + `application`, `language`; `ReplyDraftRequestDto` + `language` |

### Android — mail workflow
| File | Change |
|---|---|
| `domain/mail/MailModels.kt` | `MailActionType` + `SEND_FOLLOW_UP` |
| `domain/mail/MailPolicyEngine.kt` | `SEND_FOLLOW_UP` checked like every send (approval, expiry, recipient lock) |
| `domain/mail/WritingStyle.kt` | Greeting/sign-off regexes know Telugu, Hindi, Tamil, Kannada, Malayalam |
| `data/mail/MailIntelligenceRepository.kt` | constructor + `tracker`; in `classify()` a `// Job tracker:` block before `if (cal != null)`; `draftReply` sends `language` and maps `SEND_FOLLOW_UP → "follow_up"` |
| `data/mail/MailActionExecutor.kt` | constructor + `tracker`; `SEND_FOLLOW_UP` sends like a reply; after success calls `tracker.onFollowUpSent` |
| `data/mail/DocumentTextExtractor.kt` | + Devanagari recognizer; OCR falls back to it when Latin finds little text |

### Android — UI, navigation, app
| File | Change |
|---|---|
| `ui/navigation/AppNavHost.kt` | `DeepLink.Application`, `DeepLink.SharedJob` (Android share sheet); routes `APPLICATIONS`, `APPLICATION`; wiring from Mail and Opportunities |
| `ui/feature/mail/MailHomeScreen.kt` | Work icon → Applications; label for `SEND_FOLLOW_UP` |
| `ui/feature/mail/MailActionsScreen.kt` | card glyph/title for `SEND_FOLLOW_UP` |
| `ui/feature/mail/OpportunitiesScreen.kt` | "All applications" button in the top bar |
| `ui/feature/settings/EmailIntelligenceSection.kt` | "Reply language" picker and "Job tracker → Follow-up reminders" switch |
| `MainActivity.kt` | + `EXTRA_APPLICATION_ID` |
| `core/notifications/AppNotifier.kt` | + `jobReminder(...)` notification (opens the application) |
| `IAModeApp.kt` | schedules `JobReminderWorker` with the mail sync |
| `AndroidManifest.xml` | `MainActivity`: share-sheet `<intent-filter>` (SEND text/plain); OCR meta-data `ocr,ocr_devanagari` |
| `app/build.gradle.kts` | versionCode 8 / 1.7.0; + Devanagari OCR dependency |
| `gradle/libs.versions.toml` | + `mlkitDevanagariUnbundled`, `mlkit-text-recognition-devanagari` |
| `test/.../MailWorkflowTest.kt` | + `IndianStyleTest` (Telugu + Hindi) |
| `CHANGELOG.md` | 1.7.0 entry |

## Database
Version **6**. `MIGRATION_5_6` creates `job_applications` and `application_events` (existing data is untouched).
Verified here by replaying the real v3 schema through migrations 3→4→5→6 in SQLite: 21 tables, 0 mismatches.

## Tested here
- Backend: **76 tests pass** (10 new).
- Core app logic (pure Kotlin): **98 tests pass** (23 new: tracker, shared links, Indian-language style).
- Android screens/data layer: static checks only (**not compiled here**, no Android SDK). Build in Android Studio.

## Test on your phone
1. Update over v1.6: the app opens and your mail/data is still there (migration 5→6).
2. Email yourself "Thank you for applying for Android Developer at ABC" → Mail → Work icon → it appears as Applied.
3. Reply in the same thread with an interview invite → the same application moves to Interview (not a duplicate).
4. Share a LinkedIn job to IA Mode → "Track this job" pre-filled → Save.
5. Application detail → Follow up → review the draft → Approve & Send → timeline shows "Follow-up sent".
6. Email in Telugu with "రేపు 10 గంటలకు ఇంటర్వ్యూ" → the calendar asks you to confirm the date.
7. Settings → Reply language → తెలుగు → drafts come in Telugu.
