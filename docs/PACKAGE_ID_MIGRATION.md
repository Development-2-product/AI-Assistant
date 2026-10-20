# Android application ID migration

The distributable IA Mode application ID is now **`app.iamode.mobile`**.
The debug variant is **`app.iamode.mobile.dev`**. These are the identifiers that
Google Play, Firebase, Google OAuth and Microsoft Entra see. The Kotlin source
namespace intentionally remains `com.iamode.app`; it is an internal code
namespace and does not affect the installed package.

## Required external configuration

Do these before building a distributable release:

1. In Google Play Console, verify that `app.iamode.mobile` is available while
   creating the app. If Play says it is already in use, stop and choose another
   ID before creating Firebase or OAuth registrations.
2. In the existing Firebase project, add two Android apps: `app.iamode.mobile`
   and `app.iamode.mobile.dev`. Enable Anonymous Authentication. Add the
   release and debug signing fingerprints to the matching Firebase apps.
3. Download each new `google-services.json` and replace the ignored files at
   `frontend/app/src/release/google-services.json` and
   `frontend/app/src/debug/google-services.json`. Never edit a configuration
   file by hand or put it at `frontend/app/google-services.json`.
4. In Google Cloud, create/update Android OAuth client registrations for the
   new package IDs and their matching SHA fingerprints. This is required for
   Google sign-in and Gmail access.
5. In Microsoft Entra, add Android platform entries for both new package IDs
   and their MSAL signature hashes. See `docs/OUTLOOK_SETUP.md`.

The app will reject a build whose Firebase configuration belongs to the old
package. That guard prevents a release APK that installs but cannot authenticate.

## Consequence of this change

Android treats a different application ID as a different app. It will install
alongside the old `com.iamode.app` build, but it cannot update it or reuse its
on-device database. This is expected for a new Play listing.
