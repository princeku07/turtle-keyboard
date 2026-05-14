# turtle-worker

Cloudflare Worker backend for Turtle Keyboard overlay sheets. Currently serves `/poll` — JSON-backed polls stored in KV. Pure CRUD: every overlay route is a small handler file in `src/routes/`, KV is the only state, no auth, no AI, no DB.

AI generation (terse prompt → poll question + options) lives **on the Android side** via the existing `AiLlm` / `LmStudioAiClient.callGemini` pattern — same path `/cap`, `/us`, `/org` already use. The keyboard generates question + options locally, then POSTs explicit values here.

## Routes

| Method   | Path                  | Body / Headers                                                | Returns                                                  |
|----------|-----------------------|---------------------------------------------------------------|----------------------------------------------------------|
| `GET`    | `/`                   | —                                                             | `{ ok: true, service: "turtle-worker" }` (health)        |
| `POST`   | `/poll`               | `{ question: string, options: string[] }`                     | `{ id, url }` — `url` is the shareable App Link URL      |
| `GET`    | `/poll/:id`           | —                                                             | `{ id, question, options, createdAt }` (voters stripped) |
| `POST`   | `/poll/:id/vote`      | `{ optionIndex: number }` + header `X-Turtle-Device: <opaque>`| `{ ok: true }`                                           |

Errors come back as `{ error: "<code>" }` with the appropriate HTTP status. Codes are stable strings the Android client can switch on: `invalid_json`, `invalid_question`, `question_too_long`, `invalid_options`, `option_too_long`, `missing_device_id`, `invalid_option`, `already_voted`, `poll_not_found`.

## Setup

```bash
cd worker
npm install
npx wrangler login                                # one-time per machine

# Create the KV namespace (one-time per Cloudflare account):
npx wrangler kv:namespace create TURTLE_KV
npx wrangler kv:namespace create TURTLE_KV --preview
# Paste the two returned ids (`id` + `preview_id`) into wrangler.toml.

npm run dev      # local dev at http://127.0.0.1:8787
npm run deploy   # ships to https://turtle-worker.<acct>.workers.dev
npm run tail     # streams production logs
```

## Local testing

Once `wrangler dev` is running:

```bash
# Health
curl -s http://127.0.0.1:8787/

# Create:
curl -s -X POST http://127.0.0.1:8787/poll \
  -H "content-type: application/json" \
  -d '{"question":"Coffee or tea?","options":["☕ Coffee","🍵 Tea"]}'
# → {"id":"k3m2x8a1","url":"https://www.turtlekeyboard.com/poll/k3m2x8a1"}

# Read:
curl -s http://127.0.0.1:8787/poll/k3m2x8a1

# Vote:
curl -s -X POST http://127.0.0.1:8787/poll/k3m2x8a1/vote \
  -H "content-type: application/json" \
  -H "x-turtle-device: test-device-1" \
  -d '{"optionIndex":1}'
```

## Adding a new overlay route

Mirrors the Android `SheetRouter` pattern — each artifact type is self-contained:

1. New file `src/routes/<route-key>.ts` with `create / read / mutate` handlers and a KV key prefix.
2. Add a dispatch block in `src/index.ts` (4 lines: path regex match + method check + handler call + CORS wrapper).
3. Update the Android-side `SheetView` for that route. `PollIntegration` is the template.

No shared logic to coordinate. New routes don't touch other routes.

## Notes

- **KV consistency** — eventually consistent, ~5–10s for writes to propagate to all edges. A vote may not be globally visible immediately. Fine for the use case.
- **Vote dedup** — per `X-Turtle-Device` header (opaque, client-generated, no PII). Re-voting from the same device returns `409 already_voted`. To allow changing a vote later, switch `voters: string[]` to `voters: Record<deviceId, optionIndex>`.
- **TTL** — 90 days per artifact. KV expires the record automatically; no cleanup job needed.
- **Rate limiting** — none yet. Cloudflare's default DDoS protection covers obvious abuse; add per-IP / per-device counters in KV when scale demands it.
- **Auth** — none. Anonymous create + vote, with device-ID dedup. Sufficient for v0.
