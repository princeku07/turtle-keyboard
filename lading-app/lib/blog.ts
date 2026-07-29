/**
 * The Logbook — blog data layer.
 *
 * Posts live here as typed content blocks (not MDX) so every article is
 * fully server-rendered with zero client JS, and adding a post is just
 * appending to the array. Inline text supports a micro-markdown:
 *   **bold** · `code` · [label](href)
 * rendered by app/blog/render.tsx.
 */

export const SITE_URL = "https://www.turtlekeyboard.com";

export type Tag = "guides" | "privacy" | "developers" | "product";

export type Block =
  | { t: "p"; text: string }
  | { t: "h2"; text: string }
  | { t: "h3"; text: string }
  | { t: "ul"; items: string[] }
  | { t: "quote"; text: string }
  | { t: "code"; code: string; file?: string; label?: string }
  | { t: "table"; headers: string[]; rows: string[][] }
  | { t: "faq"; items: Array<{ q: string; a: string }> };

export type Post = {
  slug: string;
  title: string;
  /** meta description — also rendered as the lede paragraph */
  description: string;
  /** ISO date, yyyy-mm-dd */
  date: string;
  updated?: string;
  tag: Tag;
  keywords: string[];
  blocks: Block[];
};

/** the page-as-a-dive narrative: each tag lives at a depth */
export const TAG_DEPTH: Record<Tag, string> = {
  guides: "− 10 m · guides",
  product: "− 20 m · product",
  privacy: "− 1,000 m · privacy",
  developers: "0 m · developers",
};

