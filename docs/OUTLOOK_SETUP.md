# Outlook / Microsoft 365 setup (v1.8)

IA Mode reads and sends Outlook mail through **Microsoft Graph**, signing in with **MSAL**. You register the app
once in Microsoft Entra ID (Azure), then put three values in `frontend/local.properties`. Until then the app shows
"Outlook isn't set up in this build" and Gmail keeps working normally.

## 1. Register the app (portal.azure.com → Microsoft Entra ID → App registrations → New registration)

- **Name:** IA Mode
- **Supported account types:** *Accounts in any organizational directory and personal Microsoft accounts*
  (this covers Outlook.com, Hotmail and work/school accounts)
- Leave Redirect URI empty for now → **Register**
- Copy **Application (client) ID** → this is `iamode.msal.clientId`

## 2. Get your signature hashes (one per keystore)

Debug and release are signed with different keys, and the debug app id is `com.iamode.app.dev`.

```bash
# Debug keystore (from frontend/app)
keytool -exportcert -alias <debug-alias> -keystore iamode-debug.keystore | openssl sha1 -binary | openssl base64
# Release keystore
keytool -exportcert -alias <release-alias> -keystore ia-mode-release.keystore | openssl sha1 -binary | openssl base64
```
On Windows, use Git Bash (it includes openssl). Each command prints something like `ga0RGNYHvNM5d0SLGQfpQWAPGJ8=`.

## 3. Add the Android platform (Authentication → Add a platform → Android), twice

| Package name | Signature hash |
|---|---|
| `com.iamode.app.dev` | debug hash |
| `com.iamode.app` | release hash |

Azure shows the redirect URI it expects (`msauth://com.iamode.app.dev/<url-encoded hash>`). IA Mode builds exactly
that at runtime, so you don't need to copy it anywhere.

## 4. API permissions (API permissions → Add → Microsoft Graph → Delegated)

`User.Read`, `Mail.ReadWrite`, `Mail.Send` (plus `offline_access`, `openid`, `profile`, which are usually present).
Nothing else. IA Mode never deletes mail.

## 5. local.properties (never committed)

```properties
iamode.msal.clientId=00000000-0000-0000-0000-000000000000
iamode.msal.signatureHash.debug=ga0RGNYHvNM5d0SLGQfpQWAPGJ8=
iamode.msal.signatureHash.release=Xo8WBi6jzSxKDVR4drqm84yr9iU=
```
Then **Sync Project with Gradle Files** and rebuild. Settings → **Outlook accounts** → **Connect Outlook / Microsoft 365**.

## 6. Before public release

- **Publisher verification** (Branding & properties → Publisher domain + verify with a Microsoft Partner ID). Free;
  removes the "unverified" warning on the consent screen.
- **Work accounts:** many organizations block user consent for mail permissions. Their admin must grant consent
  (Enterprise applications → IA Mode → Permissions → Grant admin consent). IA Mode shows a clear message when this happens.
- No CASA-style paid assessment is required by Microsoft for these delegated permissions.

## How it maps to what IA Mode already does

| Feature | Outlook implementation |
|---|---|
| New mail | Inbox messages from the last 7 days; near-instant when the Outlook app shows a notification |
| Signals | `internetMessageHeaders` (Reply-To, List-Unsubscribe, Auto-Submitted, Precedence), attachment names |
| Reply in thread | `createReply` → set recipient/body → attachments (inline ≤3 MB, upload session above) → `send` |
| Never sends twice | The reply draft's ID is saved on the action before sending; on retry a missing draft means it was sent |
| Archive / back to Inbox | Move to the well-known `archive` / `inbox` folder |
| Move to… | Outlook folder (created if missing) |
| IA Mode labels (opt-in) | Outlook categories |
| Stable IDs | Every call uses `Prefer: IdType="ImmutableId"` so moved messages keep their ID |
| Style learning | Sent Items bodies, analyzed on the phone |
