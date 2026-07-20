# Google OAuth setup for Split cloud sync (iOS)

The Split SDK signs each user into Google and writes their splits to a private spreadsheet
in *their own* Drive. This is the iOS counterpart to `android/split/OAUTH_SETUP.md` —
iOS uses a different OAuth flow (PKCE via `ASWebAuthenticationSession`) and a different
client type, so the steps differ.

## 1. Create / reuse a Google Cloud project

If you've already done this for Android, **reuse the same project** — both clients share
the OAuth consent screen, scopes, and Sheets/Drive API enablement.

1. https://console.cloud.google.com → top-left dropdown → **New Project** (or pick existing).
2. **APIs & Services → Library** → enable both:
   - Google Sheets API
   - Google Drive API

## 2. Configure the OAuth consent screen

Same as Android — skip if already done.

- User type: **External**
- App name, support email, developer email — fill in.
- **Scopes** → add:
  - `https://www.googleapis.com/auth/spreadsheets`
  - `https://www.googleapis.com/auth/drive.file`
  - `email`
- **Test users**: add your Google accounts while in "Testing" mode.

## 3. Create the iOS OAuth 2.0 client

**APIs & Services → Credentials → Create credentials → OAuth client ID**

- Application type: **iOS**
- Bundle ID: `com.samarth.turtlekeyboard` (must match `PRODUCT_BUNDLE_IDENTIFIER` of the
  TurtleKeyboard host app target — not the keyboard extension)
- **Save**.

You'll get back two things:

| Field | Looks like |
|---|---|
| **Client ID** | `123456789012-abcdefg.apps.googleusercontent.com` |
| **iOS URL scheme** | `com.googleusercontent.apps.123456789012-abcdefg` |

The iOS URL scheme is just the Client ID with the dot-separated parts reversed and the
`.apps.googleusercontent.com` suffix dropped — Google generates it automatically.
Copy both.

## 4. Wire the values into the iOS project

Two files need editing. Both have placeholder strings starting with `REPLACE_WITH`.

### a. `ios/TurtleKeyboard/Cloud/SplitOAuth.swift`

```swift
static let clientID = "123456789012-abcdefg.apps.googleusercontent.com"
static let redirectScheme = "com.googleusercontent.apps.123456789012-abcdefg"
```

### b. `ios/TurtleKeyboard/Info.plist`

Find the `CFBundleURLTypes` array and replace the placeholder under
`com.turtlekeyboard.googleoauth`:

```xml
<key>CFBundleURLSchemes</key>
<array>
    <string>com.googleusercontent.apps.123456789012-abcdefg</string>
</array>
```

That's the same value as `redirectScheme` in step (a). It must match exactly or
`ASWebAuthenticationSession` won't return the auth code.

## 5. Build & test

```bash
xcodebuild build \
  -project ios/TurtleKeyboard.xcodeproj \
  -scheme TurtleKeyboard \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

Then on a device or simulator:

1. Open the Turtle host app.
2. From the Split detail screen (or after `/splits → Report ↗` from the keyboard),
   tap **Sign in**.
3. iOS shows Google's consent web sheet → pick a test account → approve scopes.
4. Within ~1 s the screen flips to "Signed in as <email> · owner".
5. Tap **Sync now** → if you saved any splits before sign-in, they get migrated to a
   new "Turtle Splits" sheet in your Drive (search for it at
   https://drive.google.com/drive/my-drive).
6. Save another split via `/split 1500` in the keyboard → tap **Sync now** in the host
   app → the row appears in the sheet.

## 6. Owner-shared sheets (multi-device, multi-user)

The owner-side invite + joiner-side flow mirrors Android:

1. Owner taps **Invite** in the cloud card → SDK calls
   `Drive.permissions.create` with `type=anyone, role=writer`, returns a
   `turtlekeyboard://join?sheetId=...&owner=...` URL → presented via the share sheet.
2. Joiner opens the URL on their iOS device (via Messages, AirDrop, etc.) → AppDelegate
   routes it to `SplitCloudSync.joinSharedSheet`, which switches the local store onto
   the shared sheet and pulls all rows.