const POSTS: Post[] = [
  {
    slug: "what-is-an-ai-keyboard",
    title: "What is an AI keyboard? A plain-English guide",
    description:
      "AI keyboards put assistants and tools inside the keyboard itself, so they work in every app. How they work, where your keystrokes go, and what to check first.",
    date: "2026-07-01",
    tag: "guides",
    keywords: [
      "AI keyboard",
      "what is an AI keyboard",
      "smart keyboard",
      "on-device AI",
      "keyboard app",
    ],
    blocks: [
      {
        t: "p",
        text: "Your phone's keyboard is the one piece of software that's present in every app you use. WhatsApp, Gmail, Notion, Slack, your banking app, the dating app you don't talk about — they all summon the same keyboard. An **AI keyboard** takes advantage of that position: it replaces the system keyboard and adds capabilities that follow you into every text field on your phone.",
      },
      {
        t: "p",
        text: "That's the whole idea, and it's why the category is suddenly crowded. If a feature lives inside the keyboard, nobody has to build it into each app. One integration, every app.",
      },
      { t: "h2", text: "What can an AI keyboard actually do?" },
      {
        t: "p",
        text: "The feature lists vary, but almost everything falls into four buckets:",
      },
      {
        t: "ul",
        items: [
          "**Rewrite and fix** — grammar, tone, translation, \"make this sound less angry.\"",
          "**Summarize and answer** — condense a long thread, answer a question without switching to a browser.",
          "**Generate** — draft a reply, a bio, a caption, a follow-up email.",
          "**Run commands** — drop live widgets and tools into the conversation: a poll, a quiz, a snippet pulled from your notes.",
        ],
      },
      {
        t: "p",
        text: "There are also two very different interaction models. Most AI keyboards bolt an **AI suggestion strip** above the keys — a row of buttons that rewrite whatever's in the text field. The other model is a **command line**: you type a trigger character and tell the keyboard what you want. Turtle uses the second one, with slash commands like `/poll` and `/summarize` — the same pattern that's been quietly winning for almost four decades. We wrote up [the history of the slash command](/blog/slash-commands-from-irc-to-your-keyboard) if you want the lineage.",
      },
      { t: "h2", text: "The part nobody puts on the box: where your keystrokes go" },
      {
        t: "p",
        text: "A keyboard occupies the most privileged position on your phone. It sees passwords, medical questions, arguments, addresses — everything you type, in every app. So the single most important question about any AI keyboard isn't \"how smart is it?\" It's **\"what does it send, and when?\"**",
      },
      {
        t: "p",
        text: "**Always-on keyboards** work on what you type continuously — to power predictions, \"improve suggestions,\" or feed telemetry. Your typing is an input to the product whether or not you asked for anything. That's the architecture the scary install warning is really about, and it's how a lot of the current wave operates.",
      },
      {
        t: "p",
        text: "**Command-triggered keyboards** do nothing with your text until you explicitly invoke a command. Real AI writing and image generation need models too big for a phone, so when you *do* run a command it goes to a server — but only that command's content, never your ordinary typing. On-device models exist, but they can't match a frontier model or generate an image, so any keyboard that does those things is making a network call; the honest ones just make it *only* for what you asked.",
      },
      {
        t: "quote",
        text: "The tell isn't where the model runs — it's whether the keyboard sends anything while you're just typing. A trustworthy one is silent until you invoke a command.",
      },
      {
        t: "p",
        text: "We went deep on this — including an audit checklist you can run on any keyboard — in [where do your keystrokes actually go?](/blog/on-device-ai-vs-cloud-keyboards)",
      },
      { t: "h2", text: "What to check before you install one" },
      {
        t: "ul",
        items: [
          "**Is it open source?** A keyboard asks for enormous trust. Open code is the only version of \"trust us\" that can be independently verified.",
          "**When does it touch the network?** Look for an explicit, narrow answer — \"only when you run a command\" — not a vague privacy policy.",
          "**What does it do with your data?** Check the App Store privacy label or Play Data Safety — \"improve our services\" often means your text trains someone's model.",
          "**Is the AI always on, or on request?** A keyboard that only acts when you type a trigger (like `/`) is structurally incapable of quietly analyzing everything else.",
          "**Is it still a good keyboard?** Typing feel, autocorrect, and layout quality still matter more minutes-per-day than any AI feature.",
        ],
      },
      { t: "h2", text: "Where Turtle fits" },
      {
        t: "p",
        text: "Turtle is an [open-source AI keyboard](/open-source-ai-keyboard) for iOS and Android built around three choices: **slash commands** instead of an always-on suggestion strip, a **privacy invariant** — it only ever processes what you type after a slash, never your ordinary typing — and an open [MCP-based plugin model](/blog/build-a-keyboard-command-with-mcp) with connections to apps like **GitHub, Notion, and Linear built directly into the keyboard**, plus room for any developer to add their own. The keyboard clients are MIT-licensed and public.",
      },
      {
        t: "p",
        text: "It's in beta on both platforms — you can [grab a spot on the waitlist](/#waitlist), or dive into [how we think about privacy](/#deep) first.",
      },
    ],
  },
  {
    slug: "build-a-keyboard-command-with-mcp",
    title: "MCP on mobile: turn any API into a keyboard command",
    description:
      "MCP turns any API into a tool an AI can call. Turtle turns MCP servers into slash commands that work in every app — build your own command in about 30 lines.",
    date: "2026-06-22",
    updated: "2026-07-04",
    tag: "developers",
    keywords: [
      "MCP",
      "Model Context Protocol",
      "MCP mobile",
      "keyboard SDK",
      "slash commands",
      "build keyboard extension",
    ],
    blocks: [
      {
        t: "p",
        text: "The **Model Context Protocol (MCP)** is an open standard for connecting AI systems to tools and data. An MCP server wraps some capability — search Jira, toggle the lights, query a database — and describes it in a way any MCP client can discover and call. Since its release in late 2024 it has quietly become the USB port of the AI ecosystem: build the server once, and every compatible client can use it.",
      },
      {
        t: "p",
        text: "Almost all of that energy has gone into desktop clients: IDEs, chat apps, agent frameworks. Which is strange, because the device you type on most is your phone — and the phone has a universal text interface that's active in every single app.",
      },
      { t: "h2", text: "Why a keyboard is the natural MCP client" },
      {
        t: "ul",
        items: [
          "**It's everywhere.** An MCP tool exposed through the keyboard works in WhatsApp, Gmail, Notion, and every other app — without any of those apps integrating anything.",
          "**Slash commands map one-to-one onto tools.** A tool has a name, a description, and typed inputs. So does a slash command. `/jira create` *is* a tool call; the keyboard just gives it a place to live.",
          "**The context is the conversation.** The most useful tool calls need what you're currently writing. The keyboard has the draft; a separate assistant app doesn't.",
        ],
      },
      { t: "h2", text: "Anatomy of a Turtle command" },
      {
        t: "p",
        text: "Turtle's SDK wraps an MCP tool definition in a keyboard-shaped envelope: a trigger, a one-line description for the command menu, and a `run` function that receives the current draft and a `reply` callback that inserts the result into the text field. Here's a complete command that flips your lights through Home Assistant:",
      },
      {
        t: "code",
        file: "lights.ts",
        label: "turtle mcp sdk",
        code: 'import { createTool } from "@turtle/mcp";\n\n// /lights — control Home Assistant without leaving the chat\nexport const lights = createTool("/lights", {\n  describe: "Turn a room\'s lights on or off",\n\n  input: {\n    room: { type: "string", example: "kitchen" },\n    state: { type: "string", enum: ["on", "off"] },\n  },\n\n  async run({ input, reply }) {\n    await hass.callService("light", `turn_${input.state}`, {\n      entity_id: `light.${input.room}`,\n    });\n\n    return reply(`💡 ${input.room} → ${input.state}`);\n  },\n});',
      },
      {
        t: "p",
        text: "Type `/lights kitchen off` in any chat, and the confirmation lands in your text field. No Home Assistant app, no app switch, no bot invited to the group.",
      },
      { t: "h2", text: "Design rules for keyboard-grade tools" },
      {
        t: "p",
        text: "A keyboard command runs in the middle of a conversation, which imposes tighter constraints than a desktop agent:",
      },
      {
        t: "ul",
        items: [
          "**Be fast or be async.** A command should resolve in about two seconds. If the work takes longer, return a link to a live view — that's how `/poll` works.",
          "**One intent per command.** `/lights` does lights. Resist the mega-command with twelve subcommands; discoverability lives in the command menu, not in your argument parser.",
          "**Fail in one clear line.** A command that needs the network and can't reach it should say so immediately — a single legible error, never an infinite spinner.",
          "**Never touch text outside your invocation.** A command receives the draft it was called on — nothing else. This is an architectural invariant in Turtle, and it's [the core of the privacy model](/blog/on-device-ai-vs-cloud-keyboards).",
        ],
      },
      { t: "h2", text: "Getting started" },
      {
        t: "p",
        text: "You don't have to start from zero: Turtle ships with MCP connections to **GitHub, Notion, and other apps built directly into the keyboard**, so commands like `/github` work on day one — the SDK is for the tools only you have. The keyboard is MIT-licensed and the SDK ships with a local test harness, so you can run your command against a simulated text field before it ever touches a phone. Start with the [repo on GitHub](https://github.com/princeku07/turtle-keyboard), and if you build something, the command directory is open for submissions. If it has an API, it can live in your keyboard.",
      },
    ],
  },
  {
    slug: "on-device-ai-vs-cloud-keyboards",
    title: "AI Keyboard Privacy: Where Do Your Keystrokes Actually Go?",
    description:
      "Every AI keyboard sends something to a server. Does it send only your explicit commands, or capture everything you type? A 5-point privacy audit.",
    date: "2026-06-10",
    updated: "2026-07-09",
    tag: "privacy",
    keywords: [
      "AI keyboard privacy",
      "keyboard privacy",
      "private keyboard app",
      "keyboard data collection",
      "keylogger",
      "are AI keyboards safe",
    ],
    blocks: [
      {
        t: "p",
        text: "When you install a third-party keyboard, your phone shows a warning most people have learned to swipe past: this keyboard may be able to collect **all the text you type**, including passwords and credit card numbers. That warning is not theoretical. It's a precise description of the keyboard's position in the stack — and AI features have made the question of what happens next much more interesting.",
      },
      { t: "h2", text: "Every AI keyboard talks to a server — the question is when" },
      {
        t: "p",
        text: "Here's the part the privacy marketing tends to skip: essentially every AI keyboard — Turtle included — sends text to a server to do the actually-smart parts. Rewriting to a high standard, summarizing well, generating an image: those need models far bigger than anything that fits on a phone, so any keyboard that does them is making a network call. \"Does it use the cloud?\" is the wrong question, because the honest answer for the whole category is *yes*. The question that separates a safe keyboard from a spooky one is **what it sends, and when**.",
      },
      { t: "h2", text: "Two kinds of AI keyboard" },
      {
        t: "p",
        text: "The difference that actually protects you isn't where a model runs. It's what triggers the keyboard to do anything with your text at all.",
      },
      {
        t: "ul",
        items: [
          "**Always-on keyboards** work on what you type continuously — to power predictions, \"improve suggestions,\" or feed telemetry. Your typing is an input to the product whether or not you asked for anything. That is exactly the architecture the scary warning is about.",
          "**Command-triggered keyboards** do nothing with your text until you explicitly invoke a command. Type normally and the keyboard is inert; type `/summarize` and only that one request goes out. Turtle is built this way.",
        ],
      },
      {
        t: "quote",
        text: "The invariant that matters: the keyboard never transmits, logs, or analyzes anything you type outside of an explicit command. Not as a policy — as an architecture you can read.",
      },
      { t: "h2", text: "So what actually leaves your phone?" },
      {
        t: "p",
        text: "With a command-triggered keyboard, the honest answer is: only the content of the command you just ran. `/cap a samurai cat` sends that prompt to generate the image; `/summarize` sends the thread you pointed it at. Everything else you type — the password, the message you wrote and deleted, the address, the argument — is never captured, because there is no code path that touches it. Name the trade-off plainly: those commands *do* reach a server, and their content is processed there. What you're buying isn't offline magic. It's that the keyboard is not a keylogger, and that you can prove it.",
      },
      { t: "h2", text: "Why open source is the deciding factor" },
      {
        t: "p",
        text: "Every claim above is verifiable in exactly one situation: when the code is public. A closed keyboard's \"we only send your commands\" is a promise. An open keyboard's is a diff — one you, or the many people who've already looked, can read. That's the real privacy lever in 2026: not on-device versus cloud, but **auditable versus \"trust us.\"** It's the reason a keyboard that sends commands to a server can still be the more trustworthy choice than one that swears it keeps everything local behind a binary you can't inspect.",
      },
      { t: "h2", text: "A five-point audit for any AI keyboard" },
      {
        t: "ul",
        items: [
          "**Watch it on a network monitor.** A well-behaved keyboard is boring: silent while you just type, one short burst when you run a command. Constant chatter as you type is the tell.",
          "**Read the privacy label**, not the policy. On iOS, check \"Data Linked to You\" on the App Store page; on Android, the Data Safety section. \"Improve our services\" is doing a lot of work in most of them.",
          "**Ask when the network is used.** A trustworthy answer is specific: which actions, what payload, retained how long. Vague answers are answers too.",
          "**Check for open source.** You cannot verify a binary's promises. You can verify code — or at least know that thousands of other people can.",
          "**Check what it does while idle.** The question isn't whether it *can* reach the network — it's whether it sends anything at all when you're just typing, not running a command.",
        ],
      },
      {
        t: "p",
        text: "This is the standard we hold [Turtle](/open-source-ai-keyboard) to, and the reason the keyboard is open source: a keyboard should earn its position in your stack, in the open. If you're evaluating the category more broadly, start with [what an AI keyboard actually is](/blog/what-is-an-ai-keyboard).",
      },
    ],
  },
  {
    slug: "create-a-live-poll-in-any-chat-app",
    title: "How to Poll a Group Chat With Both iPhone and Android Users",
    description:
      "iMessage polls need everyone on iOS 26. WhatsApp's stay in WhatsApp. How to run one poll — or a prompted quiz — that every phone in the group can vote on.",
    date: "2026-05-28",
    updated: "2026-07-04",
    tag: "product",
    keywords: [
      "poll in group chat iPhone and Android",
      "iMessage poll Android users",
      "cross-platform group chat poll",
      "iMessage poll without iOS 26",
      "live poll link",
      "group chat quiz",
    ],
    blocks: [
      {
        t: "p",
        text: "Every group chat has one: the friend whose Android phone turns the bubbles green, or the uncle who refuses to update past iOS 17. And the moment they're in the thread, the platform's shiny native features stop applying to your group. Polls are the newest casualty — iOS 26 finally gave iMessage built-in polls, and Apple's own fine print carries the catch: **everyone in the conversation must be on iMessage, on iOS 26**. One green bubble and the feature evaporates for the whole chat.",
      },
      {
        t: "p",
        text: "Real group chats are mixed. That's not the edge case — it's the median. So here's the actual state of group-chat polling in 2026, and the one method that works across all of it.",
      },
      { t: "h2", text: "Why native polls keep failing the group chat" },
      {
        t: "ul",
        items: [
          "**iMessage** — polls exist as of iOS 26, but only if every single participant is on iOS 26 iMessage. An Android phone, an SMS/RCS bridge, or one un-updated iPhone in the chat, and there are no polls for anyone ([here's exactly why they vanish](/blog/imessage-polls-not-working)).",
          "**WhatsApp** — solid basic polls, but they live and die inside WhatsApp ([full walkthrough here](/blog/how-to-create-a-poll-in-whatsapp)) — and every vote has your name on it.",
          "**Slack / Discord** — possible with a workspace app or bot, which needs an admin, which is not a sentence anyone wants to say about Friday plans.",
          "**SMS and RCS** — \"reply 1, 2, or 3\" and someone hand-counts. It's 2026.",
        ],
      },
      { t: "h2", text: "The fix: one link every phone can open" },
      {
        t: "p",
        text: "A poll that lives at a link doesn't care what phone anyone has. With [Turtle](/) installed, the flow is identical in iMessage, WhatsApp, Messenger, or a plain SMS thread:",
      },
      {
        t: "ul",
        items: [
          "Type `/poll` in any conversation — the command menu rises above the keys.",
          "Write the question and options in one line: `/poll Movie, bowling, or drinks?`",
          "Send. The keyboard drops an interactive link that unfurls into a live poll card in the chat.",
          "**Everyone votes in their browser** — an iPhone on iOS 26, a five-year-old Android, a laptop. Nothing to install, no account, no green-bubble politics.",
          "Results update live for the whole thread, and votes are anonymous by default.",
        ],
      },
      {
        t: "quote",
        text: "The poll doesn't run inside any chat app — that's the trick. It runs at a link, and links are the one thing every platform since the '90s agrees on.",
      },
      { t: "h2", text: "Not just polls: prompt a quiz, pick a theme" },
      {
        t: "p",
        text: "Because the widget is generated on the spot, it isn't limited to a ballot box. Type `/quiz 90s music trivia` and Turtle builds a playable quiz from that one prompt — questions, answers, and a live scoreboard the whole chat can watch. Both polls and quizzes come in **multiple UI themes**, so the game-night quiz, the wedding-planning poll, and the fantasy-league vote each get their own look instead of the same gray widget. And since it's all one interactive link, you can drop the *same* poll into the iMessage thread and the WhatsApp group and watch a single tally update live across both.",
      },
      { t: "h2", text: "Why keyboard-level beats a bot or a separate app" },
      {
        t: "ul",
        items: [
          "**Portability.** One command, every app. The poll works in the iMessage family chat and the Slack channel with the same muscle memory.",
          "**No admin, no permission.** A Slack poll app needs workspace approval. A keyboard command needs nothing from anyone else.",
          "**It works in DMs.** Bots can't join a two-person conversation. Your keyboard is already there.",
          "**Only the creator needs anything.** Voters just tap a link — the keyboard is only needed by the person making the poll.",
        ],
      },
      {
        t: "p",
        text: "`/poll` and `/quiz` ship with the Turtle beta on iOS and Android — and you can [create a poll link in your browser](/poll-maker) right now, no keyboard or account required. If your group chat spans two platforms and zero decisions, [the waitlist is open](/#waitlist); and if everyone you know is miraculously on WhatsApp, the [WhatsApp-specific guide](/blog/how-to-create-a-poll-in-whatsapp) covers both methods there.",
      },
    ],
  },
  {
    slug: "slash-commands-from-irc-to-your-keyboard",
    title: "Slash commands, from IRC to your keyboard: a brief history of /",
    description:
      "The slash command is older than the web browser and keeps winning: IRC, Slack, Notion — now the keyboard. A short history, and why it beat the chatbot.",
    date: "2026-05-14",
    tag: "product",
    keywords: [
      "slash commands",
      "slash command history",
      "IRC commands",
      "command palette",
      "text interface",
      "keyboard commands",
    ],
    blocks: [
      {
        t: "p",
        text: "In 1988, a Finnish student named Jarkko Oikarinen built Internet Relay Chat and made a small design decision with a long shadow: anything starting with `/` was an instruction, everything else was a message. `/join #channel`. `/msg`. `/whois`. The slash was the boundary between talking *in* the room and talking *to* the room.",
      },
      {
        t: "p",
        text: "Thirty-eight years later, that convention has outlived almost every interface trend that was supposed to replace typing — and it's still spreading. It's worth asking why.",
      },
      { t: "h2", text: "Why the slash survived" },
      {
        t: "ul",
        items: [
          "**It's discoverable.** Type the trigger and a menu appears. Unlike a CLI, you don't memorize; unlike a toolbar, it doesn't take up space until asked.",
          "**It's fast.** Your hands are already typing. A command is three keystrokes away from anything you're writing — no reach for a mouse, menu, or other app.",
          "**It's precise.** `/remind me in 20 minutes` is unambiguous in a way natural language requests to an assistant still aren't. You say exactly what you want; it does exactly that.",
        ],
      },
      { t: "h2", text: "Slack and Discord make it mainstream" },
      {
        t: "p",
        text: "IRC's slash stayed a power-user dialect for two decades until Slack (2013) turned it into a product surface: `/remind`, `/giphy`, and — crucially — an API so any developer could register commands. Discord followed and went further, making slash commands the *official* bot interface, with typed arguments and autocomplete. A generation that never saw IRC now types `/` reflexively in any chat box, the way an earlier one double-clicked.",
      },
      { t: "h2", text: "Notion turns it into creation" },
      {
        t: "p",
        text: "The second act came from an unexpected direction: documents. Notion made `/` the way you create anything — `/table`, `/heading`, `/embed` — and the pattern spread to Linear, Figma, GitHub, and every editor shipped since. This mattered because it proved the slash wasn't a chat gimmick. It's a general-purpose **command palette that lives inside the text itself**, summoned exactly where your attention already is.",
      },
      { t: "h2", text: "The gap: your phone" },
      {
        t: "p",
        text: "Here's the strange part. Typing moved to phones years ago — most of the world's text is now written with thumbs — but slash commands stayed on the desktop. On your phone, each app either builds its own commands or has none, and the thing every text field shares, the keyboard, has spent the mobile era autocorrecting \"duck.\"",
      },
      {
        t: "p",
        text: "That's the gap [Turtle](/) is built for: put the `/` in the keyboard itself, and every text field on the phone — iMessage, Gmail, Notion, all of it — gets the same command palette. Type `/poll` in a group chat ([here's how that works](/blog/create-a-live-poll-in-any-chat-app)), `/summarize` in a long email thread, or [build your own command](/blog/build-a-keyboard-command-with-mcp) for anything with an API.",
      },
      {
        t: "quote",
        text: "The lesson of thirty-eight years: interfaces that live inside the text outlast interfaces that live beside it. The slash keeps winning because it's exactly where your hands already are.",
      },
      {
        t: "p",
        text: "The chatbot era made a different bet — that we'd rather describe what we want in prose and wait. Sometimes, sure. But for the hundred small actions a day where you know *exactly* what you want, the 1988 answer is still the fastest one ever shipped: type a slash, say the thing, done.",
      },
    ],
  },
  {
    slug: "local-llms-in-an-ios-keyboard",
    title: "The Brutal Math of Running Local LLMs in an iOS Keyboard",
    description:
      "A keyboard extension gets ~60 MB before jetsam kills it; a useful LLM wants gigabytes. Why on-device AI in a keyboard is brutally hard — and what we do instead.",
    date: "2026-07-04",
    updated: "2026-07-09",
    tag: "developers",
    keywords: [
      "on-device LLM iOS",
      "keyboard extension memory limit",
      "local LLM mobile",
      "quantized models iOS",
      "jetsam keyboard extension",
      "Core ML keyboard",
    ],
    blocks: [
      {
        t: "p",
        text: "Here is the entire problem in two numbers. A 1-billion-parameter model in float16 weighs about **2 GB**. An iOS keyboard extension gets roughly **60 MB** of memory before jetsam — Apple's out-of-memory killer — terminates it mid-keystroke, with no crash dialog and no second chance. Apple doesn't document the exact ceiling; you discover it empirically, by crashing. Everything interesting about running AI inside a keyboard lives in the gap between those two numbers.",
      },
      { t: "h2", text: "The budget, honestly accounted" },
      {
        t: "p",
        text: "The ~60 MB isn't even yours. Before any model loads, a working keyboard has already spent a chunk of it:",
      },
      {
        t: "ul",
        items: [
          "**UIKit + the view hierarchy** — key caps, the command menu, haptics plumbing: 15–25 MB on a bad day.",
          "**The boring keyboard features** — autocorrect dictionaries, layout data, user settings: another 5–10 MB.",
          "**Headroom for spikes** — jetsam watches your *footprint*, and a single autoreleased image or an over-eager cache can spike you over the line. You want margin, not a full tank.",
        ],
      },
      {
        t: "p",
        text: "So the realistic budget for \"AI\" is 20–30 MB of dirty memory, on a good device. We check it at runtime rather than guessing:",
      },
      {
        t: "code",
        file: "InferenceGate.swift",
        label: "ios · swift",
        code: '// Apple never documents the extension ceiling — measure, don\'t guess.\nimport os\n\nlet headroom = os_proc_available_memory()\n\nguard headroom > 24 * 1024 * 1024 else {\n  // not enough room to wake the model on this device, right now.\n  // degrade to the rules-based engine instead of gambling with jetsam.\n  return .fallback(.rulesBased)\n}',
      },
      { t: "h2", text: "Trick one: weights that don't count (much)" },
      {
        t: "p",
        text: "The first tool is quantization: 4-bit weights shrink a model ~4× versus float16, and keyboard-scale tasks tolerate it well. The second is more important: **memory-map the weights instead of loading them**. An `mmap`'d read-only file produces *clean* pages — the kernel can evict and re-fault them at will, so they count very differently against your footprint than allocated buffers do. You keep the weights in the app group container and map them on demand.",
      },
      {
        t: "p",
        text: "What you *can't* mmap away is the working state: activations and, above all, the **KV cache**, which grows with every token of context. That's real, dirty, unavoidable memory. So the in-keyboard model runs with a deliberately short context window and a hard-capped, pre-allocated KV budget — a few megabytes, reused across invocations, never grown under pressure.",
      },
      { t: "h2", text: "Trick two: split the work" },
      {
        t: "p",
        text: "Even with quantization and mmap, a keyboard extension is the wrong place for a 3B-parameter model. The on-device playbook is to split it:",
      },
      {
        t: "ul",
        items: [
          "**A nano engine, in-process.** A sub-billion-parameter, 4-bit model for the instant tasks — tone, short rewrites. Weights mmap'd, KV capped, first token in tens of milliseconds.",
          "**A larger engine, in the host app.** The companion app has ordinary app memory limits and full access to the Neural Engine via Core ML. Heavier work is handed across the app group boundary and run there.",
          "**A deterministic floor.** When `os_proc_available_memory()` says no — old device, hostile moment — commands that can degrade to rules-based logic do, and the ones that can't say so in one line instead of hanging.",
        ],
      },
      {
        t: "p",
        text: "It's a real architecture, and genuinely impressive when it lands. It's also, we concluded, the wrong trade for what Turtle is trying to be.",
      },
      { t: "h2", text: "So does Turtle run the model in the keyboard? No." },
      {
        t: "p",
        text: "Here's the honest part. All of that engineering buys you *small-model* output: passable rewrites, maybe summarization. It does not buy you image generation, and it loses a writing-quality contest to a frontier model by a margin your users will feel. For a keyboard whose pitch includes `/cap` and genuinely good results, that's disqualifying. So Turtle spends its 60 MB budget on being a fast, reliable keyboard and routes AI commands to a backend, where a real model can actually answer them.",
      },
      {
        t: "p",
        text: "Which means the privacy story isn't \"the model is local\" — it's a harder line that survives using a backend: only the content of an explicit command is ever sent, ordinary typing never is, and the [clients are open source](https://github.com/princeku07/turtle-keyboard) so you can [verify that boundary](/blog/why-your-keyboard-shouldnt-talk-to-the-cloud) rather than take it on faith.",
      },
      { t: "h2", text: "The cage shapes everything anyway" },
      {
        t: "p",
        text: "Even without a model living inside it, the 60 MB ceiling still governs the whole design — it's why we [went native on both platforms](/blog/the-case-for-cross-platform-native) instead of shipping a framework runtime that eats the budget, and why the keyboard has to stay lean and boot instantly every time a text field takes focus. New to keyboard extensions? Start from scratch with our [iOS custom keyboard tutorial](/blog/ios-custom-keyboard-extension-tutorial), and the [source is open](https://github.com/princeku07/turtle-keyboard) if you want to see how the budget actually gets spent.",
      },
    ],
  },
  {
    slug: "bringing-mcp-to-mobile-keyboards",
    title: "Bringing the Model Context Protocol to Mobile Keyboards",
    description:
      "How we built a decentralized MCP host inside iOS and Android keyboard extensions — turning every text field into a universal command line, no gatekeeper.",
    date: "2026-07-03",
    tag: "developers",
    keywords: [
      "MCP host",
      "Model Context Protocol mobile",
      "MCP keyboard",
      "mobile agents",
      "keyboard extension architecture",
      "universal command line",
    ],
    blocks: [
      {
        t: "p",
        text: "The Model Context Protocol solved a coordination problem: instead of every AI app integrating every tool, a tool ships one **MCP server** and every **MCP host** can use it. Two years in, the hosts are everywhere — IDEs, desktop chat apps, agent frameworks — with one glaring exception. The device you type on most has no serious MCP host at all. We decided the right place to put one is the strangest process on the phone: the keyboard extension.",
      },
      { t: "h2", text: "Why the keyboard is the right host" },
      {
        t: "ul",
        items: [
          "**It's the only universal surface.** A host inside WhatsApp works in WhatsApp. A host inside the keyboard works in every text field on the phone — no per-app integration, ever.",
          "**Slash commands are tool calls with better UX.** An MCP tool has a name, a description, and a typed input schema. So does a slash command. `/notion` isn't *like* a tool invocation; it is one, with autocomplete.",
          "**The context is already there.** The most useful tool calls want the text you're currently writing. The keyboard holds the draft; no other host can say that without screen-scraping.",
        ],
      },
      { t: "h2", text: "A host in a hostile process" },
      {
        t: "p",
        text: "A keyboard extension is a terrible place to run infrastructure, which is what makes the design interesting. The process is spawned and killed constantly, gets [about 60 MB of memory on iOS](/blog/local-llms-in-an-ios-keyboard), and can't hold long-lived connections. So the host is built to be **stateless and lazy**: the command registry — which servers exist, what tools they expose — is compiled into a compact index in the shared app group container. Opening the command menu reads the index; nothing connects until you actually commit a command. Full tool schemas hydrate on demand and are cached with the server's content hash.",
      },
      {
        t: "p",
        text: "Servers come in three transports, and the host treats them identically:",
      },
      {
        t: "ul",
        items: [
          "**In-process** — the no-network pieces that live in the keyboard itself: the command registry and lightweight, deterministic text transforms. Instant, and they don't touch the network.",
          "**Companion** — servers running inside the host app on the same phone, for work that needs more memory or background time than the extension gets.",
          "**Remote** — hosted MCP servers reached over HTTPS. This is where the real AI lives — rewriting, summarizing, image generation — and Turtle ships with ready-made connections for the big tools (**GitHub, Notion, Linear**, so `/github` works out of the box), plus any server you add by URL, like the one you wrote last weekend for your home automation.",
        ],
      },
      {
        t: "code",
        file: "registry/notion.turtle.json",
        label: "mcp registry",
        code: '{\n  "name": "notion",\n  "transport": { "type": "https", "url": "https://mcp.notion.com" },\n  "commands": [\n    {\n      "trigger": "/notion",\n      "tool": "search_and_insert",\n      "describe": "pull from your workspace"\n    }\n  ],\n  "permissions": ["network"],\n  "schemaHash": "sha256:9f2c…"\n}',
      },
      { t: "h2", text: "Decentralized on purpose" },
      {
        t: "p",
        text: "The important word in the architecture is the one that isn't code: there is **no central command store**. Turtle ships a default set, but the registry is just files — any server the user adds is a first-class citizen, namespaced by server, permissioned per server (a server without the `network` grant never touches the radio), and removable in one tap. Nobody at Turtle approves your command, takes a revenue cut, or can remove it from your phone. The protocol is open, [the host is open source](https://github.com/princeku07/turtle-keyboard), and the whole point is that the ecosystem outgrows us.",
      },
      { t: "h2", text: "What a keystroke actually does" },
      {
        t: "p",
        text: "Type `/` and the indexed menu renders from the app group cache — no I/O worth naming. Pick `/notion`, type your query, hit send: the host validates the input against the cached schema, opens one connection, makes one tool call, and inserts the reply into the text field through the platform's input APIs. Local tools resolve in tens of milliseconds; remote ones carry a visible in-flight state and a ~2-second budget before we tell you what's wrong. Then the connection closes, the host goes back to being a keyboard, and nothing persists except what you chose to type.",
      },
      {
        t: "p",
        text: "If you want to put your own API in everyone's keyboard, the tutorial is [here](/blog/build-a-keyboard-command-with-mcp) — a working command is about thirty lines.",
      },
    ],
  },
  {
    slug: "the-case-for-cross-platform-native",
    title: "Stop Building Wrappers: The Case for Cross-Platform Native",
    description:
      "A keyboard isn't an app — it's a latency-critical service in a 60 MB cage. Why we skipped React Native for native Swift and Kotlin, and what we share instead.",
    date: "2026-06-26",
    tag: "developers",
    keywords: [
      "React Native vs native",
      "cross-platform development",
      "keyboard extension performance",
      "Swift Kotlin",
      "InputMethodService",
      "typing latency",
    ],
    blocks: [
      {
        t: "p",
        text: "Admitting you wrote the same product twice — once in Swift, once in Kotlin — is 2026's version of admitting you don't unit test. The entire industry consensus says wrap it: React Native, Flutter, a WebView in a trench coat. We tried the consensus first. Then we profiled a keystroke, and the consensus lost.",
      },
      { t: "h2", text: "A keyboard is not an app" },
      {
        t: "p",
        text: "Cross-platform frameworks are built for apps: screens you open, scroll, and close. A keyboard is a different animal — on Android it's an `InputMethodService`, on iOS a `UIInputViewController` extension. It is a **service the OS summons and executes constantly**, in a process it kills without ceremony. That shape breaks every assumption a wrapper makes:",
      },
      {
        t: "ul",
        items: [
          "**Cold start is the product.** The keyboard boots every time a text field gains focus. A JavaScript VM plus framework runtime adds hundreds of milliseconds before the first frame — an eternity you pay dozens of times a day, at the exact moment the user wants to type.",
          "**The memory cage.** An iOS keyboard extension gets [roughly 60 MB before jetsam kills it](/blog/local-llms-in-an-ios-keyboard). A JS engine and bridge can eat half of that at idle — half your budget, spent on plumbing, before you've drawn a single key.",
          "**Keystrokes can't cross a bridge.** Every tap must become a glyph inside a 16 ms frame. Routing input events through a serialization bridge into a garbage-collected runtime and back adds jitter exactly where humans are most sensitive to it. Typing feel is the one thing a keyboard cannot fumble.",
          "**The interesting APIs are native anyway.** `os_proc_available_memory`, the input-method lifecycle, Android's `InputConnection`, the text-document proxy — the layer a keyboard actually lives in has no meaningful cross-platform abstraction.",
        ],
      },
      { t: "h2", text: "\"Cross-platform\" is a spectrum, not a framework" },
      {
        t: "p",
        text: "Rejecting the wrapper doesn't mean writing everything twice. It means being deliberate about *which layer* is shared. Nothing that touches a finger is shared; everything that doesn't, is:",
      },
      {
        t: "ul",
        items: [
          "**The [MCP host protocol](/blog/bringing-mcp-to-mobile-keyboards)** — registry format, tool schemas, transport rules — is a spec, identical on both platforms and testable without a device.",
          "**The command registry and model artifacts** ship in one format; quantized weights don't care about your UI framework.",
          "**The design system** — the sand, the navy, the turquoise — lives as tokens that compile to both platforms.",
          "**The key-press path, the view layer, the inference glue** are written twice, natively, on purpose. That's the part users feel 3,000 times a day.",
        ],
      },
      {
        t: "quote",
        text: "The rule we ended up with: share the protocol, not the pixels. Specs are free to share. Frames are not.",
      },
      { t: "h2", text: "When you should absolutely use a wrapper" },
      {
        t: "p",
        text: "This is not a sermon against React Native. If you're building a content app — screens, lists, forms, a checkout — the wrapper is the right call, and shipping one codebase will beat our approach on every business metric. The calculus flips only when your product *is* the input path: keyboards, anything latency-critical running in an extension sandbox. Then every megabyte and millisecond the framework spends is spent from your product's budget — and being twice as careful means, sometimes, writing it twice.",
      },
      {
        t: "p",
        text: "Both codebases are [open source](https://github.com/princeku07/turtle-keyboard) — the Swift and Kotlin trees sit side by side in the repo, so you can judge the trade for yourself.",
      },
    ],
  },
  {
    slug: "why-your-keyboard-shouldnt-talk-to-the-cloud",
    title: "The One Thing Your Keyboard Should Never Do",
    description:
      "Your keyboard sees passwords and unsent drafts before encryption. The real threat isn't the cloud — it's a keyboard that sends what you didn't ask it to.",
    date: "2026-06-29",
    updated: "2026-07-09",
    tag: "privacy",
    keywords: [
      "keyboard privacy",
      "AI keyboard privacy",
      "private keyboard",
      "keyboard data security",
      "secure keyboard",
      "what keyboards collect",
    ],
    blocks: [
      {
        t: "p",
        text: "Think about what your keyboard saw today. The password you typed before your manager's name. The symptom you searched at lunch. The message you wrote, stared at, and deleted without sending. Your keyboard is the only software on your phone that sees **all of it, in every app, before encryption** — Signal's end-to-end encryption protects the pipe, but the keyboard sits upstream of the pipe. That deleted message? End-to-end encryption never even got the chance to protect it. The keyboard saw it anyway.",
      },
      {
        t: "p",
        text: "So when an AI keyboard sends text to a server — and any keyboard that does real AI writing or image generation must, because those models don't fit on a phone — the question isn't whether the company is nice. It's *which* text leaves: only what you explicitly asked it to act on, or whatever you happen to type.",
      },
      { t: "h2", text: "The threat model nobody prints on the box" },
      {
        t: "p",
        text: "Any text that lands on someone else's computer — however briefly, however encrypted in transit — opens five doors that no policy can fully close:",
      },
      {
        t: "ul",
        items: [
          "**The breach.** Server-side text is a target. Companies with world-class security teams get breached; keyboard startups running on margin are not companies with world-class security teams.",
          "**The subpoena.** Data that exists can be legally demanded. Data that was never sent cannot.",
          "**The insider.** Someone operates those servers. Access controls are a policy; policies have exceptions and bad days.",
          "**The training run.** \"We may use data to improve our services\" is the quiet clause doing loud work in most AI keyboard policies. Your messages become gradient updates.",
          "**The acquisition.** The startup with the lovely privacy policy gets bought. The policy is renegotiated by the new owner; your historical data attends the meeting.",
        ],
      },
      {
        t: "quote",
        text: "None of these require anyone to be evil. They only require the data to exist somewhere other than your phone. Which is why how much of your typing leaves matters more than any promise about what happens to it once it's gone.",
      },
      { t: "h2", text: "\"We encrypt everything\" answers the wrong question" },
      {
        t: "p",
        text: "TLS in transit and encryption at rest protect data from third parties — not from the party you're worried about, which is the service itself. To run a model on your text, the server must decrypt it. Every AI keyboard that processes text server-side, however honest, terminates in a place where your words are plaintext on hardware you don't control. That's not a bug; it's the definition of running a big model on your writing. So the defense can't be \"encrypt harder.\" It has to be **sending less**.",
      },
      { t: "h2", text: "The boundary that matters: commands, not keystrokes" },
      {
        t: "p",
        text: "Here's the design that actually shrinks the threat model. The only text that ever leaves is the content of a command you explicitly invoked. `/cap a samurai cat` sends that prompt to generate the image; `/summarize` sends the thread you pointed it at. The password you typed, the message you deleted, the symptom you searched — none of it is ever sent, because there is **no code path from the key-press handler to a socket for anything but a command**. That turns the five-door threat model from \"everything you type\" into \"the handful of things you deliberately asked for.\"",
      },
      {
        t: "p",
        text: "The word doing the work there is *verifiable*. A closed keyboard can claim the exact same boundary, and you'd be trusting a binary. Turtle's clients are [open source](https://github.com/princeku07/turtle-keyboard), so that boundary is a line of code you — or the many people who've already looked — can read, not a sentence in a privacy policy. That's the [shell principle](/open-source-ai-keyboard): the keyboard has exactly one door, it opens only when you type a command, and you can inspect the lock.",
      },
      {
        t: "p",
        text: "For the fuller comparison — including a five-point audit you can run on any keyboard in ten minutes — see [AI keyboard privacy: where do your keystrokes go?](/blog/on-device-ai-vs-cloud-keyboards).",
      },
    ],
  },
  {
    slug: "zero-server-costs-local-inference",
    title: "The Inference Tax: Why AI Features Cost What They Cost",
    description:
      "Every AI feature you use is a per-request bill someone pays. How that tax shapes free tiers and subscriptions — and why the ugly fix is selling your data.",
    date: "2026-06-19",
    updated: "2026-07-09",
    tag: "privacy",
    keywords: [
      "AI unit economics",
      "API costs LLM",
      "inference cost",
      "AI product pricing",
      "sustainable AI products",
      "AI keyboard cost",
    ],
    blocks: [
      {
        t: "p",
        text: "Every AI product built on model APIs carries the same line item, and it never goes to zero: **inference**. A fraction of a cent to a few cents per request sounds like nothing until you multiply it — thirty commands a day, times 365, times every user, forever. Unlike servers you optimize or code you refactor, this cost scales *exactly* as fast as your success does. Success, in the API model, is a bill — and that bill quietly shapes almost every AI app on your phone, ours included.",
      },
      { t: "h2", text: "How the inference tax shapes products" },
      {
        t: "p",
        text: "You can read the tax in the product decisions of nearly every AI app on your phone:",
      },
      {
        t: "ul",
        items: [
          "**Free tiers shrink** — ten requests a day, then five, then a trial. Not stinginess; arithmetic.",
          "**Everything becomes a subscription**, because a one-time purchase can't fund a forever-cost.",
          "**Engagement gets rationed and monetized at once** — the product must keep you hooked enough to pay, but each use costs the company money. That tension produces strange, anxious software.",
          "**Your data becomes the offset.** When inference is expensive, \"we may use your data to improve our services\" stops being a legal nicety and starts being a revenue line. This is the one that matters.",
        ],
      },
      { t: "h2", text: "So why not just run it on the phone?" },
      {
        t: "p",
        text: "It's the obvious escape: modern phones ship with neural accelerators sitting mostly idle, and running a model there would drop the marginal cost of a command to zero. For *some* tasks that's a real strategy. But it hits a wall exactly where a keyboard earns its place — a phone-sized model can't generate an image, and it loses a writing-quality contest to a frontier model by a margin you'd notice. A keyboard whose whole pitch is `/cap` and genuinely good rewrites can't live entirely on-device today. So Turtle, like every keyboard that actually does those things, routes them to a backend and pays the tax. Pretending otherwise would be marketing, not architecture.",
      },
      { t: "h2", text: "The tax we refuse to offset with your data" },
      {
        t: "p",
        text: "Paying the tax is fine. The ugly move is that last bullet — quietly turning your keystrokes into the revenue that covers the bill. That's where a lot of \"free\" AI keyboards get their margin back, and it's the one option Turtle's design takes off the table. The keyboard only ever receives the content of a command you explicitly ran; your ordinary typing is never sent, so it can't be logged, sold, or trained on even if a spreadsheet made the case for it. And because the [clients are open source](/blog/why-your-keyboard-shouldnt-talk-to-the-cloud), that isn't a promise — it's checkable.",
      },
      {
        t: "p",
        text: "Which leaves the boring, honest business answer: the free beta is subsidized, a paid tier would eventually fund the expensive part (frontier models, heavy image work), and your data is never the product. We'd rather charge for the costly capability than sell the private thing — that's also the economic root of keeping the keyboard [calm and unmonetized in your attention](/blog/building-calm-ai).",
      },
      {
        t: "quote",
        text: "Every AI product pays the inference tax. The only real choice is whether you recover it with a fair price — or quietly, with the user's data. We chose the bill.",
      },
    ],
  },
  {
    slug: "building-calm-ai",
    title: "The Anti-Hustle Tech Stack: Building Calm AI",
    description:
      "Every app has a sparkle emoji begging for attention. We build the opposite: AI that appears only when you type a slash. Notes on calm technology.",
    date: "2026-06-15",
    tag: "privacy",
    keywords: [
      "calm technology",
      "calm AI",
      "anti-hustle",
      "mindful tech",
      "attention economy",
      "quiet software",
    ],
    blocks: [
      {
        t: "p",
        text: "Somewhere in the last two years, AI became the loudest thing on your phone. Every app grew a sparkle emoji. Every text field wants to finish your sentences. Every notification tray fills with \"your weekly AI insights.\" The technology that promised to save attention is currently strip-mining it — because the products are built on business models that need you looking at them.",
      },
      {
        t: "p",
        text: "In 1995, two researchers at Xerox PARC — Mark Weiser and John Seely Brown — described the alternative and named it **calm technology**: tools that live in the periphery of attention and move to the center only when you summon them. A kettle. A good pair of boots. Technology that *informs without demanding*. It reads like satire against the modern AI product, which is why we adopted it as a spec.",
      },
      { t: "h2", text: "The hustle stack vs. the calm stack" },
      {
        t: "ul",
        items: [
          "**The hustle stack is measured in engagement** — sessions, streaks, time-in-app. The calm stack is measured in completion: you needed a thing, you got it, you left.",
          "**The hustle stack initiates** — notifications, badges, \"just checking in!\" The calm stack waits. Turtle has no feed and sends no notifications. It cannot ping you; the code path doesn't exist.",
          "**The hustle stack interrupts by default** — suggestions appearing over your half-formed sentence. The calm stack is invoked: nothing happens until you type `/`, and everything stops when the command completes.",
          "**The hustle stack needs your data flowing** to fund the noise. The calm stack [refuses to monetize your data](/blog/zero-server-costs-local-inference), so silence costs it nothing.",
        ],
      },
      { t: "h2", text: "Design rules we actually hold" },
      {
        t: "p",
        text: "\"Calm\" decays into a marketing word unless it's enforceable. These are the enforceable versions:",
      },
      {
        t: "ul",
        items: [
          "**The slash is the only doorbell.** The AI activates on `/` and never otherwise. No ambient analysis, no proactive suggestions — which is also [the privacy invariant](/blog/why-your-keyboard-shouldnt-talk-to-the-cloud). Calm and private are the same architecture.",
          "**Finish and vanish.** A command inserts its result into the text field and disappears. No follow-up screen, no \"rate this response,\" no upsell.",
          "**No spinners that lie.** A command shows honest progress against a two-second budget — no fake shimmer, no infinite spinner pretending something's happening. Waiting is the opposite of calm, so we tell you exactly how long it'll be.",
          "**No metrics we'd have to feed.** We don't measure engagement, so nothing in the product is optimized to create it. You can't be tempted by a dashboard you refuse to build.",
        ],
      },
      {
        t: "quote",
        text: "We named it after a turtle on purpose. Slow is a feature: a tool with no engagement dashboard, no notification privileges, and no reason to sell your attention has nothing to gain from your anxiety.",
      },
      { t: "h2", text: "Anti-hustle is not anti-capable" },
      {
        t: "p",
        text: "None of this is a nostalgia project. The same keyboard that refuses to ping you [hosts an open protocol](/blog/bringing-mcp-to-mobile-keyboards) that can talk to any API on earth. Capability isn't the thing we gave up — *solicitation* is. The tool is as powerful as we can make it and exactly as loud as you make it, which is the entire difference between a rip current and [the current you swim with](/#current).",
      },
    ],
  },
  {
    slug: "how-to-create-a-poll-in-whatsapp",
    title: "How to Create a Poll in WhatsApp (Without Leaving the Chat)",
    description:
      "WhatsApp polls are quick but basic: not anonymous, no quizzes, one fixed look. Step-by-step for the native poll — and the /poll command that does more.",
    date: "2026-06-05",
    updated: "2026-07-04",
    tag: "guides",
    keywords: [
      "how to create a poll in WhatsApp",
      "anonymous poll WhatsApp",
      "WhatsApp quiz",
      "WhatsApp group poll",
      "poll without leaving chat",
      "/poll command",
    ],
    blocks: [
      {
        t: "p",
        text: "It's 6:40 p.m., the group chat has produced fifty messages about dinner, and the only consensus is that someone else should decide. This is a poll-shaped problem. WhatsApp actually has two answers to it — the built-in one, and a keyboard trick that also works in every *other* chat app where this exact scene plays out. Here are both, honestly compared.",
      },
      { t: "h2", text: "Method 1: WhatsApp's built-in poll" },
      {
        t: "ul",
        items: [
          "Open the chat, then tap the **+** (iPhone) or **paperclip** (Android) next to the message box.",
          "Choose **Poll**.",
          "Type your question, add up to twelve options, and toggle **Allow multiple answers** if you want it.",
          "Send. Votes tally live inside the message, and you can tap **View votes** to see who picked what.",
        ],
      },
      {
        t: "p",
        text: "For \"pizza or sushi,\" this is genuinely fine — use it. But you'll hit its walls fast: polls are text-only, there's no deadline so voting dribbles on forever, votes aren't anonymous, results can't be shared outside the thread, and — the big one — **the feature lives only in WhatsApp**. Your family plans in WhatsApp; your friends are in iMessage; work is in Slack. The skill doesn't travel.",
      },
      { t: "h2", text: "Method 2: type /poll — in WhatsApp or anywhere else" },
      {
        t: "p",
        text: "The [Turtle keyboard](/) puts a poll command inside the keyboard itself, so it goes wherever your keyboard goes:",
      },
      {
        t: "ul",
        items: [
          "In any chat — WhatsApp included — type `/poll`. The command menu rises above the keys.",
          "Type the question and options in one line: `/poll Dinner: pizza, sushi, or that new thai place?`",
          "Hit send. Turtle drops a link that unfurls into a live poll card, right in the thread.",
          "Everyone taps and votes in their browser — **nothing to install**, no account, and only you need the keyboard.",
          "Results update live for the whole group, with a proper preview card in the chat.",
        ],
      },
      { t: "h2", text: "What the built-in poll can't do (and /poll can)" },
      {
        t: "ul",
        items: [
          "**Quizzes from a prompt.** WhatsApp chats have no quiz feature at all. Type `/quiz 90s music trivia` and Turtle generates a playable quiz from that one prompt — questions, answers, and a live scoreboard the whole group can see.",
          "**Themes.** Native polls all wear the same gray. Turtle's poll and quiz cards come in multiple UI themes, so the Friday-plans vote and the wedding-logistics poll don't have to look like the same widget.",
          "**Anonymous voting.** WhatsApp shows everyone exactly who voted for what. Turtle's poll link counts votes without names — people just tap, no account, no callout.",
          "**A link that travels.** The poll is an interactive link, so the *same* poll runs in the WhatsApp group, the iMessage thread, and the Slack channel at once — one live tally across every app.",
        ],
      },
      { t: "h2", text: "Can you make an anonymous poll in WhatsApp?" },
      {
        t: "p",
        text: "Not natively. Every vote in a WhatsApp poll is pinned to your name — anyone can tap **View votes** and see exactly who chose what, and there is no setting to turn that off. That changes how people vote on anything remotely sensitive: whose place to meet at, which date *actually* works, whether the group trip is getting too expensive. The workaround is to take the vote out of WhatsApp's widget and into a link — a `/poll` card tallies choices without attaching names, so the group sees *what* won without seeing *who* tipped it.",
      },
      {
        t: "ul",
        items: [
          "**Quick binary question, everyone's on WhatsApp** → native poll. Two taps, done.",
          "**The vote is even slightly sensitive** → `/poll`, because it's anonymous and the native poll never is.",
          "**You want a quiz, not a poll** → `/quiz`. WhatsApp has nothing to compare it to.",
          "**The same debate spans apps** — half the group in iMessage, half in WhatsApp → `/poll`, because one link works in both.",
          "**You're polling a chat that has no polls** — SMS, a dating app, an email thread → `/poll` is the only move on the board.",
        ],
      },
      {
        t: "quote",
        text: "Rule of thumb from heavy testing: three options, one deadline, and let one option be a joke. Turnout doubles. The joke option occasionally wins — the group chat deserves the consequences.",
      },
      {
        t: "p",
        text: "And if your group chat is split between iPhone and Android — where even iMessage's new native polls give up — that's its own minefield, covered in [how to poll a group chat with both iPhone and Android users](/blog/create-a-live-poll-in-any-chat-app). No keyboard yet? You can [create a poll link in your browser](/poll-maker) right now, free and without an account — or [grab a beta spot](/#waitlist) and settle tonight's dinner debate properly.",
      },
    ],
  },
  {
    slug: "slack-slash-commands-everywhere",
    title: "Bring Slack Slash Commands to iMessage, Instagram, and Gmail",
    description:
      "Slash commands made Slack a superpower — then you open Gmail and juggle six tabs. How a keyboard puts /notion, /github, and /summarize into every app.",
    date: "2026-06-01",
    updated: "2026-07-04",
    tag: "guides",
    keywords: [
      "slash commands",
      "Slack slash commands",
      "slash commands iMessage",
      "productivity keyboard",
      "Notion mobile workflow",
      "app switching productivity",
    ],
    blocks: [
      {
        t: "p",
        text: "There's a specific muscle-memory glitch that marks a heavy Slack user: you type `/remind` into iMessage, watch the letters just… sit there, and feel faintly betrayed. In Slack, `/` summons every tool you have. Everywhere else on your phone, it's punctuation. The superpower turns out to be locked to one app — which is a strange place to leave it, since most of your typing happens in all the others.",
      },
      { t: "h2", text: "Why commands got trapped in Slack" },
      {
        t: "p",
        text: "Slash commands are an *integration model*: Slack built a platform, and every tool wrote a Slack app. That works beautifully inside Slack and not at all outside it, because iMessage, Instagram, and Gmail would each need to build their own platform and convince every tool to integrate again. Apple isn't building a command platform into iMessage. Instagram definitely isn't. The per-app model has a per-app ceiling.",
      },
      {
        t: "p",
        text: "But there's one piece of software that's already present in every one of those text fields: the keyboard. Move the command line down a layer — [into the keyboard itself](/blog/what-is-an-ai-keyboard) — and one integration works everywhere, no cooperation from any app required.",
      },
      { t: "h2", text: "A workday, without the app shuffle" },
      {
        t: "ul",
        items: [
          "**Gmail** — a client's six-paragraph email lands. `/summarize` condenses the thread; `/notion pull q3 pricing` drops the exact quote from your workspace into the reply. Zero tabs opened.",
          "**Instagram DMs** — a customer reports a bug in your product's DMs. `/linear file: checkout button dead on android` creates the ticket without leaving the conversation, and pastes the issue link back as proof.",
          "**Any dev chat** — a teammate asks \"did you see the CI failure?\" `/github` checks the PR, or files the issue, through the keyboard's **built-in GitHub connection** — no browser tab, no losing your place in the thread.",
          "**iMessage** — the \"when are you free?\" volley. `/calendar free thu-fri` drops your actual open slots as text — [the end of the calendar shuffle](/blog/the-end-of-the-calendar-app-shuffle) is its own story.",
          "**Any group chat** — decisions via [`/poll`](/blog/how-to-create-a-poll-in-whatsapp), dead-chat revival via `/quiz`. Same keystroke, every app.",
        ],
      },
      {
        t: "quote",
        text: "The unit of productivity on a phone isn't the app — it's the conversation. Every app switch taxes the conversation. Commands that come to the text field, instead of dragging you to the tool, refund that tax.",
      },
      { t: "h2", text: "The part Slack could never do" },
      {
        t: "p",
        text: "Because Turtle's commands are [MCP servers](/blog/bringing-mcp-to-mobile-keyboards), the catalog isn't limited to what we ship — though the big ones are already wired in: **GitHub, Notion, and Linear connect directly in the keyboard**, no setup beyond signing in. And if your team runs an internal tool with an API, [about thirty lines of code](/blog/build-a-keyboard-command-with-mcp) puts `/yourtool` into every text field on your phone — including the apps that will never have a platform of their own. Slack's superpower, minus Slack's walls: [the beta is open](/#waitlist).",
      },
    ],
  },
  {
    slug: "the-end-of-the-calendar-app-shuffle",
    title: "The End of the “Let Me Check My Calendar” App Shuffle",
    description:
      "Checking your availability mid-chat costs seven steps and two app switches. How a /calendar slash command drops your free slots into any chat as plain text.",
    date: "2026-05-21",
    tag: "guides",
    keywords: [
      "check calendar availability",
      "share availability in chat",
      "calendar slash command",
      "scheduling in messages",
      "app switching",
      "calendar productivity",
    ],
    blocks: [
      {
        t: "p",
        text: "You know the shuffle by heart. Someone asks \"does Thursday work?\" — and you leave the chat, open the calendar app, squint at Thursday, try to hold *2 to 4:30, then free after 6* in your head, switch back, discover the app helpfully refreshed the thread, scroll to find your place, and type a summary of what you already forgot. Seven steps. Two app switches. And a coin-flip chance you got distracted by a notification somewhere in the middle and never replied at all.",
      },
      {
        t: "p",
        text: "It's the most common micro-errand on a phone, and it's shaped exactly like the thing phones are worst at: holding your place in one app while you fetch a fact from another.",
      },
      { t: "h2", text: "The fix: your calendar, as a keystroke" },
      {
        t: "p",
        text: "With [Turtle](/) installed, the calendar comes to the conversation instead:",
      },
      {
        t: "ul",
        items: [
          "**`/calendar today`** — your day, compressed to one glanceable line in the command panel, without leaving the chat.",
          "**`/calendar free thu`** — just the gaps: *Thu: free 12–2, after 5:30*. Tap to drop it into the message as plain text.",
          "**`/calendar check thu 3pm`** — a conflict check that answers in one word before you promise something your Thursday can't cash.",
          "It's the same command in iMessage, WhatsApp, Slack, email — anywhere there's a text field and [a keyboard](/blog/what-is-an-ai-keyboard).",
        ],
      },
      { t: "h2", text: "Why plain text beats a booking link" },
      {
        t: "p",
        text: "The existing \"solution\" is the scheduling link, and for sales calls it's fine. But sending a booking page to your best friend is a power move nobody asked for — it says *my time is a resource; please queue*. Availability as ordinary text keeps the exchange human: they read it, reply \"6:30 then,\" done. No accounts, no branded interstitial, and it works for the recipient on any device ever made, because it's just words in a chat.",
      },
      {
        t: "ul",
        items: [
          "**Text is universal** — survives forwarding, screenshots, SMS, and your one friend still on a flip phone.",
          "**Text is social** — an answer inside the conversation, not a redirect out of it.",
          "**Text is fast** — the whole exchange stays inside the reply box, which is the entire point of [commands that come to you](/blog/slack-slash-commands-everywhere).",
        ],
      },
      { t: "h2", text: "The part that matters: who reads your calendar" },
      {
        t: "p",
        text: "A calendar is a diary with timestamps — doctor's appointments, interviews at competitors, therapy every other Tuesday. So `/calendar` follows the rule that governs everything in Turtle: it reads your schedule from your phone's own calendar the moment you invoke it, and inserts only the text you choose to send. Nothing about your keyboard is captured or uploaded except the message you actually type out — a privacy invariant that holds because the keyboard [only ever acts on an explicit slash command](/blog/what-is-an-ai-keyboard).",
      },
      {
        t: "quote",
        text: "The shuffle was never about the calendar app being bad. It's about the answer living one app away from the question. Move the answer into the keyboard, and the distance drops to zero.",
      },
      {
        t: "p",
        text: "`/calendar` rolls out with the Turtle beta on iOS and Android — [the waitlist is here](/#waitlist), and Thursday, for the record, works.",
      },
    ],
  },
  {
    slug: "imessage-polls-not-working",
    title: "iMessage Polls Not Working? Causes and Fixes (2026)",
    description:
      "iMessage polls require iOS 26 on every phone in the chat. Why the poll option isn't showing, fixes to try, and what works when someone's on Android.",
    date: "2026-07-04",
    tag: "guides",
    keywords: [
      "iMessage polls not working",
      "iMessage poll not showing up",
      "polls missing in Messages iOS 26",
      "iMessage poll with Android users",
      "can't create poll in iMessage",
    ],
    blocks: [
      {
        t: "p",
        text: "You updated to iOS 26, your friend just sent a poll in another chat, but in *your* group the Polls button is nowhere to be found — or you made one and half the group swears they can't see it. Nine times out of ten this isn't a bug. iMessage polls have strict requirements, and when one of them isn't met, the feature doesn't error — it just **silently disappears**.",
      },
      { t: "h2", text: "Why the poll option isn't showing up" },
      {
        t: "ul",
        items: [
          "**Your iPhone isn't on iOS 26.** Polls shipped with iOS 26. Check Settings → General → Software Update.",
          "**The conversation isn't iMessage.** Green bubbles mean SMS or RCS, and polls don't exist there — only blue-bubble iMessage threads get them.",
          "**Someone in the group isn't on iOS 26 iMessage.** This is the big, silent one: *every participant* must be on iMessage running iOS 26 (or the matching macOS/iPadOS release). One Android phone, one un-updated iPhone, one person texting from an old iPad — and the Polls button vanishes for the entire group.",
          "**A genuine iMessage glitch.** Rarer, but real: registration hiccups after an update can hide new features until iMessage re-syncs.",
        ],
      },
      {
        t: "p",
        text: "That third cause explains most \"polls work in one chat but not another\" confusion. The feature isn't broken — it's checking the roster, finding one incompatible device, and quietly removing itself. Apple's own support page states the requirement; it just never tells you *in the chat* which member is the reason.",
      },
      { t: "h2", text: "The fixes, in order" },
      {
        t: "ul",
        items: [
          "**Update your own phone** to iOS 26, restart, and reopen Messages.",
          "**Confirm the thread is blue.** If the group is green, polls were never on the table.",
          "**Audit the group.** Anyone on Android? Anyone who hasn't updated? If yes, that's your answer — skip to the next section.",
          "**Toggle iMessage off and on** (Settings → Apps → Messages → iMessage) if everyone genuinely qualifies and polls still don't appear, then restart the phone.",
          "**Start a fresh group thread.** Old threads occasionally carry stale state; a new conversation with the same people re-evaluates feature support.",
        ],
      },
      { t: "h2", text: "The fix when someone's on Android (or iOS 25, or a flip phone)" },
      {
        t: "p",
        text: "You can't update your way out of a mixed group — and most group chats are mixed. The workaround is to stop asking iMessage to host the poll and drop in a poll that lives at a **link** instead. With the [Turtle keyboard](/), you type `/poll Movie, bowling, or drinks?` right in the iMessage thread; it sends an interactive link that unfurls into a live poll card. Everyone opens it in their browser — the Android friend, the iOS 17 holdout, even someone on a laptop — votes anonymously, and watches results update live. No app for voters, no requirements, no roster check.",
      },
      {
        t: "quote",
        text: "The real cause of \"iMessage polls not working\" usually isn't a bug — it's a compatibility wall. Links don't have compatibility walls.",
      },
      {
        t: "faq",
        items: [
          {
            q: "Why can't I see the poll someone made in our iMessage group?",
            a: "Almost always because your iPhone isn't on iOS 26 yet, or you're receiving the thread over SMS/RCS instead of iMessage. Update in Settings → General → Software Update, confirm the chat shows blue bubbles, then have someone re-send the poll.",
          },
          {
            q: "Do iMessage polls work if an Android user is in the group?",
            a: "No. Polls require every participant to be on iMessage with iOS 26. One Android (or older-iOS) member hides the feature for the whole group. A link-based poll — like Turtle's /poll command — is the standard workaround, since it runs in the browser and works on any phone.",
          },
          {
            q: "Do iMessage polls work over SMS or RCS?",
            a: "No. Polls are an iMessage-only feature; green-bubble conversations never get them, regardless of iOS version.",
          },
          {
            q: "How do I make a poll in a group chat without iOS 26?",
            a: "Use a poll that lives at a shareable link instead of inside the messaging platform. Turtle's /poll command creates one from your keyboard in any chat app; voters need nothing but a browser, and results update live for everyone.",
          },
        ],
      },
      {
        t: "p",
        text: "For the full playbook on mixed groups — including quizzes and themed poll cards — see [how to poll a group chat with both iPhone and Android users](/blog/create-a-live-poll-in-any-chat-app). Want to fix tonight's chat right now? [Create a poll link in your browser](/poll-maker) — free, no account — and paste it into the thread. And if the debate is happening in WhatsApp instead, [that guide is here](/blog/how-to-create-a-poll-in-whatsapp).",
      },
    ],
  },
  {
    slug: "ios-custom-keyboard-extension-tutorial",
    title: "iOS Custom Keyboard Extension Tutorial: 2026 Edition",
    description:
      "A from-scratch UIInputViewController guide backed by real open-source code: setup, key handling, textDocumentProxy, Full Access, and the memory ceiling.",
    date: "2026-07-04",
    tag: "developers",
    keywords: [
      "iOS custom keyboard extension tutorial",
      "UIInputViewController tutorial",
      "custom keyboard Swift",
      "textDocumentProxy",
      "keyboard extension Full Access",
      "iOS keyboard extension memory limit",
    ],
    blocks: [
      {
        t: "p",
        text: "Most iOS keyboard tutorials you'll find rank from 2018 — Swift 4, iOS 11, and Apple's own top result is literally in the documentation archive. This one is different in two ways: it's current, and it's backed by a **real, shipping, MIT-licensed keyboard** — [Turtle's iOS source](https://github.com/princeku07/turtle-keyboard) — so every pattern here is one you can read in production context, not a toy.",
      },
      { t: "h2", text: "The two-target shape" },
      {
        t: "p",
        text: "A custom keyboard is never a standalone app. It's an **app extension** that rides inside a host app: in Xcode, File → New → Target → *Custom Keyboard Extension*. You end up with two targets — the host app (`com.you.keyboard`) that handles onboarding and Settings deep-links, and the extension (`com.you.keyboard.extension`) that *is* the keyboard. The host app matters more than it looks: it's the only place users can be walked through enabling the keyboard, and later it's where heavy work that doesn't fit the extension's limits can live.",
      },
      {
        t: "code",
        file: "Info.plist (extension)",
        label: "config",
        code: '<key>NSExtension</key>\n<dict>\n  <key>NSExtensionAttributes</key>\n  <dict>\n    <key>IsASCIICapable</key>       <false/>\n    <key>PrefersRightToLeft</key>   <false/>\n    <key>PrimaryLanguage</key>      <string>en-US</string>\n    <key>RequestsOpenAccess</key>   <true/>  <!-- Full Access: see below -->\n  </dict>\n  <key>NSExtensionPointIdentifier</key>\n  <string>com.apple.keyboard-service</string>\n  <key>NSExtensionPrincipalClass</key>\n  <string>$(PRODUCT_MODULE_NAME).KeyboardViewController</string>\n</dict>',
      },
      { t: "h2", text: "UIInputViewController is the whole world" },
      {
        t: "p",
        text: "Your principal class subclasses `UIInputViewController`. There's no storyboard, no scene delegate, no app lifecycle — the OS instantiates your controller every time a text field gains focus and tears it down when focus leaves. That spawn-and-kill rhythm is the defining constraint: **cold start is your first feature**. Build the key grid programmatically in `viewDidLoad` — stack views of plain `UIButton`s boot fastest, and it's the reason [we went native instead of React Native](/blog/the-case-for-cross-platform-native):",
      },
      {
        t: "code",
        file: "KeyboardViewController.swift",
        label: "ios · swift",
        code: 'class KeyboardViewController: UIInputViewController {\n\n  override func viewDidLoad() {\n    super.viewDidLoad()\n    buildKeyRows()          // UIStackViews of UIButtons — no nibs, no async\n  }\n\n  private func buildKeyRows() {\n    let rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]\n    let column = UIStackView()\n    column.axis = .vertical\n    column.distribution = .fillEqually\n    for row in rows {\n      let rowStack = UIStackView()\n      rowStack.distribution = .fillEqually\n      for ch in row {\n        rowStack.addArrangedSubview(makeKey(String(ch)))\n      }\n      column.addArrangedSubview(rowStack)\n    }\n    view.addSubview(column)\n    // pin to edges; give the keyboard an explicit height constraint\n  }\n}',
      },
      { t: "h2", text: "Typing goes through textDocumentProxy" },
      {
        t: "p",
        text: "You never touch the text field. Every mutation goes through `textDocumentProxy` — `insertText(_:)` to type, `deleteBackward()` to erase — and every *read* is limited to `documentContextBeforeInput` / `AfterInput`, which the host app may truncate wherever it likes. The part that surprises everyone: **you get none of the system keyboard's behavior for free**. Shift state, caps lock, auto-capitalization, double-tap-space-for-period — all yours to implement. Here's the double-tap-shift-for-caps-lock pattern from Turtle's production code:",
      },
      {
        t: "code",
        file: "KeyboardViewController.swift",
        label: "ios · swift",
        code: 'private var shifted = false\nprivate var capsLocked = false\nprivate var lastShiftTap: TimeInterval = 0\n\nfunc shiftPressed() {\n  let now = Date().timeIntervalSince1970\n  if now - lastShiftTap < 0.3 {          // double-tap window\n    capsLocked = true\n    shifted = true\n  } else {\n    capsLocked = false\n    shifted.toggle()\n  }\n  lastShiftTap = now\n  restyleKeys()\n}\n\nfunc keyPressed(_ letter: String) {\n  textDocumentProxy.insertText(shifted ? letter.uppercased() : letter)\n  if shifted && !capsLocked {            // shift-once auto-unshifts\n    shifted = false\n    restyleKeys()\n  }\n}',
      },
      { t: "h2", text: "The globe key is not optional" },
      {
        t: "p",
        text: "Users must always be able to leave your keyboard. Check `needsInputModeSwitchKey` (it's false on Face ID devices, where the system draws its own globe) and wire the key to `handleInputModeList(from:with:)` — a plain `advanceToNextInputMode()` tap target also works, but the long-press input-mode list is what App Review expects. Skipping this is one of the classic keyboard-extension rejection reasons.",
      },
      { t: "h2", text: "Full Access, App Groups, and the scary warning" },
      {
        t: "p",
        text: "`RequestsOpenAccess` unlocks the network and shared containers — and triggers the system's famous warning that the keyboard \"may be able to collect everything you type.\" Two things follow. Architecturally: pair the extension with the host app via an **App Group** so settings, registries, and model files can be shared without the network. Ethically: the warning is accurate, which is why what your keyboard does with its access should be [verifiable rather than promised](/blog/why-your-keyboard-shouldnt-talk-to-the-cloud) — it's the entire reason Turtle is open source.",
      },
      { t: "h2", text: "The memory ceiling will find you" },
      {
        t: "p",
        text: "A keyboard extension gets roughly **60 MB** before jetsam kills it mid-keystroke — no dialog, no crash log worth reading. Check `os_proc_available_memory()` at runtime before doing anything ambitious, never load large assets eagerly, and treat rotation (which briefly doubles some view allocations) as your stress test. We wrote a full deep dive on surviving it — quantization, mmap'd weights, the two-engine architecture — in [the brutal math of running local LLMs in an iOS keyboard](/blog/local-llms-in-an-ios-keyboard).",
      },
      { t: "h2", text: "Debugging without losing your mind" },
      {
        t: "ul",
        items: [
          "**Run the extension scheme, not the app scheme** — Xcode will ask which host to attach to; pick a simple app with a text field (Notes works).",
          "**If breakpoints don't land**, Debug → Attach to Process by name, then focus a text field to spawn the extension.",
          "**Test memory on a real device.** The simulator does not enforce the extension memory limit and will happily run what jetsam would execute.",
          "**Log through the app group.** `print` output from an extension is flaky in the console; writing a ring buffer to the shared container is the reliable path.",
        ],
      },
      {
        t: "p",
        text: "That's a working keyboard: two targets, a programmatic key grid, proxy-based typing, shift state, a globe key, and respect for the memory cage. The complete production version — three layouts, symbols, the banner system, and the [MCP command host](/blog/bringing-mcp-to-mobile-keyboards) — is in [the repo](https://github.com/princeku07/turtle-keyboard), MIT-licensed. Clone it, break it, ship your own.",
      },
    ],
  },
  {
    slug: "clevertype-alternatives",
    title: "CleverType Alternatives: Open-Source & Private AI Keyboards",
    description:
      "Hitting CleverType's paywall or uneasy about cloud processing? Honest alternatives compared — open-source keyboards, private options, and what each trades away.",
    date: "2026-07-04",
    tag: "product",
    keywords: [
      "CleverType alternative",
      "CleverType alternatives",
      "private AI keyboard",
      "open source AI keyboard",
      "AI keyboard without cloud",
    ],
    blocks: [
      {
        t: "p",
        text: "CleverType is a capable AI keyboard — grammar fixes, tone rewrites, solid reviews. People go looking for alternatives for two reasons: the useful features sit behind a subscription, and the processing model — by its own documentation, advanced features run in the cloud — means the text you're perfecting is leaving your phone to be perfected. If either of those is your itch, here's the honest map of what else exists.",
      },
      {
        t: "p",
        text: "Full disclosure up front: **we build one of the keyboards on this list** (Turtle, currently in beta). We'll tell you exactly where it's strong, where it isn't yet, and when one of the others is the better pick — because in this category, unverifiable claims are the disease, not a sales technique we want to catch.",
      },
      { t: "h2", text: "The quick comparison" },
      {
        t: "table",
        headers: ["Keyboard", "AI features", "Open source", "Where text is processed", "Price"],
        rows: [
          [
            "CleverType",
            "Grammar, tone, AI writing",
            "No",
            "Cloud for advanced features",
            "Free tier + subscription",
          ],
          [
            "**Turtle** (ours, beta)",
            "Slash commands: rewrite, summarize, quizzes, polls, images, MCP tools",
            "Yes — MIT",
            "Only your slash commands",
            "Free beta",
          ],
          ["HeliBoard", "None", "Yes", "On-device only", "Free"],
          [
            "FUTO Keyboard",
            "Local prediction + offline voice input",
            "Source-available",
            "On-device only",
            "Free",
          ],
          ["SwiftKey", "Copilot integration", "No", "Cloud", "Free"],
          [
            "Gboard",
            "Proofread, smart replies",
            "No",
            "Mixed on-device/cloud",
            "Free",
          ],
        ],
      },
      { t: "h2", text: "If you want AI in an open keyboard: Turtle" },
      {
        t: "p",
        text: "Turtle is our answer to the gap this whole market has: every private keyboard is dumb, and every AI keyboard is closed. Turtle is an [open-source AI keyboard](/open-source-ai-keyboard) that triggers only when you type a slash command — and is [MIT-licensed](https://github.com/princeku07/turtle-keyboard), so \"we only touch what you put after a slash\" is a fact you can check, not a policy you have to trust. It also does things no grammar keyboard attempts: [live polls and prompted quizzes](/blog/create-a-live-poll-in-any-chat-app) in any chat, images from a prompt, and [built-in MCP connections](/blog/bringing-mcp-to-mobile-keyboards) to GitHub, Notion, and Linear. The honest caveats: it's in **beta** ([waitlist](/#waitlist)), and today CleverType's pure writing-assistance feature set is more mature than ours. If you need polished tone-rewriting right now, CleverType is genuinely good at it.",
      },
      { t: "h2", text: "If you want maximum privacy and zero AI: HeliBoard or FUTO" },
      {
        t: "p",
        text: "The privacy community's standard recommendations, and deservedly so. **HeliBoard** is the fully open-source, offline-only choice — no network permission at all, which is the strongest privacy statement software can make. **FUTO Keyboard** goes further on capability with local predictive text and genuinely good offline voice input. Neither will rewrite a sentence or summarize anything — that's the deal. If your threat model says *no AI anywhere near my keystrokes*, pick HeliBoard and don't look back.",
      },
      { t: "h2", text: "If you want mainstream polish: SwiftKey or Gboard" },
      {
        t: "p",
        text: "Best-in-class typing feel, autocorrect trained on decades of data, free forever. The trade is the obvious one: they're operated by Microsoft and Google, closed-source, and their AI features run through their clouds. For a lot of people that's an acceptable deal — just make it knowingly. Our [five-point keyboard privacy audit](/blog/on-device-ai-vs-cloud-keyboards) takes ten minutes and works on any of these.",
      },
      {
        t: "faq",
        items: [
          {
            q: "Is there a free CleverType alternative?",
            a: "Yes — HeliBoard and FUTO Keyboard are completely free (no AI features), Gboard and SwiftKey are free with cloud AI, and Turtle's beta is free with AI via slash commands.",
          },
          {
            q: "Is there an open-source AI keyboard?",
            a: "Turtle is, to our knowledge, the first open-source (MIT) keyboard with generative AI — slash commands, connected tools, and an open MCP plugin system. HeliBoard and FUTO are open/source-available but don't do generative AI.",
          },
          {
            q: "Does CleverType work offline?",
            a: "Basic typing does, but by CleverType's own documentation its advanced AI features are processed in the cloud, so they need a connection. If cloud processing is your concern, the thing to look for is open-source code you can actually audit — the main reason this list exists.",
          },
          {
            q: "What's the most private AI keyboard?",
            a: "\"Private\" and \"AI\" only coexist when inference happens on the phone and the code is auditable. That combination is Turtle's entire design; if you'd rather drop AI completely, HeliBoard's no-network-permission approach is the gold standard.",
          },
        ],
      },
      {
        t: "p",
        text: "Whichever way you go: watch what it sends while you're just typing, read the privacy label, and prefer keyboards whose claims you can verify. The full checklist is in [where do your keystrokes go](/blog/on-device-ai-vs-cloud-keyboards) — and if you're switching specifically from Grammarly, see [Grammarly keyboard alternatives for iPhone](/blog/grammarly-keyboard-alternatives-iphone).",
      },
    ],
  },
  {
    slug: "grammarly-keyboard-alternatives-iphone",
    title: "Grammarly Keyboard Alternatives for iPhone (Free & Private)",
    description:
      "Grammarly's keyboard gates its best features and checks text in the cloud. Five iPhone alternatives compared — free, open-source, and Apple's built-in tool.",
    date: "2026-07-04",
    tag: "product",
    keywords: [
      "Grammarly keyboard alternative iPhone",
      "Grammarly keyboard alternative free",
      "grammar keyboard iPhone",
      "open source Grammarly alternative",
      "private grammar checker iPhone",
    ],
    blocks: [
      {
        t: "p",
        text: "The Grammarly keyboard put a real grammar checker on the iPhone, and for years it had no serious rival. But the reasons people search for a way out are consistent: the features that matter live behind a premium subscription, the iOS keyboard has long felt like the neglected corner of Grammarly's product line, and — structurally — everything you check is processed on Grammarly's servers. A grammar tool's whole job is reading your writing; *where* it does that reading is worth a decision, not a shrug.",
      },
      {
        t: "p",
        text: "Disclosure before the list: **we build Turtle**, one of the options below. We'll rank it where it honestly belongs today — which is not #1 for pure grammar checking.",
      },
      { t: "h2", text: "The quick comparison" },
      {
        t: "table",
        headers: ["Tool", "What it does best", "Price", "Where your text goes", "Open source"],
        rows: [
          [
            "Apple Writing Tools",
            "Proofread + rewrite, built into iOS",
            "Free",
            "On-device (Apple Intelligence devices)",
            "No",
          ],
          [
            "LanguageTool",
            "Grammar + style, 30+ languages",
            "Free tier + premium",
            "Cloud (self-hosting possible)",
            "Core engine — yes",
          ],
          ["SwiftKey", "Typing feel + autocorrect", "Free", "Cloud", "No"],
          [
            "CleverType",
            "Closest feature match to Grammarly",
            "Free tier + subscription",
            "Cloud for advanced features",
            "No",
          ],
          [
            "**Turtle** (ours, beta)",
            "Rewriting + slash-command tools",
            "Free beta",
            "Only your slash commands",
            "Yes — MIT",
          ],
        ],
      },
      { t: "h2", text: "Start with what's already on your phone: Apple Writing Tools" },
      {
        t: "p",
        text: "If you have an Apple Intelligence-capable iPhone, the honest first answer is that you may not need a grammar keyboard at all. Select text almost anywhere → Writing Tools → Proofread or Rewrite, processed on-device. It's free, private, and system-wide. The limits: it's reactive (select-then-fix, not as-you-type), the rewrites are conservative, and older iPhones don't get it. Try it before you install anything — including ours.",
      },
      { t: "h2", text: "LanguageTool: the open-core grammar engine" },
      {
        t: "p",
        text: "The strongest pure-grammar rival. The rule engine is open source, it's genuinely excellent in multiple languages, and the free tier is usable. The keyboard experience on iOS is serviceable rather than delightful, and the hosted service processes text in the cloud — though the self-hosting option (run the engine on your own server) is a real escape hatch no other tool here offers.",
      },
      { t: "h2", text: "CleverType: the closest feature match" },
      {
        t: "p",
        text: "If what you want is \"Grammarly, but better AI rewriting,\" CleverType is the most direct swap — tone controls, AI writing, active development. It shares Grammarly's two structural traits, though: subscription-gated depth and cloud processing for the advanced features. We compared it against the private options in detail in [CleverType alternatives](/blog/clevertype-alternatives).",
      },
      { t: "h2", text: "Turtle: an open keyboard that fixes tone (ours)" },
      {
        t: "p",
        text: "Turtle approaches it from the open end: rewriting and summarizing are invoked as slash commands (`/summarize` a thread, rewrite a draft) rather than an always-on suggestion strip — and the keyboard is [open source](https://github.com/princeku07/turtle-keyboard), so its central claim, that it only ever touches what you put after a slash, is auditable. It also does things outside the grammar lane entirely: [live polls and quizzes](/blog/create-a-live-poll-in-any-chat-app) in any chat, images from a prompt, and [GitHub and Notion connected directly in the keyboard](/blog/bringing-mcp-to-mobile-keyboards). Honesty requires saying: it's in **beta**, and for deep style-and-clarity coaching, Grammarly and CleverType are more mature today. If your priority is a keyboard you can actually inspect, [the waitlist is open](/#waitlist).",
      },
      { t: "h2", text: "The question to ask any grammar tool" },
      {
        t: "p",
        text: "Every tool on this page must read your writing to help you — that's the job. The differentiator is whether you can *verify* what it does with your text: a closed tool asks for trust, an open one lets you read the code. And prefer a tool that acts only on an explicit request over one that reads every keystroke as you type. The [full keyboard privacy audit](/blog/on-device-ai-vs-cloud-keyboards) has the rest of the checklist.",
      },
      {
        t: "faq",
        items: [
          {
            q: "Is there a free Grammarly keyboard alternative for iPhone?",
            a: "Yes. Apple's built-in Writing Tools are free and on-device (on Apple Intelligence iPhones), LanguageTool has a capable free tier, and Turtle's beta is free with AI rewriting via slash commands.",
          },
          {
            q: "Does Grammarly send what I type to its servers?",
            a: "Text you check with Grammarly is processed on its servers — that's how the analysis works, per Grammarly's own documentation. Whether that's acceptable depends on what you write; for sensitive drafts, prefer tools whose behavior you can verify.",
          },
          {
            q: "What's the most private grammar checker for iPhone?",
            a: "Apple Writing Tools (on-device, free) for select-and-fix editing; Turtle for keyboard-level, slash-command rewriting that's open source and only acts on explicit commands; LanguageTool self-hosted if you want a full grammar engine on infrastructure you control.",
          },
          {
            q: "Can Apple's Writing Tools fully replace the Grammarly keyboard?",
            a: "For casual proofreading on a recent iPhone, often yes. For continuous as-you-type checking, style coaching, or older devices, a dedicated tool still wins — which one depends on whether you optimize for features (CleverType), languages (LanguageTool), or privacy (Turtle, Writing Tools).",
          },
        ],
      },
      {
        t: "p",
        text: "Bottom line: try Apple's free built-in tools first, pick LanguageTool or CleverType if you need Grammarly-depth checking and accept the cloud, and pick [Turtle](/#waitlist) if you want the keyboard itself to stay out of your business — verifiably.",
      },
    ],
  },
];

