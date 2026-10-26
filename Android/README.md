# Oasis Social – Android (Play Store build)

Kotlin WebView app that wraps https://oasis.myguyinthechair.com, matching the iOS build
(same URL, same internal-host rules, offline screen, pull-to-refresh, camera/mic/location/file upload).

- Package / applicationId: `com.myguyinthechair.oasis`
- minSdk 24, **targetSdk 36** (Google Play requirement for new apps/updates)
- No Compose, no extra libraries beyond AndroidX core/appcompat/activity/swiperefreshlayout

## Open it
1. Android Studio (current stable) -> **Open** -> select this folder.
2. Let Gradle sync. If it offers the **AGP Upgrade Assistant**, accept it. I could not compile this
   in my sandbox, so treat the first sync as the real test.
3. Run on an emulator/phone to check it.

## Build the file Google Play wants (AAB, not APK)
**Build -> Generate Signed App Bundle / APK -> Android App Bundle -> Create new keystore.**
Back the `.jks` and its passwords up somewhere safe (not in git). Pick "release". Upload the `.aab`
to Play Console and enroll in **Play App Signing**; your keystore becomes the *upload* key.

## Before bumping versions
`versionCode` in `app/build.gradle.kts` must increase on every upload.

## Play Console checklist
- Store icon: `play-store-icon-512.png` (512x512). You also need a feature graphic (1024x500) and phone screenshots.
- Privacy policy URL (required; you collect accounts, photos, camera, location).
- Data safety form + content rating questionnaire.
- User-generated content: in-app **report/block** tools and published terms.
- **Account deletion**: must be available in the app AND via a web link you enter in the Data safety section.
- Personal developer accounts created recently must run a closed test with testers before production; check the current rule in Play Console.
- Minimum-functionality policy: pure website wrappers can be rejected. Add something native
  (push notifications, share target, etc.) before submitting.
- Google sign-in does not work inside WebViews (`disallowed_useragent`). If your site offers it,
  use Credential Manager / Custom Tabs, or email login only.
- Optional: host `/.well-known/assetlinks.json` and add `android:autoVerify="true"` to the https intent filter
  so oasis links open in the app.
