# App languages (v1.9): English, తెలుగు, हिन्दी

## For users
Settings › **App language** → System default / English / తెలుగు / हिन्दी.
On Android 13+ the same choice also appears in Settings › Apps › IA Mode › Language.
Replies to people are unaffected: they're always written in the other person's language.

## How it works
- Every user-visible text in the screens, notifications and reminders goes through `tr("English text", args…)`
  (`core/i18n/I18n.kt`). It looks up the matching string resource and falls back to English if there isn't one,
  so a missing translation never crashes or shows a key.
- Translations are standard Android resources: `res/values/strings_i18n.xml` (English),
  `res/values-te/…` and `res/values-hi/…`, 602 strings each. They open in Android Studio's Translations Editor
  and work with Crowdin/Lokalise.
- Texts with values use Android placeholders: `tr("Applied %1$s", daysAgo)`.
- Labels that come from enums (tabs, stages, categories, relationships, permissions, celebration headlines) are
  translated where they're displayed (`tr(stage.label)`), so switching language updates them immediately.
- Language choice: Android 13+ uses the system per-app language (`LocaleManager`, `res/xml/locales_config.xml`);
  Android 10–12 store it in the app and apply it in `IAModeApp`/`MainActivity.attachBaseContext`.
- Fixed on purpose: Gmail label / Outlook category names ("IA Mode/Interviews") stay English so every language
  uses the same labels; product names (IA Mode, Gmail, Outlook, WhatsApp, AI, PDF) stay as they are.

## Adding or changing text
1. Write English text in the code as usual.
2. `python tools/i18n/extract.py --apply` wraps new user-facing texts in `tr(...)` (technical strings such as
   routes, keys, formats, URLs, regexes and ALL_CAPS values are skipped automatically).
3. Add Telugu and Hindi for each new text in `tools/i18n/translations.json`: `"English": ["తెలుగు", "हिन्दी"]`.
4. `python tools/i18n/generate.py` regenerates the three resource files and `I18nKeys.kt`, checks that every
   `%1$s` placeholder matches, and lists anything still untranslated.

## Review before public release
The translations were written for this release but have **not yet been reviewed by native speakers**. Have a
Telugu and a Hindi speaker read the screens once (Settings, Mail, AI Actions, Applications, onboarding), then
fix wording in `translations.json` and regenerate. Texts from the AI server (summaries, reasons) follow the
email's language, not the app language.

## Known limits
- 14 texts built from nested expressions stay in English (e.g. "Ravi requested a document (PDF)").
- A few messages created in the data layer (for example some error reasons) are shown in English.
- Date formats follow the phone's locale; month names follow the chosen app language on Android 13+.