3. Owner taps **Stop sharing** → revokes the anyone-with-link permission.

The shared QR-code rendering UI from Android isn't ported yet — joiners receive a URL
they tap rather than scan.

## 7. Going to production

When you're ready to ship to non-test users:

1. **APIs & Services → OAuth consent screen → Publish app**.
2. Because `drive.file` is non-sensitive, this transitions to "In production" without a
   verification review. `spreadsheets` is sensitive; you'll need to submit for verification
   before non-test users can sign in. See
   https://support.google.com/cloud/answer/9110914 for the verification flow.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "OAuth client ID not set" alert in the app | You skipped step 4 |
| Web sheet opens then immediately closes with an error | Bundle ID in step 3 doesn't match `com.samarth.turtlekeyboard`, OR URL scheme in Info.plist doesn't match `redirectScheme` exactly |
| `HTTP 401: invalid_client` | Client ID typo, or the OAuth client was deleted in Cloud Console |
| User stuck in consent loop | Account not in test users list and app isn't published |
| `drive.file` scope greyed out in consent screen | Google Drive API not enabled in step 1 |
| Sign-in works but Sync now does nothing | Network error in the background task — check Xcode logs for `SplitSheetsClient` errors |
| Token refresh fails after ~1 hour | Refresh tokens come back only on first auth; sign out and back in to get a fresh refresh token |

---

# Notion OAuth setup

`/notion <prompt>` and `/note <prompt>` create a Notion page under the user's chosen
parent. Same architecture as Split: host app does OAuth, extension reads the token from
the shared store.

## 1. Create a Notion integration

1. Go to https://www.notion.so/my-integrations → **+ New integration**.
2. Type: **Public** (Internal won't work — only Public supports OAuth).
3. App name + workspace, capabilities = "Read content, Insert content, Update content".
4. **OAuth Domain & URIs** → Redirect URIs → add the **HTTPS bounce URL**
   `https://turtle-worker.trtlk.workers.dev/oauth/notion`.

   > ⚠️ **Notion does NOT accept custom URL schemes** like
   > `turtleknotionoauth://oauth-callback` — it silently prepends `https://` and
   > the authorize request then fails with *"Missing or invalid redirect_uri."*
   > This is why, unlike Slack, Notion needs the HTTPS → custom-scheme bounce
   > described in step 2b.
5. **Save**. Then **Show** the OAuth client ID and OAuth client secret — copy both.

## 2. Wire into the app

### 2a. Credentials + redirect URI (`.env`)

Set these in the repo-root `.env` (regenerates `Secrets.swift` on next build):
```
NOTION_OAUTH_CLIENT_ID=your-notion-client-id
NOTION_OAUTH_CLIENT_SECRET=your-notion-client-secret
NOTION_OAUTH_REDIRECT_URI=https://turtle-worker.trtlk.workers.dev/oauth/notion
```
`NOTION_OAUTH_REDIRECT_URI` **must exactly match** the Redirect URI registered in
step 4 — it is sent unchanged at both authorize and token-exchange time.

`Info.plist` already has the `turtleknotionoauth` URL scheme registered — no edit
needed unless you change `redirectScheme` in code.

### 2b. Deploy the HTTPS → custom-scheme bounce (Worker)

`ASWebAuthenticationSession` can only catch a custom scheme; Notion can only
redirect to HTTPS. Bridge them with a Worker route at the URL from step 4 that
redirects the auth code back into the app's scheme (client-side JS — a server 302
to a custom scheme is unreliable inside `ASWebAuthenticationSession`):

```js
// GET /oauth/notion?code=...&error=...  → hand the query back to the app
if (url.pathname === "/oauth/notion") {
  const qs = url.searchParams.toString();
  return new Response(
    `<!doctype html><meta charset=utf-8>
     <script>location.replace("turtleknotionoauth://oauth-callback?${qs}");</script>
     <p>Return to Turtle Keyboard…</p>`,
    { headers: { "content-type": "text/html; charset=utf-8" } });
}
```

## 3. Test

