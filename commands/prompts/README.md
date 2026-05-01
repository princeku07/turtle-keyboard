# Shared system prompts

Plain-text system prompts consumed by both the Android and iOS keyboards so
behavior stays in parity. Each file corresponds to a slash command:

| File       | Used by   |
|------------|-----------|
| `org.txt`  | `/org`    |
| `ask.txt`  | `/ask`    |

These are the literal `system` role messages sent to the model. Edit them in
one place and **both platforms pick up the change** at next build:

- **Android** — `android/app/build.gradle.kts` has a `mergeAssetsDebug`-time
  copy task that pulls `commands/prompts/*.txt` into
  `app/src/main/assets/prompts/`. `LmStudioAiClient` loads them with
  `AssetManager.open(...)`.
- **iOS** — add a "Run Script" build phase to the keyboard extension target
  that copies `../commands/prompts/*.txt` into the bundle (Resources). Read
  with `Bundle.main.url(forResource:withExtension:)`.

When changing a prompt, keep the format ASCII-safe except for currency symbols
and other characters the example outputs need verbatim — both readers treat
the file as UTF-8.
