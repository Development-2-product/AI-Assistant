# Changelog

## 1.9.0 (app in Telugu and Hindi)

- The whole app is available in English, తెలుగు and हिन्दी: 602 texts across screens, notifications and reminders,
  including tabs, stages, categories, permissions and celebration headlines.
- Settings › App language (System default / English / తెలుగు / हिन्दी). Android 13+ also shows it in system settings.
- Standard Android string resources (values, values-te, values-hi) with a `tr()` helper that falls back to English.
- tools/i18n: extractor (wraps new texts safely) and generator (resources + lookup map + placeholder checks).

## 1.8.0 (Outlook / Microsoft 365)

- Connect Outlook.com, Hotmail and work/school accounts (MSAL, several accounts, silent refresh, reconnect prompts).
- The whole email workflow works for Outlook: understanding, Documents (reply with attachments, upload sessions for
  large files), interviews and calendar, Opportunities, job tracker, follow-ups, AI Actions, celebrations, style learning.
- Outlook mailbox actions: archive / back to Inbox, mark read, move to folder, IA Mode categories (opt-in). All audited.
- Never sends twice: the reply draft ID is saved before sending and checked on retry. Immutable Graph IDs throughout.
- Near-instant sync when the Outlook app posts a notification. "Open in Outlook" on email details.
- Configured from local.properties (client ID + per-build signature hashes). Database v7 (`outlook_accounts`).

## 1.7.0 (job-application tracker, Indian-language email)

- Job-application tracker: career emails create and update applications (applied → assessment → interview → selected →
  offer / not selected / no response). Matching by thread, reference ID, company (from email or sender domain) and role;
  stages only move forward from email. Funnel insights, timeline, notes, manual add, share a job from LinkedIn/Naukri.
- Follow-ups: daily reminders (14 days after applying, 5 after an interview, max 2), deadline and offer reminders,
  "no response" after 30 days. Follow-up emails are drafted and go through review → approval → send.
- Indian languages in email: safety rules understand Hindi/Telugu/Tamil relative dates, reply requests and OTP scams;
  drafts reply in the email's language and script (or a chosen language); style learning knows Indian greetings;
  Hindi (Devanagari) OCR.
- Database v6. Backend: application info + language in /v1/mail/intelligence; follow_up purpose + language in /v1/mail/reply-draft.

## 1.6.0 (smarter documents, your writing style, premium motion)

- OCR for scanned PDFs and photos (ML Kit, on-device, model via Play services).
- Password-protected PDFs are detected (🔒 badge + warning); owner-password-only PDFs are now read.
- Google Drive search includes shared drives (falls back to My Drive).
- Replies in your writing style: greeting, sign-off, length, tone and masked excerpts learned on the phone from
  your sent mail (Settings › Writing style: learn, relearn, forget). Style samples are fenced against injection.
- Sender history: senders that mostly send promotions/notifications are treated as promotions (never career mail).
- Full document viewer: every PDF page, pinch and double-tap zoom, page indicator, image/Word/text previews.
- Premium motion: shared-element card→detail, press physics, staggered entrances, shimmer skeletons,
  pull-to-refresh on the real sync job, swipe-to-archive with haptic + undo, rolling counters, animated success check.
- Sync every 15 minutes (plus near-instant via the Gmail app's notifications). Database v5.

## 1.5.0 (Gmail actions, Drive, content ranking)

- Gmail uses one scope, `gmail.modify` (replaces gmail.readonly + gmail.send). Existing accounts reconnect once.
- Archive / Move to Inbox mirror to Gmail; Mark as read; Move to label (existing or new); optional "IA Mode/…" labels;
  muting archives the sender's mail. All audited.
- Document ranking reads file content on the phone (PDF first pages via pdfbox-android, .docx, .txt).
- Google Drive as an opt-in document source (drive.readonly, separate consent): name + full-text search,
  download only on attach/preview, Google Docs exported as PDF.
- Multiple attachments (up to 5, 18 MB total).

## 1.4.0 (email workflow)

- Email intelligence: every inbox email (last 7 days, then new mail) is classified into Important, Reply Needed,
  Documents, Interviews, Opportunities, Calendar, Promotions, Notifications, No Reply, Suspicious and Archive,
  independent of IA Mode being on. Deterministic rules make the AI's answer safe (no-reply, promotions,
  concrete suspicious reasons, never-invented dates).
- Document requests: on-device ranking of files from folders you authorize; preview; native file picker; editable
  AI reply; Approve & Send with the attachment (Gmail upload endpoint).
- Interviews: calendar preview with ambiguity handling, date/time pickers, reminders, calendar choice; optional
  automation rule for complete, high-confidence interview events.
- Opportunities: Selected, Interviews, Job Offers, Applications, Recruiters.
- ⚡ AI Actions center with activity (audit) log. Policy engine, 24 h approval expiry, recipient lock, attachment safety,
  idempotent send (Message-ID check) and calendar insert (UID check).
