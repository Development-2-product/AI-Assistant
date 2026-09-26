# Troubleshooting: "IA Mode couldn't reach the AI"

Start here: in the app, open **Settings › Connection check**. It tests each step in order (internet, server,
sign-in, AI, Gmail) and shows the exact fix. **Copy report** gives a text you can share; it contains no
messages, tokens or keys.

## Why it happened before v1.3

Each of these alone breaks every reply:

| Cause | Symptom |
|---|---|
| The debug APK had a private LAN URL built in | Works only at home on Wi-Fi with the PC server running |
| The release APK had an unconfigured placeholder URL and no token | Never works |
| Render ran `AUTH_MODE=firebase` but the app had no `google-services.json` | Server rejects every request (401) |
| Backend not updated after installing a newer app | New fields rejected (422) |

With no AI, no reply is drafted, so WhatsApp, Telegram and Gmail replies all stop together.

## Pick one server setup

### Option A: Render with a dev token (quickest, fine for personal use)
On Render, open the service, then **Environment**, and set:
```
ENVIRONMENT=staging
AUTH_MODE=dev
DEV_API_TOKEN=<a long random secret, e.g. 40+ characters>
GEMINI_API_KEY=<your key>
GEMINI_MODEL_FAST=gemini-3.5-flash-lite
GEMINI_MODEL_WRITER=gemini-3.5-flash
GEMINI_MODEL_FALLBACK=gemini-3.5-flash-lite
```
Save, which redeploys. In the app, go to **Settings › Connection check › Server**, enter
`https://<your-app>.onrender.com` and the same token, then tap **Save and check**. No rebuild needed.

### Option B: Render with Firebase (recommended before sharing the app)
1. In Firebase `ia-mode`, go to **Authentication › Sign-in method** and enable **Anonymous**.
2. Register Android apps `com.iamode.app.dev` and `com.iamode.app`, then put `google-services.json` in `frontend/app/`.
3. On Render, set `ENVIRONMENT=production`, `AUTH_MODE=firebase` and `FIREBASE_PROJECT_ID=ia-mode`.
4. Rebuild the app.

### Option C: PC on your Wi-Fi (testing only)
Run `uvicorn app.main:app --host 0.0.0.0 --port 8000`, allow port 8000 in Windows Firewall, and keep the
phone on the same Wi-Fi. This setup doesn't work on mobile data.

## Checking the server side
Render › your service › **Logs**. The first line after each deploy shows the configuration:
```
{"msg": "startup", "auth_mode": "dev", "gemini_key_set": true, "writer_model": "gemini-3.5-flash", ...}
```
`gemini_key_set: false` means the key is missing. `gemini_error` lines show Google's exact reason
(e.g. `429 RESOURCE_EXHAUSTED` = free quota used up, `400 API key not valid`).

## Gmail not answering
- IA Mode must be **on**. Only mail that arrives **after** turning it on is answered, and it must be unread,
  in the inbox, and from a person (not a newsletter or no-reply address).
- New mail is checked when the Gmail app shows a notification. Without Gmail notifications, the check runs
  every 15 minutes. **Connection check › Check Gmail now** runs it immediately.
- Gmail authorization requires an Android OAuth client that exactly matches the installed APK's package and
  signing certificate. This project's current local debug certificate SHA-1 is
  `E3:F1:97:75:B3:70:A6:5E:16:FF:DB:7D:51:8E:C7:CB:D8:8C:3A:1B`; the installed debug
  `google-services.json` does **not** contain a matching Android OAuth client, so Gmail cannot work yet.
  In Firebase Console > Project settings > Your apps, add that SHA-1 to the Android app
  `com.iamode.app.dev`, download its updated `google-services.json`, and replace
  `frontend/app/src/debug/google-services.json`. Then enable **Gmail API** in the linked Google Cloud
  project and add your Gmail address under OAuth consent screen > Test users. Rebuild and reinstall.
- Release builds need a separate Android OAuth client for package `com.iamode.app` and the SHA-1 of the
  release signing key. Never use a desktop OAuth `client_secret_*.json` in the Android app.

## Chat replies not sending even when the AI works
Replies go through the chat app's own notification Reply button. If you open the chat in WhatsApp, Telegram
or Instagram, that notification disappears and IA Mode can't reply there. The Send failure then shows under
**Recent activity** in the Connection check.

The same applies to WhatsApp Business. If the notification was never delivered, dismissed, or expired,
Android provides no supported local API for IA Mode to retrieve the missed message or send an auto-reply.
Open WhatsApp Business and reply manually; keep the chat notification active until IA Mode sends. For
guaranteed server-side handling after notifications disappear, make a deliberate Meta Cloud API product
integration with Meta Business credentials, a verified number, access token, webhook, approved templates,
and recipient opt-in. Do not add a Cloud API fallback without that complete setup.
