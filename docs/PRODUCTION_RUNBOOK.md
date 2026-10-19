# Production release runbook

## Required infrastructure

- Run the API behind managed TLS with a custom HTTPS domain. Do not use a free-tier cold-start service for time-sensitive automation.
- Set `ENVIRONMENT=production`, `AUTH_MODE=firebase`, `FIREBASE_PROJECT_ID`, `GEMINI_API_KEY`, and a managed TLS `REDIS_URL`.
- Set `RELEASE_VERSION` to the deployed commit or release tag.
- Restrict dashboard access, rotate provider secrets, and set a bounded retention period for hosting logs.
- Monitor availability, p95 latency, HTTP 429/5xx rates, Gemini failures, Redis failures, and monthly model spend. Alert an on-call owner.

## Required mobile controls

- Use a Play-signed release build, Firebase production configuration, and Firebase App Check / Play Integrity enforcement before public distribution.
- Submit the Play permission declaration for SMS and complete Google OAuth verification and any required security assessment for Gmail/Drive scopes.
- Verify each provider's automation terms. Do not claim guaranteed WhatsApp delivery; notification replies are only dispatch attempts.
- Publish the reviewed privacy notice, support process, and Data Safety disclosures.

## Release gate

1. Run backend tests, Android unit tests, lint, migration instrumentation tests, and a signed-release smoke test on physical devices.
2. Run multilingual safety evaluations: crisis language, money/commitments, impersonation, prompt injection, group messages, and sensitive data requests.
3. Confirm automatic replies are off by default for a new user, high-risk categories require approval, and the undo flow works without network access.
4. Test provider outages, Redis failure, expired Firebase tokens, revoked Gmail/Outlook access, notification removal, and SMS failure.
5. Record approval from product, security, privacy/legal, and support owners.

## Incident response

If an unsafe send, privacy event, or suspected credential exposure occurs: immediately disable the affected API/deployment path, rotate affected secrets, preserve metadata-only evidence, notify affected users where required, and complete a post-incident review before re-enabling the feature. Add a tested remote kill switch before public launch.