- 3D celebration: custom perspective particle engine on Compose Canvas, 5 variants, once per email, replay,
  reduced-motion and low-end fallbacks, pauses in background, haptics, optional sound.
- Database v4 (migration from v3). Backend: /v1/mail/intelligence (extended), /v1/mail/reply-draft.
- Fixes: mail classification no longer delays chat replies; domain layer no longer depends on data layer
  (PipelineTest compiles again); backend tests no longer read your real .env; JVM crash dumps ignored; Gradle heap 2 GB.


## v1.3.0: reliability and diagnostics

### Fixed
- **"IA Mode couldn't reach the AI" on real phones.** The server address was built into the APK: a home-Wi-Fi
  IP for debug builds, and a placeholder that could never work for release builds. The address now comes from
  `local.properties` and **can be changed inside the app** without rebuilding.
- **Sign-in mismatches** (app sends a dev token, server expects Firebase) are now detected and explained,
  instead of failing silently.
- Debug builds can reach a PC server on any Wi-Fi IP. Release builds allow HTTPS only.
- Gmail errors (disabled API, missing test user, expired access, failed sends) were silently ignored. They're
  now recorded and shown with a fix.

### New
- **Connection check** (Settings › Connection): tests the phone's permissions, internet, server, sign-in, each
  Gemini model and each Gmail account, with a one-line fix for every failure. It also has a copyable report,
  a "Check Gmail now" button and a Recent activity log.
- A red home-screen banner and a "Find out why" link on conversations whenever the AI can't be reached.
- Backend: `GET /v1/auth/check`, `POST /v1/diagnostics/ai`, specific error messages for every sign-in failure,
  Google's exact error in AI failures, and a startup log line showing the configuration (never the key).

See **TROUBLESHOOTING.md** for the three server setups.


## v1.2.0

### New
- **More chat apps:** WhatsApp Business, Telegram (official app, Telegram Web build and Telegram X) and Instagram
  direct messages, read and answered the same way as WhatsApp. Turn each on or off in **Settings › Apps**.
  Instagram likes, follows and other non-chat notifications are ignored, because only real chats have a Reply button.
- **Group chats:** IA Mode replies only when someone mentions you (your first name, `@name`, or other names you
  add in **Settings › Group chats**). The unread messages before the mention are kept as context, so the reply
  makes sense. Every group reply needs your approval, even on autopilot.
- **Automatic on/off** (**Settings › Turn on automatically**):
  - When you start driving or riding; off again when you stop.
  - During busy, timed calendar events.
  - Weekly schedules, with one-tap presets for work hours and bedtime. Overnight windows work.
  - A session you started yourself is never switched off automatically. If you switch off an automatic session,
    it stays off until that drive, meeting or schedule ends.
- **"While you were busy" summary:** when IA Mode turns off (by you or automatically) you get a notification
  and a screen showing replies sent, missed calls, emails, what still needs you, and money or commitment
  follow-ups. The last summary also appears on the home screen while IA Mode is off.
- **Home-screen widget:** one tap to turn IA Mode on or off, plus the number of chats that need you.
  It updates live.
- **Crash reporting** with Firebase Crashlytics (active once `google-services.json` is added). You can turn
  it off in Settings. Reports never include message text, names or numbers.

### Changed
- The database moves to version 2 with a proper migration. Existing conversations are kept.
- The "IA Mode is on" notification and the home screen show why it turned on automatically.
- Backend prompt version `2026-09-v3`, which adds group-chat rules and the new channels.

### How to test v1.2
1. **Backend:** `pip install -r requirements.txt`, then `pytest` shows 22 passed. Restart or redeploy the
   server; the app now sends new channel names that older servers reject.
2. **Android:** Sync and run. Then:
   - **Groups:** in a WhatsApp group, have a friend write "@YourName are you coming?". You should get an approval
     notification. A message that doesn't mention you gets no reply.
   - **Telegram and Instagram:** send yourself a DM from another account while IA Mode is on.
   - **Auto on/off:** Settings › Add a schedule starting 2 minutes from now. IA Mode turns on within a few
     minutes. Android batches alarms to save battery, so allow up to about 10 minutes.
   - **Summary:** turn IA Mode off after a few chats and tap the "While you were busy" notification.
   - **Widget:** long-press the home screen › Widgets › IA Mode.

### Known limits
- Auto on/off uses battery-friendly alarms, so it can start a few minutes late. Exact timing would need the
  "Alarms & reminders" permission, which Google Play restricts.
- Replies on Telegram and Instagram work only while that chat's notification exists, the same as WhatsApp.
- Group replies need your first name set in Settings, or other names added under Group chats.

## v1.1.0
Model fallback, offline retry, prompt-injection protection, Firebase auth without service accounts, boot
recovery, smooth transitions and shared-element animations. See REVIEW.md.
