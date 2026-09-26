# IA Mode

> **Replies not working?** Open **Settings › Connection check** in the app, and see **TROUBLESHOOTING.md**.
>
> **v1.2** adds WhatsApp Business, Telegram and Instagram DMs, group chats (reply when mentioned),
> automatic on/off (driving, meetings, schedules), a "While you were busy" summary, a home-screen widget
> and crash reporting. See **CHANGELOG.md**. **REVIEW.md** lists what's still needed before a public release.

An Android assistant that replies for you when you're busy: WhatsApp messages, Gmail and missed calls,
in each person's own language (Telugu, Tenglish, Hindi, Tamil, Kannada, English and mixes).

- **Clients / Business** get replies automatically (with an undo window).
- **Partner / Friends / Family** need your approval first. After you approve, IA Mode can handle
  the rest of that chat until it ends.
- **Unknown** numbers and senders always need approval.
- **Missed calls** get an AI-written SMS in the caller's language, based on what you're doing
  (driving, riding, interview, meeting, gaming, sleeping).
- It **never** replies to messages that sound like a crisis, never agrees to money or commitments,
  and stops when a conversation is over.

```
ia-mode/
├── backend/    FastAPI + Gemini. Analyzes messages and writes replies. Stores nothing.
└── frontend/   Android app (Kotlin, Jetpack Compose). Reads, decides, sends. Data stays on the phone.
```

The AI **analyzes and writes**. The app's own rules (`domain/policy/ReplyPolicy.kt`) **decide** whether
anything is sent. That split is deliberate: a model mistake can produce a bad draft, but it can never
make IA Mode auto-send to your family or agree to a payment.

---

## 1. Run the backend

