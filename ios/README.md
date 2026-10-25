# Oasis Social — iOS (built in the cloud, no Mac needed)

A SwiftUI app that wraps https://oasis.myguyinthechair.com, like the Android build.
GitHub Actions builds it on a Mac in the cloud and uploads it to TestFlight.

Bundle ID: **com.myguyinthechair.oasis**

## One-time setup (all from a Windows browser)

### 1. Register the app with Apple
1. Go to https://appstoreconnect.apple.com → **Apps** → **+** → **New App**.
2. Platform **iOS**, name **Oasis Social**, language English, SKU `oasis-social`.
3. For Bundle ID, click "Register a new bundle ID" (opens developer.apple.com) and register
   **com.myguyinthechair.oasis** (explicit, no extra capabilities), then come back and pick it.

### 2. Create an App Store Connect API key
1. App Store Connect → **Users and Access** → **Integrations** → **App Store Connect API** → **Team Keys**.
2. Click **+**, name it `GitHub Actions`, access **Admin** (needed so Apple can create the signing certificate for you).
3. Download the `.p8` file — **you can only download it once**. Note the **Key ID** and the **Issuer ID** shown at the top.

### 3. Find your Team ID
https://developer.apple.com/account → **Membership details** → **Team ID** (10 characters).

### 4. Add four secrets to the GitHub repo
Repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:

| Name | Value |
|---|---|
| `APPLE_TEAM_ID` | your Team ID |
| `ASC_KEY_ID` | the API Key ID |
| `ASC_ISSUER_ID` | the Issuer ID |
| `ASC_KEY_P8` | open the .p8 file in Notepad and paste the whole thing, including the BEGIN/END lines |

## Build
Repo → **Actions** → **iOS build → TestFlight** → **Run workflow**. It takes about 10–15 minutes.
When it's green, the build shows up in App Store Connect → your app → **TestFlight** (Apple processes it for another 5–30 minutes).

Install the **TestFlight** app on your iPhone, add yourself as an internal tester, and install Oasis.

Each run gets a new build number automatically. To ship a new version, change `MARKETING_VERSION` in `project.yml`.

## Before submitting to the App Store
- **Guideline 4.2:** Apple often rejects apps that are only a website. Plan on a couple of native features (push notifications, native share sheet, Sign in with Apple if the site offers Google/Facebook login).
- **Account deletion** must be possible inside the app (5.1.1(v)).
- **User-generated content** needs report/block tools and published terms (1.2).

## Files
- `project.yml` — project definition (XcodeGen turns it into an Xcode project during the build)
- `OasisSocial/Sources/` — Swift code (`OasisApp.swift` has the site URL)
- `OasisSocial/Resources/` — app icon and accent color
- `../.github/workflows/ios-testflight.yml` — the cloud build
