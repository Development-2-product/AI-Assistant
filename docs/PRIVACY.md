# IA Mode Privacy Notice

Last updated: 2026-10-19

This is the product baseline for a public release. Have counsel review it for the countries in which IA Mode is offered before publishing it as the hosted privacy policy.

## What the app handles

IA Mode can read message notifications, selected mail, contact labels, missed-call metadata, and optional calendar/activity/location signals. It uses that information to create a suggested reply or to decide whether the user must review a reply. The user controls which permissions and integrations are enabled.

The Android app keeps its working data on the device in an encrypted SQLCipher database. Removing the app or using its data-reset controls removes that local data. Android account providers retain OAuth tokens in their own secure account storage; users can revoke access from Google or Microsoft.

## AI processing

When AI assistance is used, only the context needed for that request is sent over TLS to the IA Mode API and then to the configured Google Gemini paid-tier service. The API is designed not to persist message bodies, email bodies, phone numbers, OAuth tokens, or prompts in application logs. The app sends recent conversation context to create a meaningful reply; it does not send the complete device inbox by default.

Do not describe the service as end-to-end encrypted: AI-assisted content leaves the device for processing. Obtain explicit consent before enabling any AI feature.

## Sharing and retention

- IA Mode does not sell personal data or use it for advertising.
- Google, Microsoft, Meta, and chat/SMS providers process data under the integrations the user enables and their own terms.
- Crash reporting is opt-in. Message content must never be attached to crash reports.
- Operational logs contain request metadata and a correlation ID, not request content. Configure the hosting provider's log-retention period and access controls before launch.

## User controls

Users can disable IA Mode, revoke Android permissions, disconnect mail accounts, delete local app data, and revoke provider access. Automatic sending must remain visibly enabled, reversible with an undo period, and approval-first for high-risk content.

## Before publishing

Publish this notice at a stable HTTPS URL, name the legal controller/contact and support address, add jurisdiction-specific rights and retention durations, complete Google OAuth and Play Data Safety disclosures, and link the notice from onboarding and Settings.
