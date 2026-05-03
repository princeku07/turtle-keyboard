# Google OAuth setup for Split cloud sync

The Split SDK signs each user into Google and writes their splits to a private spreadsheet
in *their own* Drive. This requires a one-time setup of a Google Cloud project + OAuth
client. Follow these steps once; ship-time changes only require updating the SHA-1
fingerprint of your release keystore.

## 1. Get your app's SHA-1 fingerprints

You'll need two — one for the debug keystore (so dev builds work) and one for the release
keystore (so production builds work).

```bash
# Debug
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
  -storepass android -keypass android | grep "SHA1:"

# Release (use your actual keystore path + creds)
keytool -list -v -keystore /path/to/release.keystore -alias YOUR_ALIAS \
  -storepass YOUR_PASSWORD | grep "SHA1:"
```

Copy both SHA-1 strings.

## 2. Create a Google Cloud project

1. Go to https://console.cloud.google.com → top-left dropdown → **New Project**.
2. Name it (e.g. "Turtle Keyboard"). Note the project ID.
3. **APIs & Services → Library** → enable both:
   - **Google Sheets API**
   - **Google Drive API**

## 3. Configure the OAuth consent screen

**APIs & Services → OAuth consent screen**

- User type: **External**
- App name, support email, developer email — fill in.
- **Scopes** → add `https://www.googleapis.com/auth/drive.file` (auto-suggested as "See, edit, create, and delete only the specific Google Drive files you use with this app").
  - This scope is **non-sensitive** so no Google verification is required for production.
- **Test users** while in "Testing" mode: add the Google accounts you'll test with.
- Save & continue. You can stay in "Testing" indefinitely for personal use, or click **Publish app** to make it available to anyone (still no verification needed for `drive.file`).

## 4. Create OAuth 2.0 client IDs

**APIs & Services → Credentials → Create credentials → OAuth client ID**

You need **two** clients:

### a. Android client (one per signing keystore)

- Application type: **Android**
- Package name: `com.prince.turtlekeyboard`
- SHA-1 fingerprint: paste the **debug** SHA-1
- Click Create. Repeat with **release** SHA-1 → second Android client.

These bind your app's signed APK to the project. No client ID string is needed in code
for this type — Google validates by package name + signature at runtime.

### b. Web client (for offline access / token exchange)

- Application type: **Web application**
- Name: e.g. "Turtle Splits Web"
- No redirect URIs needed.
- Click Create. **Copy the Client ID** that ends in `.apps.googleusercontent.com`.

## 5. Wire the Web client ID into the build

Open or create `android/local.properties` and add:

```
SPLIT_OAUTH_WEB_CLIENT_ID=123456-abcdef.apps.googleusercontent.com
```

(Use **your** value, not this placeholder.)

`split/build.gradle.kts` reads this at build time and exposes it as
`BuildConfig.OAUTH_WEB_CLIENT_ID` to `SplitAuth.java`.

`local.properties` is gitignored by default, so the client ID stays out of source control.

## 6. Build & test

```bash
cd android
./gradlew :split:assembleDebug :app:assembleDebug
```

On a device:

1. Open the host app — the sign-in dialog appears.
2. Tap **Continue with Google** → select an account from the testing list.
3. Approve the `drive.file` scope.
4. App should toast "Signed in — provisioning sheet…"; a "Turtle Splits" sheet now exists in
   your Drive at `https://drive.google.com/drive/my-drive` (search for "Turtle Splits").
5. Save a split via `/split 1500` → the row appears in the user's sheet within ~1 s.
6. Reinstall the app, sign in again with a different test account → that user gets their
   own separate "Turtle Splits" sheet.

## 7. Going to production

When you're ready to ship to non-test users:

1. **APIs & Services → OAuth consent screen → Publish app**.
2. Because `drive.file` is non-sensitive, this transitions to "In production" immediately
   without a verification review.
3. Make sure your release keystore's SHA-1 is registered as an Android OAuth client
   (step 4a). Add Play App Signing's SHA-1 too if you publish via Play.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `SIGN_IN_FAILED` / `DEVELOPER_ERROR` (status 10) | Wrong SHA-1 registered, or wrong package name |
| `400 Bad Request` from authorize() | Web client ID missing or wrong |
| Sign-in works but Sheets calls return 401 | App crashed before storing the access token, or scope wasn't granted — sign out and back in |
| User stuck in consent loop | Account not in test users list and app isn't published |
| `drive.file` scope greyed out | Google Drive API not enabled in step 2 |