/** all posts, newest first; same-day posts keep their authored order */
export const posts: Post[] = [...POSTS].sort((a, b) =>
  a.date === b.date ? 0 : a.date < b.date ? 1 : -1
);

export function getPost(slug: string): Post | undefined {
  return posts.find((p) => p.slug === slug);
}

/** ~220 wpm across every text-bearing block */
export function readingTime(post: Post): number {
  const words = post.blocks
    .map((b) => {
      switch (b.t) {
        case "ul":
          return b.items.join(" ");
        case "code":
          return b.code;
        case "table":
          return [...b.headers, ...b.rows.flat()].join(" ");
        case "faq":
          return b.items.map((i) => `${i.q} ${i.a}`).join(" ");
        default:
          return b.text;
      }
    })
    .join(" ")
    .split(/\s+/).length;
  return Math.max(1, Math.round(words / 220));
}

/** micro-markdown → plain text, for JSON-LD payloads */
export function mdToPlain(text: string): string {
  return text
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\*([^*]+)\*/g, "$1")
    .replace(/`([^`]+)`/g, "$1");
}

/** every FAQ item in a post, for FAQPage structured data */
export function faqItems(post: Post): Array<{ q: string; a: string }> {
  return post.blocks.flatMap((b) => (b.t === "faq" ? b.items : []));
}

export function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}
