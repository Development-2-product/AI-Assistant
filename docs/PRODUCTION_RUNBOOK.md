# Production release runbook

## Required infrastructure

- Run the API behind managed TLS with a custom HTTPS domain. Do not use a free-tier cold-start service for time-sensitive automation.
- Set `ENVIRONMENT=production`, `AUTH_MODE=firebase`, `FIREBASE_PROJECT_ID`, `GEMINI_API_KEY`, and a managed TLS `REDIS_URL`.
- Set `FORWARDED_ALLOW_IPS` to only the documented Render proxy IP/CIDR(s). Never use `*`; otherwise a public origin can trust forged `X-Forwarded-For` values and weaken the IP rate limit. If the proxy network changes, update this value before relying on client-IP telemetry or limits.
- Monitor `/v1/health` for liveness and `/v1/ready` for traffic readiness. `/v1/ready` deliberately returns HTTP 503 when Redis is unreachable, because protected production requests would fail closed in that condition.
- Set `RELEASE_VERSION` to the deployed commit or release tag.
- Restrict dashboard access, rotate provider secrets, and set a bounded retention period for hosting logs.
- Monitor availability, p95 latency, HTTP 429/5xx rates, Gemini failures, Redis failures, and monthly model spend. Alert an on-call owner.

## Required mobile controls

- Use a Play-signed release build, Firebase production configuration, and Firebase App Check / Play Integrity enforcement before public distribution.
- Stage App Check safely: link the Play Console app to the Firebase project, register the production Android app under **Firebase → App Check** using the Google Play App Signing SHA-256, release a build that sends `X-Firebase-AppCheck`, confirm valid requests in server logs, then set `ENFORCE_APP_CHECK=true` and set `FIREBASE_SERVICE_ACCOUNT_JSON` to the least-privileged service-account JSON secret. Grant that service account the **Firebase App Check Token Verifier** role. Keep enforcement off only during this staged rollout; do not enable it until the Play-distributed build is installed successfully.
- Submit the Play permission declaration for SMS and complete Google OAuth verification and any required security assessment for Gmail/Drive scopes.
- Verify each provider's automation terms. Do not claim guaranteed WhatsApp delivery; notification replies are only dispatch attempts.
- Publish the reviewed privacy notice, support process, and Data Safety disclosures.
- Host the completed `docs/index.html` at a stable HTTPS URL and set `iamode.privacyPolicyUrl=https://your-domain/privacy` in ignored `frontend/local.properties`. Release builds now fail if that URL is absent or the checked-in policy still contains `[INSERT ...]` placeholders.

## Release gate

1. Run backend tests, Android unit tests, lint, migration instrumentation tests, and a signed-release smoke test on physical devices.
2. Run multilingual safety evaluations: crisis language, money/commitments, impersonation, prompt injection, group messages, and sensitive data requests.
3. Confirm automatic replies are off by default for a new user, high-risk categories require approval, and the undo flow works without network access.
4. Test provider outages, Redis failure, expired Firebase tokens, revoked Gmail/Outlook access, notification removal, and SMS failure.
5. Record approval from product, security, privacy/legal, and support owners.
6. Copy the Google Play App Signing SHA-1/SHA-256 (not only the local release-keystore fingerprints) into the production Firebase Android app and Google OAuth client, then download the refreshed release `google-services.json` and rebuild the AAB.
7. Use a new `versionCode` for every Play upload. Version code 11 is already uploaded, so the next AAB must use at least 12.

## Incident response

If an unsafe send, privacy event, or suspected credential exposure occurs: immediately disable the affected API/deployment path, rotate affected secrets, preserve metadata-only evidence, notify affected users where required, and complete a post-incident review before re-enabling the feature. Add a tested remote kill switch before public launch.
