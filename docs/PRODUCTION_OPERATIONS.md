# IA Mode production operations

This checklist is intentionally separate from local development. Do not copy secrets into source control, Android `local.properties`, diagnostics, or support tickets.

## Before every production deployment

1. Set Render environment values: `ENVIRONMENT=production`, `AUTH_MODE=firebase`, `FIREBASE_PROJECT_ID`, `GEMINI_API_KEY`, `REDIS_URL`, and a unique `RELEASE_VERSION`.
2. Configure `FORWARDED_ALLOW_IPS` only for the trusted proxy addresses supplied by the host. Never set it to `*`, because the IP rate limit would then trust attacker-controlled headers.
3. Keep `CORS_ALLOWED_ORIGINS` blank for the Android-only API. If a browser client is later added, list its exact HTTPS origin; do not use `*`.
4. Confirm `GET /v1/health` returns `status: ok` and `GET /v1/ready` returns `status: ready` after deployment.
5. In Firebase Crashlytics, enable email/Slack alerting for new fatal issues and a spike in non-fatal issues. Crash collection remains controlled by the user’s in-app setting.
6. In Render, create an uptime monitor against `/v1/ready` and alert when it returns non-200 for more than five minutes.
7. Enable Firebase App Check with Play Integrity for the release signing certificate. Only then set `ENFORCE_APP_CHECK=true` on the backend and `iamode.enableAppCheck.release=true` before rebuilding the release.

## Release gate

Run these before a public rollout:

```powershell
cd D:\ia-mode\backend
python -m pytest -q

cd ..\frontend
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease verifyPublicRelease
```

Upload an Android App Bundle to Google Play Internal Testing, add real tester accounts, and review Crashlytics plus the backend structured logs before promotion.

## Incident rules

- Use the API response `x-request-id` to find a backend request; never ask a user to send message content or OAuth tokens.
- A 429 means the rate limit is working—do not raise limits without checking misuse and Gemini quota.
- A 503 from AI is retryable. Keep the worker backoff enabled; do not loop immediately.
- A failing `/v1/ready` normally means the shared Redis limiter is unavailable. Restore Redis before accepting traffic.
