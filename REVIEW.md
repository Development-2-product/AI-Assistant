# IA Mode – Production Review (v1.1)

What was reviewed, what was fixed in this version, and what's still needed before a public release.

## 1. Security issue found in the uploaded zip

**`backend/.env` (with a real Gemini API key) was inside the zip.** It has been removed from this build.
If the zip was shared or uploaded anywhere, create a new key at aistudio.google.com/apikey and delete the old
one. `.gitignore` already keeps `.env` out of Git, but zipping the folder by hand copies it anyway. Before
zipping, delete `backend/.env`, `backend/.venv` and `frontend/build`.

## 2. Gaps found, and what this version does about them

| Area | Gap in v1.0 | Fixed in v1.1 |
|---|---|---|
| AI reliability | One busy Gemini model (the 503 "high demand" you hit) failed the whole request | Backend tries the next model automatically (writer → fallback → fast). Configurable with `GEMINI_MODEL_FALLBACK` |
| AI reliability | Any network hiccup meant "write this reply yourself", even for a 2-second blip | App retries busy/timeouts twice (2 s, 5 s) and waits up to 60 s for a sleeping free-tier server |
| Offline | Messages that arrived with no internet were never answered | If the AI can't be reached, IA Mode asks you **and** retries in the background when you're online again (30 s → 4 min backoff). If you reply first, the retry does nothing |
| Error messages | Every failure said "couldn't reach the AI" | Clear reasons: no internet / AI busy / sign-in problem / message rejected |
| Security | Messages were pasted straight into prompts, so a message like "ignore your instructions…" could steer the AI (prompt injection) | Conversations are fenced as untrusted data in every prompt, with explicit rules; message text can't break out of the fence. Tested |
| Auth on Render | `firebase-admin` needs Google service-account credentials, which Render doesn't have, so every request would fail with 401 | Firebase ID tokens are now verified directly against Google's public keys (cached). Checks signature, audience **and** issuer. Tested |
| Background | After a reboot, driving detection and the Gmail schedule stopped until the app was opened | Boot/update receiver restores everything if IA Mode was on |
| Background | Xiaomi/Realme/Vivo/Oppo phones kill background apps | New "Unrestricted battery" permission step + Autostart guidance on those brands |
| Background | Notification listener can silently disconnect after updates on some phones | App asks Android to rebind it on every start |
| Visibility | No sign on the phone that IA Mode is on | Optional silent "IA Mode is on" notification with a live timer and a **Turn off** button |
| Data safety | If the Android Keystore key is lost (restores, some OS updates) the encrypted database crashes the app on every launch | Detects it and starts with a fresh database instead of crashing |
| Data safety | No migration policy | Schema is exported; downgrades reset, upgrades must ship a Migration (documented in code) |
| Deployment | Dockerfile always started 2 workers, too much for free-tier memory | `WEB_CONCURRENCY` (default 1), container health check, `render.yaml` blueprint |
| Quality | No automated checks | GitHub Actions: backend tests, Android unit tests and a debug APK on every push |
| Tests | Backend 12, Android 19 | Backend **20**, Android **25** (new end-to-end pipeline test: offline, retry, duplicates, turn-off) |

## 3. Screen transitions and UI

Every transition now uses one motion system (Material 3 emphasized easing, `ui/theme/Motion.kt`), so the app
feels consistent instead of mixing default animations.

| Where | What changed |
|---|---|
| App launch | Splash screen with the IA Mode icon that holds until settings load, then fades out, with no blank or white flash (dark window background added for dark mode) |
| Navigation | Shared-axis motion: forward slides in from the right, back reverses it. **Predictive back** on Android 14+ (you see the previous screen while swiping back) |
| List → conversation | The contact's avatar and name **fly** from the card into the conversation header (shared-element transition). Name and relationship travel in the route, so this works from the first frame |
| Onboarding → home | Home fades and settles in instead of sliding; onboarding steps slide in both directions and Back goes to the previous step |
| Home tabs | Tabs are now **swipeable** pages. Cards animate in, out and into new positions when a chat changes state (e.g. from Needs you to Handling) |
| Status changes | Status pills cross-fade their text and color; the mode card animates its color and title; the situation chip animates when it changes |
| Undo window | The countdown drains smoothly frame by frame with a progress bar, instead of ticking |
| Conversation actions | The bottom panel morphs between approve, sending, waiting and call-back instead of jumping |
| Messages | New messages animate into the thread |
| Flexibility | Content keeps a readable width on tablets, foldables and landscape; the mode card goes compact on short screens |
| Personalization | Optional **Material You** colors from the wallpaper (Android 12+) |
| Feel | Haptic feedback when turning IA Mode on/off and when approving |
| Identity | Contacts get colored initials by relationship (client blue, partner pink, friend green, family amber), so you can tell who's who at a glance |

## 4. Still needed before a public release (not code I can finish here)

| Priority | Item | Why |
|---|---|---|
| High | **Gemini paid tier** | Free-tier prompts may be used by Google; not acceptable for other people's private chats |
| High | **Release signing key** | Create one in Android Studio (Build › Generate Signed Bundle) and back it up; losing it means you can never update the app |
| High | **Privacy policy page** | Required by Play and by Google's Gmail verification |
| High | **Gmail OAuth verification + CASA assessment** | Needed before anyone outside your 100 test users can connect Gmail |
| High | **Play SMS permission declaration** | `SEND_SMS` is restricted; without approval, the fallback is opening the SMS app pre-filled |
| Medium | **Crash reporting** (Firebase Crashlytics, free) | You currently won't know when the app crashes on someone else's phone |
| Medium | **Paid always-on hosting** (Render Starter or Cloud Run) | Free Render sleeps; UptimeRobot mitigates it, but paid hosting removes 50-second cold starts entirely |
| Medium | **Redis for rate limits** | Only matters when you run more than one server instance |
| Low | Group chats, Telegram/Instagram, voice notes, daily summary, auto-on when driving starts | Feature work for v1.2+ |

## 5. How to verify this version

1. Backend: `cd backend && pytest` shows **20 passed**.
2. Android: Android Studio › Sync › Run. Then check:
   - The splash screen fades into the app.
   - Opening a chat makes the avatar and name glide into the header.
   - Swiping left and right switches tabs.
   - On Android 14+, swiping back previews the previous screen.
3. Turn Wi-Fi and mobile data off, get a WhatsApp message, then turn data back on. Within about a minute the
   notification is replaced by a proper suggested reply.
4. Settings › Appearance: try wallpaper colors and the "IA Mode is on" notification.