Requires Python 3.13 and a **paid-tier** Gemini API key from Google AI Studio.
(On the free tier Google may use prompts to improve its products; that's not acceptable for private chats.)

```bash
cd backend
python -m venv .venv
source .venv/bin/activate            # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
cp .env.example .env                 # then put your GEMINI_API_KEY in .env
pytest                               # 27 tests, no API key needed
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Open http://localhost:8000/docs to try the API.

### Run in Docker locally

From `backend/`, Docker Compose reads the uncommitted `.env` file at runtime:

```bash
docker compose up --build
```

Compose always reads only the ignored `backend/.env`. It binds to `127.0.0.1:8000` by default. For a trusted phone
on the same Wi-Fi, set `BIND_ADDRESS=0.0.0.0` and `HOST_PORT=8000` in that real `.env`, then run
`docker compose up --build`. Do not expose this development server directly to the internet and do not use a
production env file for local phone testing.

| Endpoint | What it does |
|---|---|
| `POST /v1/messages/process` | Fast model analyzes (language, tone, money, crisis, conversation state); writer model drafts the reply |
| `POST /v1/messages/restyle` | Rewrites a draft in another style (professional, casual, romantic, comeback…) |
| `POST /v1/calls/missed-reply` | Writes the missed-call SMS in the caller's language and script |
| `POST /v1/conversations/recap` | One-line summary when a conversation ends |
| `GET  /v1/health` | Health check |

Models are set in `.env` (`GEMINI_MODEL_FAST`, `GEMINI_MODEL_WRITER`, optional `GEMINI_MODEL_FALLBACK`).
If a model is overloaded or rate-limited, the backend automatically tries the next one. Google retires model versions
regularly, so check the current model list and update these two lines when needed. Prompts live in
`backend/app/prompts/*.md` and can be edited without touching code.

### Deploy to Render (free, no card)

1. Push the repo to GitHub (private is fine). **Never commit `backend/.env`.**
2. On render.com: **New › Blueprint** and pick the repo. `render.yaml` sets everything up; enter
   `GEMINI_API_KEY` when asked (and change `FIREBASE_PROJECT_ID` if yours isn't `ia-mode`).
3. Add a free UptimeRobot monitor on `https://YOUR-APP.onrender.com/v1/health` every 5 minutes so the
   free server doesn't fall asleep.

Firebase tokens are verified against Google's public keys, so no service-account file is needed on Render.

---

## 2. Run the Android app

Requires Android Studio (Ladybug or newer) and a phone or emulator with Android 10+.

1. **File › Open** the `frontend` folder. Let Gradle sync (it downloads Gradle 8.11.1 and all libraries).
   If Studio asks to create the Gradle wrapper, accept.
2. Start the backend (step 1).
3. Copy `frontend/local.properties.example` to the ignored `frontend/local.properties` and set:
   - `iamode.backendUrl` for debug. An emulator can use `http://10.0.2.2:8000/`; a real phone may use
     `http://LAN-IP:8000/` only on the same trusted Wi-Fi.
   - `iamode.devToken` to the matching `DEV_API_TOKEN` in `backend/.env`.
   - `iamode.backendUrl.release` only for a release build; it must be an HTTPS domain, never a LAN address,
     HTTP address, or raw public IP.
4. Rebuild after every endpoint or debug-token change. The endpoint is immutable after APK build; Connection Check
   diagnoses health, authentication, AI, Gmail, permissions and recent activity but cannot edit server settings.

### First launch

Setup asks for your name, gender (for correct Hindi/Telugu verb forms) and default language, then permissions:

| Permission | Why |
|---|---|
| Notification access | Read WhatsApp messages and reply through WhatsApp's own Reply button; notice new Gmail |
| Caller ID & spam app | Learn the incoming number so a missed caller can be texted (never blocks calls) |
| Phone | Detect that a call rang and wasn't answered |
| Send SMS | Text missed callers |
| Contacts | Recognize saved contacts. **Also required for Android to screen calls from saved contacts** |
| Calendar (optional) | Detect interviews and meetings |
| Physical activity (optional) | Detect driving / riding |
| Usage access (optional) | Detect games like Free Fire and BGMI |

**Android 13+ sideloaded builds:** Notification access may be greyed out. Open *App info › ⋮ ›
Allow restricted settings*, then grant it. This doesn't apply to Play Store installs.

Then:
- **Contacts screen:** label your clients, business contacts, partner, friends and family. Use the name
  exactly as it appears in WhatsApp. Set a language per person if you like.
- **Settings › Connect a Gmail account** for email replies (you can connect several).
- Turn IA Mode on from the home screen or the **IA Mode Quick Settings tile**.

### Testing without a second phone
- WhatsApp: ask a friend to message you, or use a second WhatsApp account on another device.
- Missed call: call yourself from another phone and let it ring out.
- Situation: tap the status chip on the home screen to set Driving, Interview, Gaming etc. manually.

### Run the tests
```bash
cd frontend
./gradlew testDebugUnitTest     # policy, language, pipeline, auto-mode tests (38 tests)
```

---

## 3. Firebase auth (for release builds)

The debug build talks to the backend with a shared dev token. For anything you give to other people:

1. Create Firebase Android apps for `com.iamode.app.dev` (debug) and `com.iamode.app` (release), and enable
   **Anonymous** sign-in.
2. Put their variant-specific configurations in `frontend/app/src/debug/google-services.json` and
   `frontend/app/src/release/google-services.json`; never use a root shared config.
3. For a production deployment, create the real `backend/.env` with `ENVIRONMENT=production`,
   `AUTH_MODE=firebase`, and the matching `FIREBASE_PROJECT_ID`; the backend reads no alternate env file.
   Set the HTTPS endpoint only in `iamode.backendUrl.release` and rebuild.

Each phone then signs in anonymously and the backend verifies its Firebase ID token on every request.

---

## 4. Before publishing on Google Play

These are real blockers, plan for them early:

- **SEND_SMS** is a restricted permission. Submit a Permissions Declaration explaining missed-call
  auto-replies. If it's rejected, the fallback is opening the SMS app with the message pre-filled.
- **Gmail restricted scope** (`gmail.modify`; plus `drive.readonly` if you offer Drive search) needs Google OAuth verification and an annual
  CASA security assessment before the public can use them. Until then, add up to 100 test users in the
  Google Cloud console OAuth consent screen. You also need an OAuth client for the app's package name
  and SHA-1/SHA-256 in the same Google Cloud project. Register debug, release, and Google Play App Signing
  fingerprints; use least-privilege Gmail/Drive scopes and validate first through Play Internal Testing.
- **Notification listener + WhatsApp:** replying through WhatsApp's notification is the only way to
  automate a personal WhatsApp account. It's the same mechanism smartwatches use, but WhatsApp's terms
  don't explicitly allow automated personal replies. For business-scale use, move clients to the
  WhatsApp Business Platform. Guaranteed server-side delivery after notifications disappear requires a separate
  Meta Cloud API product decision plus Meta Business credentials, verified phone number, access token, webhook URL,
  approved templates, and user opt-in; none of those are implemented here.
- **Accessibility service is not used**, which avoids another Play review category.
- Write a clear privacy policy: messages are processed by Google Gemini (paid tier), nothing is stored
  on the server, and conversations are kept encrypted on the phone for 30 days.

---

## 5. How it works

```
WhatsApp notification ─┐
Gmail (API, per account)├─► ProcessIncomingMessageUseCase
Missed call (screening + ┘        │  store (encrypted Room / SQLCipher)
  call state)                     │  resolve relationship from YOUR labels
                                  │  backend: Gemini analyzes + drafts
                                  ▼
                         ReplyPolicy (deterministic)
        ┌───────────────┬────────────────┬──────────────┬───────────┐
     AutoSend       NeedsApproval       Crisis          End        Skip
  (undo window)   (notification with   (never reply,   (recap,    (newsletters,
                   Approve / Write /    alert you)      stop)      OTPs)
                   Don't reply)
```

Key files:

| Area | File |
|---|---|
| Rules that decide sending | `frontend/.../domain/policy/ReplyPolicy.kt` |
| Main pipeline | `frontend/.../domain/usecase/ProcessIncomingMessageUseCase.kt` |
| Missed calls | `domain/usecase/HandleMissedCallUseCase.kt`, `service/call/*` |
| WhatsApp read + reply | `service/notification/*` |
| Gmail | `data/gmail/*` |
| Situation detection | `service/situation/SituationDetector.kt` |
| Prompts | `backend/app/prompts/*.md` |
| Backend safety checks | `backend/app/services/safety.py` |

### Known limits of V1
- Group chats get a reply only when someone mentions you, and always need your approval.
- WhatsApp replies only work while that chat's notification exists. If you open WhatsApp and read the
  chat, IA Mode can't reply there, and it tells you so.
- WhatsApp Business uses the same safe Android notification-reply mechanism. If Android never delivers
  the message notification, or it is dismissed, there is no message/action available for IA Mode to read
  or auto-reply to. A dispatched notification reply is not proof that WhatsApp delivered it. The app clears
  removed or disconnected notification actions and asks you to open the relevant chat and reply manually.
  Guaranteed missed-message handling requires a separately approved Meta Cloud API integration; it is not
  safe to simulate without Meta Business verification, a verified phone number, access token, webhook,
  approved templates, user opt-in, and a confirmed product decision.
- If you type a reply yourself in WhatsApp, IA Mode notices and steps back from that chat.
- Android can't tell a car from a bike; pick your usual vehicle in Settings.
- Adding a second Gmail account may reuse the first account if Google doesn't show the account picker;
  remove and re-add, or sign the second account into the phone first.