1. From the host app's Connect Notion screen → tap **Sign in to Notion**.
2. Browser opens Notion's consent → grant access to one or more pages.
3. Back in the app, the parent picker lists those pages. Pick one.
4. In any chat: `/notion meeting notes for tomorrow's standup` → tap **Create**.
5. Banner: "📓 Page created — link copied". Paste anywhere to share.

If `/notion` says "Connect Notion in the Turtle app" even after sign-in, that's the App
Group caveat — the keyboard extension and host app see different `UserDefaults` until
the App Group entitlement is wired (`group.com.samarth.turtlekeyboard.split`). Add it in
Xcode → Signing & Capabilities for both targets and the token becomes shared.

---

# Slack OAuth setup

`/slack <message>` and `/msg <message>` post to the user's default channel (or
`#channel-name` to override). Same architecture.

## 1. Create a Slack app

1. Go to https://api.slack.com/apps → **Create New App** → From scratch.
2. App name + pick a workspace to develop in.
3. **OAuth & Permissions**:
   - Redirect URLs → add the **HTTPS bounce URL**
     `https://turtle-worker.trtlk.workers.dev/oauth/slack` and **Save URLs**.

     > ⚠️ **Slack does NOT accept custom URL schemes** like
     > `turtleslackoauth://oauth-callback` — Redirect URLs must be HTTPS, or the
     > authorize step fails with *"redirect_uri did not match any configured
     > URIs."* Same HTTPS → custom-scheme bounce as Notion (step 2b below).
   - **User Token Scopes** (NOT Bot Token Scopes) → add:
     - `chat:write`
     - `channels:read`
     - `groups:read`
4. **Basic Information** → **App Credentials** → copy the Client ID and Client Secret.
5. **Install to Workspace** if prompted (or do it from your test device).

## 2. Wire into the app

### 2a. Credentials + redirect URI (`.env`)

```
SLACK_OAUTH_CLIENT_ID=your-slack-client-id
SLACK_OAUTH_CLIENT_SECRET=your-slack-client-secret
SLACK_OAUTH_REDIRECT_URI=https://turtle-worker.trtlk.workers.dev/oauth/slack
```
`SLACK_OAUTH_REDIRECT_URI` **must exactly match** the Redirect URL registered in
step 3 — it is sent unchanged at both authorize and token-exchange time.

`Info.plist` already has the `turtleslackoauth` URL scheme registered.

### 2b. Deploy the HTTPS → custom-scheme bounce (Worker)

Same pattern as Notion §2b — add a Worker route at the URL from step 3 that hands
the auth code back into the app's scheme:

```js
// GET /oauth/slack?code=...&error=...  → hand the query back to the app
if (url.pathname === "/oauth/slack") {
  const qs = url.searchParams.toString();
  return new Response(
    `<!doctype html><meta charset=utf-8>
     <script>location.replace("turtleslackoauth://oauth-callback?${qs}");</script>
     <p>Return to Turtle Keyboard…</p>`,
    { headers: { "content-type": "text/html; charset=utf-8" } });
}
```

## 3. Test

1. Host app's Connect Slack screen → **Sign in to Slack**.
2. Browser opens Slack's authorize → pick the workspace → approve.
3. Channel picker lists every channel you're in. Pick one.
4. In any chat: `/slack hey team — running 5 min late` → tap **Post**.
5. Banner: "💬 Posted to #general — link copied".
6. Override syntax: `/slack #engineering ship it 🚀` posts to `#engineering` instead.

## Slack troubleshooting

| Symptom | Likely cause |
|---|---|
| `redirect_uri did not match any configured URIs` | The HTTPS bounce URL sent (`SLACK_OAUTH_REDIRECT_URI`) isn't registered verbatim in the Slack dashboard Redirect URLs — they must match exactly. Slack does **not** accept custom schemes. |
| "Sign-in cancelled" right after browser opens | The Worker bounce route isn't redirecting to `turtleslackoauth://oauth-callback`, or that scheme isn't in `Info.plist` |
| `Slack: invalid_auth` on post | Token revoked — sign out and back in |
| `Slack: not_in_channel` | Pick a different channel; you're not a member of the one you tried |
| Channel picker is empty | Token has wrong scopes — re-create the Slack app with `channels:read` + `groups:read` as **User** scopes (not Bot) |
