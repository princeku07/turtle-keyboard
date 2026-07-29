# App Store Optimization (ASO) — Turtle Keyboard

Store-listing copy for Google Play (live) and the Apple App Store (in review).
Positioning is **utility-first + open-source**, consistent with the site: Turtle
turns any text field into a command line — polls, quizzes, AI, and connected
tools via `/` slash commands. **Do not** claim on-device processing; the honest
privacy line is "the keyboard only sends what you type after a slash command."

Character limits are hard store maximums — copy below is written to fit.

---

## Keyword strategy

Primary (own these — low competition, exact fit):
- `open source AI keyboard` · `slash command keyboard` · `Turtle Keyboard`

Secondary (category demand):
- `AI keyboard` · `AI keyboard app` · `poll maker` · `group chat poll` ·
  `quiz maker` · `AI keyboard iPhone` / `Android`

Long-tail (feature intent, great for the description body):
- `create a poll in any chat` · `poll for iMessage / WhatsApp` ·
  `quiz in group chat` · `GitHub / Notion in keyboard` · `MCP keyboard` ·
  `private keyboard` · `keyboard that doesn't track you`

Avoid: "offline AI", "on-device AI", "local LLM", "no cloud" — not true, and a
review/PR liability.

---

## Google Play

**Title** (30 chars max)
> Turtle: AI Slash Keyboard

**Short description** (80 chars max)
> Type / in any chat for polls, quizzes, AI & tools. Open-source AI keyboard.

**Full description** (4000 chars max)

> Turtle turns your keyboard into a command line. Type a slash (/) in any app —
> WhatsApp, Instagram, Gmail, your group chat — and bring polls, quizzes, AI,
> and your favorite tools right into the conversation. No app-switching.
>
> And it's fully open source (MIT). You can read exactly what it does.
>
> ⌨️ WHAT YOU CAN DO
>
> • /poll — drop a live, anonymous poll into any chat. Works even in a mixed
>   iPhone + Android group, where native polls give up. Friends just tap a link.
> • /quiz — generate a playable trivia quiz from a prompt, with a live
>   scoreboard the whole chat can see.
> • /cap, /sticker — turn a prompt into an image, sticker, or meme, in line.
> • /summarize, /fix — condense a long thread or clean up a draft, in place.
> • /github, /notion, /linear — connected apps built right into the keyboard.
>   Check a PR or pull a note without leaving the chat.
> • /your-command — anything with an API. Turtle is built on the Model Context
>   Protocol (MCP), so developers can add their own slash commands.
>
> 🔓 OPEN SOURCE, BY DESIGN
>
> A keyboard sees everything you type. Most ask you to trust them. Turtle's code
> is public and MIT-licensed, so you don't have to. The privacy line that matters
> and that you can verify: the keyboard only ever acts on what you type AFTER a
> slash command. Your ordinary typing is never captured, logged, or sent.
>
> 🐢 CALM, NOT LOUD
>
> No feed. No notifications. No always-on suggestion strip nagging over your
> sentence. Nothing happens until you type /. You needed a thing, you got it,
> you're back to your conversation.
>
> ⚡ BUILT FOR THE JOB
>
> Native Android keyboard, small install, no third-party trackers, no analytics
> SDKs. It's a good keyboard first — and a command line when you want one.
>
> Free to use. Open source at github.com/princeku07/turtle-keyboard.
>
> Type slash. Say the thing. Done.

**Tags / category:** Tools (primary) · Productivity
**Contains ads:** No · **In-app purchases:** (set per plan)

---

## Apple App Store

**App Name** (30 chars max)
> Turtle: AI Slash Keyboard

**Subtitle** (30 chars max)
> Slash commands in any chat

**Keywords field** (100 chars max, comma-separated, no spaces, don't repeat
words already in the name/subtitle)
> open,source,poll,quiz,command,tools,github,notion,mcp,productivity,private,group,chat,maker,writing

**Promotional text** (170 chars max — updatable without review)
> New: /poll and /quiz work in mixed iPhone + Android group chats where native
> polls don't. Type / in any app. Open source.

**Description** (4000 chars max)

> Turtle turns your keyboard into a command line. Type a slash (/) in any app —
> Messages, WhatsApp, Instagram, Gmail — and bring polls, quizzes, AI, and your
> favorite tools right into the conversation. No app-switching.
>
> And it's fully open source (MIT) — you can read exactly what it does.
>
> WHAT YOU CAN DO
>
> • /poll — a live, anonymous poll in any chat. Works even when the group is a
>   mix of iPhone and Android, where the built-in Messages poll needs everyone on
>   the latest iOS. Friends just tap a link — nothing to install.
> • /quiz — a playable trivia quiz from a single prompt, with a live scoreboard.
> • /cap, /sticker — an image, sticker, or meme from a prompt, sent in line.
> • /summarize, /fix — condense a thread or tidy a draft, right where you're typing.
> • /github, /notion, /linear — connected apps, built into the keyboard.
> • /your-command — anything with an API, via the Model Context Protocol (MCP).
>
> OPEN SOURCE, BY DESIGN
>
> A keyboard sees everything you type. Most ask for your trust; Turtle earns it
> by being readable. The code is public and MIT-licensed, and the boundary that
> matters is one you can verify: the keyboard only ever acts on what you type
> after a slash. Ordinary typing is never captured, logged, or sent.
>
> CALM BY DEFAULT
>
> No feed, no notifications, no suggestion strip talking over you. Nothing fires
> until you type /.
>
> Free to use. Source: github.com/princeku07/turtle-keyboard

**Notes for the listing**
- Requires Full Access (network) — App Review will ask why: it's needed to
  fulfill slash commands (generate an image, create a poll link, reach a
  connected tool). State plainly that ordinary typing is never transmitted.
- Privacy "nutrition label": declare exactly what a command sends. Do not claim
  zero data collection if commands reach a backend.

---

## Cross-store notes

- **Screenshots** (both stores): lead with the `/` command menu open in a real
  chat, then one screen per hero feature (poll card, quiz scoreboard, generated
  image, GitHub command). Caption each with the command shown.
- **Brand consistency (helps AI/Google entity matching):** always "Turtle
  Keyboard", one-line description identical to the site's, link back to
  turtlekeyboard.com and the GitHub repo from both listings.
- When the App Store listing goes live, set `APP_STORE_URL` in
  `lading-app/lib/store.ts` — the site's badges, links, and app schema update
  automatically.
