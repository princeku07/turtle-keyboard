**PRODUCT REQUIREMENTS DOCUMENT**

**Turtle Keyboard**

*The Universal AI Input Layer*

An open-source AI keyboard for iOS and Android that puts every model — image, text, voice — one slash command away, inside any app you already use.

Version 1.0 • Draft

April 2026

# **Contents**

1\. Executive Summary

2\. The Problem & The Insight

3\. Vision, Mission, and Strategic Position

4\. Goals and Success Metrics

5\. Target Personas

6\. Product Overview & Core Experience

7\. Feature Specification

8\. Technical Architecture

9\. Open-Source & Monetization Strategy

10\. Go-to-Market Plan

11\. Implementation Roadmap

12\. Risks, Threats, and Mitigations

13\. Open Questions & Decisions Required

14\. Appendix

# **1\. Executive Summary**

## **1.1 What is Turtle Keyboard?**

Turtle Keyboard is a third-party AI keyboard for iOS and Android. It introduces a single, universal interaction pattern — typing a slash command (e.g., /cap, /fix, /reply) inside any text field — that triggers an AI action and returns the result to the user, ready to paste into the conversation they are already in.

The keyboard itself is open source. The intelligence behind it — model routing, image generation, premium models, custom commands — is delivered through a closed-source backend service. Users get a free tier with generous limits; power users pay a small monthly subscription for unlimited access, premium models, and advanced features.

## **1.2 Why now?**

-   AI has become useful enough that people want to use it 50+ times a day, but the friction of opening a separate app stops them.
-   Platform AI (Apple Intelligence, Google Gemini, Galaxy AI) is locked to one model per platform — and that model is chosen by the platform, not the user.
-   Best-in-class models are fragmented across providers. Users want Flux for images, Claude for writing, GPT for code, Perplexity for research. No platform keyboard can route across providers.
-   Inference costs have collapsed. Flux Schnell delivers high-quality images in ~1.5 seconds at ~$0.003 per generation. The unit economics finally support a consumer freemium AI product.

## **1.3 Strategic position**

Turtle Keyboard is the model-agnostic AI input layer. Apple, Google, and Samsung structurally cannot build this — their AI keyboards exist to promote their own models. The closed AI labs (OpenAI, Anthropic) won't build this — they want to be destinations, not infrastructure. This neutrality is Turtle Keyboard's structural moat.

## **1.4 What we're shipping**

v1 launch is iOS-first, with a single hero command (/cap for image generation) plus three retention-driving text commands (/fix, /reply, /tone). Android follows in month 4. The full vision — user-defined commands, plugin marketplace, ambient suggestions — unfolds across the first 18 months.

# **2\. The Problem & The Insight**

## **2.1 The friction tax on AI usage**

Today, using AI from your phone requires a context switch:

1.  Stop what you are doing in the current app.
2.  Open the AI app (ChatGPT, Claude, Gemini).
3.  Type or dictate the prompt.
4.  Wait for the response.
5.  Copy the output.
6.  Switch back to the original app.
7.  Paste.

That is six steps and at least one app switch every time. Multiply by the dozens of moments each day where AI could plausibly help, and the friction adds up to a massive amount of unrealized utility. Most users only invoke AI when the value is high enough to overcome the friction. The 'long tail' of small daily wins (a quick translation, a tone fix, a meme) goes unaddressed.

## **2.2 The platform conflict**

Default keyboards on iOS and Android are racing to add AI features, but they are constrained by their parent companies' commercial interests:

-   Apple's keyboard will only invoke Apple Intelligence and Apple-blessed third parties.
-   Gboard will privilege Gemini, the model Google sells to enterprises.
-   Samsung Keyboard pushes Galaxy AI as a hardware differentiator.

None of these companies can offer model-agnostic routing without cannibalizing their own AI businesses. The user, who would benefit from picking the right model per task, is structurally unserved.

## **2.3 The insight**

**The keyboard is the universal layer above every app.** Whoever owns the keyboard owns the input layer of the phone. AI does not need to live in a destination app — it should live in the text field itself, contextually available everywhere.

**The slash command is the right primitive.** Discord, Slack, Notion, and Linear have already trained tens of millions of users that '/' invokes commands. We extend the same primitive to every text field on the phone.

**Model neutrality is a structural moat.** The platforms cannot match it. Independent labs will not build it. A neutral, third-party keyboard can route any command to the best model for the job — and switch as the landscape evolves.

# **3\. Vision, Mission, and Strategic Position**

## **3.1 Vision**

A world where AI is invoked as easily as typing a word — available in every app, on every platform, routed to the best model for each task, owned by no single platform.

## **3.2 Mission**

Build the universal AI command line for the phone. Make slash commands the default way humans summon AI from their pocket. Keep the keyboard open and trusted, the routing neutral, and the experience faster than any platform alternative.

## **3.3 Strategic position**

Turtle Keyboard occupies a position no incumbent can credibly replicate:

| **Player** | **AI strategy** | **Model neutrality** | **Keyboard surface** |
| --- | --- | --- | --- |
| Apple | Apple Intelligence | No (locked) | Default keyboard |
| Google | Gemini | No (locked) | Gboard |
| Samsung | Galaxy AI | No (locked) | Samsung Keyboard |
| OpenAI / Anthropic | Destination chat app | Conflict of interest | None |
| **Turtle Keyboard** | **Universal input layer** | **Yes (core principle)** | **Third-party keyboard** |

## **3.4 Tagline options**

-   "One keyboard. Every AI. Your choice."
-   "Slash is the new Hey Siri."
-   "The keyboard Apple and Google can't build."

# **4\. Goals and Success Metrics**

## **4.1 Year 1 goals**

-   Ship iOS keyboard to public App Store within 4 months of project start.
-   Ship Android keyboard within 8 months.
-   Reach 50,000 total installs across platforms by month 12.
-   Achieve 35%+ Day 7 retention and 20%+ Day 30 retention on activated users.
-   Convert 4%+ of weekly active users to a paid tier.
-   Reach $5,000 MRR by month 12.

## **4.2 North-star metric**

**Weekly Active Slash Commands (WASC).** The total number of /-prefixed commands successfully completed by users in a given week. This single metric captures install quality, activation, retention, and engagement in one number.

## **4.3 Supporting metrics**

| **Metric** | **Why it matters** | **Year 1 target** |
| --- | --- | --- |
| Install → keyboard activated | Measures how well onboarding survives the Full Access fear | ≥ 55% |
| Activated → first command in 24h | First magic moment within day one | ≥ 70% |
| D7 retention | Did the user come back after the novelty? | ≥ 35% |
| D30 retention | Real long-term habit | ≥ 20% |
| Avg. commands per WAU | Engagement intensity | ≥ 12 / week |
| Free → paid conversion | Revenue health | ≥ 4% |
| K-factor (viral coefficient) | Word-of-mouth growth | ≥ 0.4 |
| GitHub stars | Open-source momentum | ≥ 5,000 |

## **4.4 Anti-goals**

Things we explicitly will not optimize for in year one:

-   Pure user count — installs without activation are vanity.
-   Feature breadth at the expense of latency — every command must feel instant or it dies.
-   Enterprise and team features — the wedge is consumer.
-   Cross-platform parity for its own sake — ship one platform deeply before the second.

# **5\. Target Personas**

Turtle Keyboard targets four overlapping personas. The first is the wedge persona — the user we design v1 around. The others expand the addressable audience as the product matures.

## **5.1 The Group Chat Entertainer (wedge persona)**

**Demographic:** 16–28, heavy WhatsApp/iMessage/Instagram DM user, meme-fluent, multiple active group chats.

**Behavior:** Sends 100+ messages a day. Already shares memes, GIFs, and reaction images. Often the 'funny one' in their groups. Status comes from making others laugh.

**Pain:** Wants to react with a custom image to fit the moment but Google Image search is slow, GIF keyboards are stale, and switching to Midjourney/ChatGPT kills the moment's energy.

**Why Turtle Keyboard:** /cap returns a custom image in 2 seconds, in the same conversation, before the moment passes. Status reward is immediate and visible.

**Activation moment:** First time their generated image gets 5+ reactions in a group chat.

## **5.2 The Multi-Tool Power User**

**Demographic:** 25–45, technologist, designer, founder, or knowledge worker. Already pays for ChatGPT Plus, Claude Pro, Midjourney, or several.

**Behavior:** Switches between AI tools depending on task. Keeps multiple apps open. Annoyed by lock-in, loves bring-your-own-key models.

**Pain:** Already pays for the models. Wants a unified keyboard surface to invoke them without app switching. Hates being forced into one platform's AI.

**Why Turtle Keyboard:** Per-command model routing. Bring-your-own-key support. Configurable shortcuts. Open source means they trust the privacy story.

**Activation moment:** First time they configure /fix to use Claude and /code to use GPT-4o, and realize they don't open the chat apps anymore.

## **5.3 The ESL / Cross-Lingual Communicator**

**Demographic:** Anyone communicating regularly in a non-native language — students, immigrants, remote workers, international couples.

**Behavior:** Types in their second language for work or relationships, second-guesses grammar and tone, often double-checks with Google Translate or ChatGPT.

**Pain:** Constant context switching just to verify a sentence. Embarrassment about errors. Slowed-down communication.

**Why Turtle Keyboard:** /fix grammars in place. /tone shifts register (formal/casual/warm). /tl translates inline. All without leaving the chat.

**Activation moment:** First time they send a polished work email without opening Translate or ChatGPT.

## **5.4 The Dating App User**

**Demographic:** 20–40, active on Tinder/Hinge/Bumble/equivalent.

**Behavior:** Stares at messages trying to think of clever replies. High effort per match. Easily falls into 'dry texting' patterns.

**Pain:** Feels stuck for replies. Already screenshots conversations into ChatGPT for help. Embarrassed by the workflow.

**Why Turtle Keyboard:** /reply suggests three on-tone responses based on the last message. /tone flirty rewrites a flat line. The whole loop happens inside the dating app.

**Activation moment:** First time they get a positive response to a Turtle Keyboard-suggested reply.

# **6\. Product Overview & Core Experience**

## **6.1 The Turtle Keyboard experience in one paragraph**

A user installs Turtle Keyboard, completes a guided onboarding to enable it as a keyboard, and grants Full Access. From that moment on, in any text field on their phone — WhatsApp, Gmail, Tinder, Twitter, Notes — they can type a slash followed by a command and a prompt, and Turtle Keyboard will return a result. Images get copied to the clipboard with a one-tap paste banner. Text results are inserted directly into the field. Everything happens in 1–3 seconds. The user never leaves the app they were already in.

## **6.2 The first 60 seconds (critical UX)**

1.  User downloads Turtle Keyboard from the App Store and opens it.
2.  Welcome screen: 'The AI command line for your phone' with a 6-second video showing /cap in action inside WhatsApp.
3.  Sign in with Apple / Google / email. Anonymous mode also available.
4.  In-app playground: user immediately tries /cap a cat astronaut and sees the image generated. Magic moment delivered before keyboard setup.
5.  'Now let's set up the keyboard everywhere.' Step-by-step animated guide for Settings → Keyboard activation, with deep links where possible.
6.  Full Access explanation screen: clear, jargon-free reason; link to source code; promise that nothing is logged outside slash commands.
7.  Test screen: 'Try it here.' User invokes /cap inside Turtle Keyboard's own text field, confirms the keyboard works.
8.  'You're ready. Try it in WhatsApp.' Optional: launch user directly into WhatsApp.

## **6.3 Anatomy of a slash command**

Every Turtle Keyboard command follows the same shape:

/\[command\] \[prompt or modifier\] \[optional flags\]

Examples:

-   /cap a golden retriever as a samurai
-   /fix (operates on text already typed in the field)
-   /tone formal (rewrites preceding text in formal register)
-   /reply (reads clipboard or last received message and suggests responses)
-   /tl es (translates to Spanish)

Every command in this list is also reachable from the Quick Panel (§6.6) — a tap-driven grid that invokes the same commands without typing the slash form.

## **6.4 Output handling**

| **Output type** | **iOS behavior** | **Android behavior** |
| --- | --- | --- |
| Image | Copy to clipboard. Banner: 'Tap to paste 📋'. User long-presses field to paste. | Direct insertion via Image Keyboard API where supported (WhatsApp, Gmail, Messages). Clipboard fallback elsewhere. |
| Text replacement | Cursor-aware insertion: replaces selection or appended text in field. | Same as iOS. |
| Multiple options (e.g. /reply) | Show three tappable suggestions above keys. Tap inserts. | Same as iOS. |
| Long content | Insert into field; if >500 chars, also save to Turtle Keyboard app history. | Same as iOS. |

## **6.5 Turtle Keyboard host app**

The standalone Turtle Keyboard app is more than a setup wizard. It serves four purposes:

-   Onboarding and keyboard activation guide.
-   Playground: a chat-like interface where users can try every command without setting up the keyboard. Drives day-one engagement and helps App Store review pass the 'unique functionality' bar.
-   History: every generation is saved (locally, with optional cloud sync). Users can re-share, edit, or delete past outputs.
-   Settings: model routing preferences, BYO API keys, subscription management, and a 'discover commands' section.

## **6.6 Quick Panel (secondary invocation)**

The slash remains the primary, power-user primitive. The Quick Panel is a complementary entry point for users who would rather tap than type — newcomers, users browsing options, and anyone who has not yet memorized a command. It does not replace the slash; it widens the door to the same commands.

### **Trigger**

Double-tap the space bar inside Turtle Keyboard. A compact panel slides up above the keys; the keyboard remains visible underneath so the user never loses context. Dismissed by tapping outside, swiping down, or double-tapping space again.

### **Contents**

A grid of registered commands, each shown as an icon and label (e.g., 🎨 Image, ✏️ Fix, 💬 Reply, 🌐 Translate). A tap takes one of two paths:

-   Commands that need a prompt (e.g., /cap, /tl) open an inline prompt field above the panel; the user types the prompt and hits send.
-   Commands that act on existing field or clipboard text (e.g., /fix, /reply) execute immediately.

Either path runs through the exact same backend dispatcher as the slash invocation. The panel is only a different entry point — never a different command implementation.

### **iOS gesture conflict**

Apple's 'double-space inserts a period' behavior lives inside Apple's own keyboard, not the OS — a third-party keyboard owns its own space-tap interpretation. Users may still have muscle memory expecting auto-period, however. Mitigations:

-   Onboarding explicitly demos the panel before keyboard activation, so the new gesture is taught, not discovered by surprise.
-   Settings → Keyboard exposes a toggle: *Double-tap space → Quick Panel* (default ON). When OFF, Turtle Keyboard re-implements Apple's auto-period behavior so opt-out users lose nothing.
-   Long-pressing the space bar always inserts a period as a universal escape hatch.

### **Why both paths exist**

-   /slash is faster for users with muscle memory and supports inline arguments (e.g., /cap a samurai cat) in a single uninterrupted typing motion.
-   The Quick Panel is more discoverable, requires no memorization, and is a better fit when the user is browsing options rather than executing a known command.
-   Dual invocation expands the addressable audience beyond the technical persona without diluting the slash brand. The slash is still the hero in marketing, App Store screenshots, and the public command leaderboard.
-   Onboarding shows the panel first, then teaches the slash equivalent — graduating panel users into power users over time.

# **7\. Feature Specification**

## **7.1 v1 launch commands**

| **Command** | **What it does** | **Default model** | **Latency target** |
| --- | --- | --- | --- |
| **/cap** | Generate image from prompt | Flux Schnell (free), Flux Pro (paid) | ≤ 2 seconds |
| **/fix** | Fix grammar and spelling of preceding text | Claude Haiku / Gemini Flash | ≤ 1 second |
| **/tone** | Rewrite text in chosen register (formal, casual, flirty, professional, warm, concise) | Claude Haiku | ≤ 1.5 seconds |
| **/reply** | Suggest 3 replies based on last received message | Claude Sonnet / GPT-4o-mini | ≤ 2 seconds |
| **/tl** | Translate to specified language | Gemini Flash | ≤ 1 second |

## **7.2 v2 commands (months 4–6)**

-   **/meme** — generate a meme template with AI-written caption
-   **/sum** — summarize pasted text
-   **/explain** — explain pasted content in plain language
-   **/code** — generate or fix code snippets
-   **/voice** — transcribe a voice note clipboard URL
-   **/roast** — playful roast of a pasted message

## **7.3 v3 platform features (months 7–12)**

-   **User-defined commands.** Power users can author custom slash commands with their own system prompts and model preferences. Example: /jared = 'rewrite this in the voice of my friend Jared who's sarcastic and concise.'
-   **Shareable command packs.** Users publish their commands to a public registry. Others install with one tap. This is when Turtle Keyboard becomes a platform.
-   **Per-command model picker.** Pro users can override default model on any command. UI: long-press the command name in the keyboard.
-   **Bring-your-own-key.** Users plug in their own OpenAI/Anthropic/fal keys; Turtle Keyboard uses those keys for inference, charging only a flat keyboard subscription.
-   **Generation history with sync.** Past generations sync across devices (encrypted).

## **7.4 v4 ambient features (year 2)**

-   Contextual command suggestions: Turtle Keyboard recognizes patterns (long pasted text → suggests /sum) and surfaces a one-tap suggestion.
-   Image-to-image and style chaining: /cap on an existing image to remix or restyle.
-   Voice trigger: hold spacebar, speak the prompt for hands-free invocation.
-   Cross-app context: Turtle Keyboard reads the last received message in supported apps (where OS allows) for richer /reply context.

# **8\. Technical Architecture**

## **8.1 System overview**

Turtle Keyboard is composed of four major components:

1.  Keyboard extension (per platform, native, open source)
2.  Host app (per platform, native, open source UI / closed account logic)
3.  Backend API (closed source, multi-tenant, model-routing)
4.  Inference layer (third-party providers — fal.ai, Replicate, OpenAI, Anthropic, Gemini)

## **8.2 iOS keyboard extension**

Built as a native iOS Keyboard Extension in Swift / SwiftUI. Hard constraints to design around:

-   48 MB memory ceiling — no on-device ML, minimal in-memory caching.
-   No direct image insertion into other apps — clipboard handoff with paste banner.
-   Network calls require Full Access permission — onboarding must earn this trust.
-   Shared App Group for app↔keyboard communication (auth tokens, recent generations, settings).

### **Key responsibilities**

-   Slash command detection: monitor textDocumentProxy input, detect / prefix, parse command + arguments.
-   Inline UI: above-keys row showing command status (typing, generating, ready), recent generations, suggested commands.
-   API call: POST to Turtle Keyboard backend with command, prompt, auth token, locale.
-   Result handling: text → insertText into field; image → save to Pasteboard, show banner.
-   Error handling: graceful fallback if no network, server error, rate limit.
-   Quick Panel (§6.6): detect double-tap on the space bar, render the registered-command grid above the keys, and route taps through the same dispatcher used for slash invocation. Settings exposes a toggle to disable the panel and restore Apple's double-space-for-period behavior for users who opt out.

## **8.3 Android keyboard**

Built as a native Android InputMethodService in Kotlin with Jetpack Compose for the candidate strip UI. Looser constraints than iOS:

-   Direct image insertion supported via commitContent() in apps that opt in (WhatsApp, Gmail, Messages, Slack, etc.).
-   No memory ceiling — can be more generous with caching and UI.
-   No 'Full Access' equivalent fear — but still triggers an OS warning users must accept.

The Quick Panel (§6.6) is implemented identically to iOS: double-tap on the space bar opens the same grid of registered commands and routes taps through the same dispatcher. Android has no equivalent of Apple's double-space-for-period behavior, so the gesture is unambiguous; the same opt-out toggle exists for parity with iOS.

## **8.4 Backend API**

A stateless HTTP API serving authenticated requests from keyboards and host apps. Tech stack chosen for speed of iteration and low cold-start latency.

### **Stack**

-   Node.js / TypeScript on Cloudflare Workers (or Bun on Fly.io as alternative) — chosen for global edge latency and zero cold start.
-   Postgres (Neon or Supabase) for user, subscription, command-pack data.
-   R2 / S3 for generated image storage, with signed URLs and 30-day TTL on free tier.
-   Upstash Redis for rate limiting and request-level caching.
-   Stripe for web subscriptions; Apple IAP and Google Play Billing for in-app subscriptions.
-   PostHog for product analytics, Sentry for error tracking.

### **Core endpoints**

| **Endpoint** | **Method** | **Purpose** |
| --- | --- | --- |
| /v1/auth/anonymous | POST | Issue anonymous device-bound token for first-launch users |
| /v1/auth/upgrade | POST | Convert anonymous account to email-bound |
| /v1/command | POST | Execute a slash command. Body: { command, prompt, context, locale }. Returns: { type, payload, generationId } |
| /v1/history | GET | Paginated user generation history |
| /v1/commands | GET | List of available commands and their default models |
| /v1/commands/custom | POST/GET/DELETE | CRUD for user-defined commands (v3+) |
| /v1/billing/subscribe | POST | Initiate web-side subscription via Stripe |
| /v1/billing/verify-iap | POST | Verify Apple/Google IAP receipt and grant entitlements |

## **8.5 Model routing layer**

The single most important backend component. A configuration-driven router that maps (command, user\_tier, override) → (provider, model, parameters).

### **Router design principles**

-   All routing logic lives in a single config file/table — never hardcoded into endpoint handlers.
-   Every command has a fallback chain: if primary provider fails, try secondary.
-   Every model invocation logs (anonymized) latency, cost, and success rate to inform future routing.
-   Public model leaderboard surfaces aggregate routing stats — reinforces neutrality brand.

### **Example routing config**

command: "/cap" free: { provider: "fal", model: "flux-schnell" } pro: { provider: "fal", model: "flux-pro" } byo\_key: { provider: user\_provider } fallback: \[{ provider: "replicate", model: "sdxl-turbo" }\]

## **8.6 Latency budget**

End-to-end target: ≤ 2 seconds for /cap, ≤ 1.5 seconds for text commands, on a typical 4G connection. Allocated as:

-   Keyboard → backend round trip: ≤ 250 ms (Cloudflare Workers edge)
-   Backend processing + auth + routing: ≤ 50 ms
-   Inference: 1.0–1.5 s (Flux Schnell), 0.4–0.8 s (Haiku, Flash)
-   Backend → keyboard return + render: ≤ 200 ms

## **8.7 Privacy & security architecture**

-   End-to-end TLS for all keyboard ↔ backend traffic.
-   Keyboard never logs typed text outside of slash commands. Codified, audited, and verifiable in open-source repo.
-   Slash command payloads stored only as needed for generation history; user can purge from settings.
-   No selling of user data, ever. This is a stated, non-negotiable principle.
-   Anonymous mode: device-bound token, no email, no PII. Some Pro features require account upgrade.
-   Bring-your-own-key: keys stored encrypted at rest, never logged in plaintext, never sent to third parties beyond the model provider the key targets.

# **9\. Open-Source & Monetization Strategy**

## **9.1 Why open source**

-   Trust: addresses Full Access fear directly. Auditable by anyone.
-   Distribution: GitHub stars, forks, and dev evangelism are free marketing.
-   Community contributions: locale support, accessibility fixes, niche commands.
-   Defensibility: backend service remains the moat; the keyboard is the surface.

## **9.2 What is open vs. closed**

| **Component** | **Open or closed** | **Reason** |
| --- | --- | --- |
| iOS keyboard extension | **Open (MIT)** | Trust + community contribution |
| Android keyboard | **Open (MIT)** | Same as above |
| Host app UI | **Open (MIT)** | Visual transparency |
| Backend API | **Closed** | Routing logic, billing, abuse defenses |
| Model selection logic | **Closed** | Competitive secret sauce, but routing OUTCOMES are public |
| Self-hosting docs | **Open** | Power users can run their own backend with their own keys |

## **9.3 License choice: MIT**

MIT permissive license. Anyone can fork, modify, and redistribute. We do not defend against forks via license — we defend via execution, routing quality, and the network effect of our paid backend service. Embrace forks as marketing.

## **9.4 Pricing tiers**

| **Tier** | **Price** | **Daily limits** | **Features** |
| --- | --- | --- | --- |
| **Free** | $0 | 20 images   100 text commands | Standard models Watermark on /cap All v1 commands |
| **Pro** | $4.99/mo   $39/yr | Unlimited | Premium models (Flux Pro, GPT-4o, Claude Sonnet) No watermark Priority queue Custom commands |
| **Pro+ / BYO-Key** | $9.99/mo | Unlimited | Everything in Pro   Bring your own API keys Cross-device sync Early access to new commands |

## **9.5 Monetization timeline**

-   Months 1–3: free tier only. No payment infrastructure. Focus entirely on retention and product-market fit.
-   Months 4–6: introduce Pro tier via Apple IAP. Communicate clearly: 'Keyboard stays free forever; premium AI features support development.'
-   Months 7–9: introduce Pro+ with BYO-key support. Add Stripe-backed web subscriptions for users who prefer to bypass IAP.
-   Year 2: explore command-pack marketplace revenue share, team plans for small businesses, API access for developers.

## **9.6 Apple IAP considerations**

App Store Guidelines 3.1.1 require IAP for digital goods sold inside the app. Turtle Keyboard will:

-   Offer Apple IAP for in-app subscription purchases (accept the 30% cut, 15% after year one).
-   Maintain a website where users can sign up directly via Stripe (full margin).
-   Never link to or mention the website inside the iOS app (compliant with current rules).
-   Honor entitlements purchased via either path; web-side users sign in inside the app and sync their subscription.

# **10\. Go-to-Market Plan**

## **10.1 Launch sequencing**

### **Pre-launch (months 1–3)**

-   Build in public on Twitter/X. Weekly progress threads showing real builds, real bugs, real users.
-   GitHub repo public from day one. Landing page with email signup + demo video.
-   Closed alpha with 20–30 friends and Twitter followers via TestFlight.
-   Recruit 5 'design partners' from the wedge persona (group chat entertainers) for weekly feedback.

### **Soft launch (month 4)**

-   Open TestFlight to 500 beta users via waitlist.
-   Show HN post: 'Show HN: Turtle Keyboard — open-source AI keyboard for iOS.' Aim for front page.
-   Targeted outreach to 20 indie tech YouTubers and TikTok creators in the AI/productivity space.
-   Reddit posts in r/iOSProgramming, r/sideprojects, r/ChatGPT, r/Anthropic — focused on the open-source and model-agnostic angles, not the product pitch.

### **Public launch (month 5)**

-   App Store launch. Coordinated Product Hunt + HN + Twitter day-of push.
-   Press outreach: TechCrunch, The Verge, 9to5Mac, Daring Fireball — emphasize the strategic angle (open-source, model-agnostic, anti-platform-AI).
-   Influencer seeding: send TestFlight access to 50 niche creators in dating, memes, language learning. Pay nothing — give them Pro free for life.

### **Scale (months 6–12)**

-   Android launch (month 8) — second wave of press.
-   Localization: Spanish, Portuguese, Hindi, Indonesian — markets with high WhatsApp usage and underserved AI tooling.
-   Referral program: 14 days of Pro for each referred install that activates.
-   Community: launch Discord for power users, weekly 'command of the week' contests.

## **10.2 Distribution channels (ranked by expected impact)**

1.  Word of mouth in group chats — every Turtle Keyboard-generated image is a tiny ad. Optimize for shareability.
2.  Build in public on Twitter — direct line to the technical early-adopter crowd.
3.  Hacker News / Product Hunt — single-day spikes that translate to ~10–30k installs if it lands.
4.  TikTok / YouTube creators — shows the magic moment to non-technical audiences.
5.  Reddit niche communities — slow burn, high quality.
6.  App Store organic — once ratings + downloads hit a threshold, App Store search becomes a real channel.
7.  Paid ads — last resort, only after retention is proven.

## **10.3 Brand and voice**

-   Tone: confident, irreverent, technically credible. Slightly anti-establishment toward platform AI.
-   Visual: minimalist, monospace-forward. The slash is the hero. Strong color palette built around a single accent.
-   Posture: 'Turtle Keyboard is what AI on your phone should already be. The platforms can't ship this. We will.'

# **11\. Implementation Roadmap**

## **11.1 Month 1 — Foundation**

-   Apple Developer account, GitHub repo, domain, brand basics.
-   Backend skeleton on Cloudflare Workers: auth, /v1/command endpoint, model router.
-   Single command: /cap, routed to Flux Schnell on fal.ai.
-   iOS host app skeleton with playground (no keyboard yet) — proves end-to-end flow.
-   Internal milestone: generate an image inside the host app in ≤ 2 seconds.

## **11.2 Month 2 — Keyboard MVP**

-   iOS keyboard extension with slash command parser.
-   Image clipboard handoff with paste banner.
-   Onboarding flow: welcome → playground → keyboard activation → Full Access → test.
-   Add /fix and /reply commands.
-   Internal milestone: send Turtle Keyboard-generated image into a real WhatsApp conversation in under 10 seconds end-to-end.

## **11.3 Month 3 — Polish & Closed Alpha**

-   Add /tone and /tl commands.
-   Generation history in host app (with optional cloud sync via account).
-   Anonymous mode and account upgrade flow.
-   Closed alpha with 30 testers via TestFlight; weekly user interviews.
-   Telemetry: PostHog events for every command, error states, latency percentiles.

## **11.4 Month 4 — Soft Launch**

-   Apple App Store submission (allow 2-week review buffer).
-   Open TestFlight to 500 waitlist users.
-   Show HN post.
-   Begin paid tier wiring: Apple IAP integration, server-side entitlement verification.
-   Internal milestone: 35% D7 retention on alpha users.

## **11.5 Month 5 — Public iOS Launch**

-   App Store live.
-   Pro tier ($4.99/mo) goes live with no premium-only features at first — pure 'support development' framing — and rolls in premium models within 2 weeks.
-   Coordinated launch on Product Hunt, HN, Twitter.
-   Internal milestone: 5,000 installs, 250 Pro subscribers.

## **11.6 Months 6–8 — Android + Expansion**

-   Android keyboard build (4–6 weeks) using lessons from iOS.
-   Add v2 commands: /meme, /sum, /code, /voice, /roast.
-   Localization wave 1: Spanish, Portuguese, Hindi.
-   Internal milestone: 25,000 total installs, 1,000 Pro subscribers, $5k MRR.

## **11.7 Months 9–12 — Platform Features**

-   User-defined commands MVP.
-   BYO-key support for OpenAI, Anthropic, fal.
-   Pro+ tier launches.
-   Public model leaderboard inside the app.
-   Internal milestone: 50,000 total installs, $5k+ MRR sustained.

## **11.8 Year 2 — Marketplace & Ambient**

-   Public command pack registry with one-tap install.
-   Contextual command suggestions.
-   Cross-app context (where OS allows).
-   First hire: senior mobile engineer to free founder for product strategy.

# **12\. Risks, Threats, and Mitigations**

| **Risk** | **Severity** | **Mitigation** |
| --- | --- | --- |
| Apple/Google ship comparable AI keyboard features for free | **High** | Out-execute on speed and breadth. Stay model-agnostic — they cannot. Build community moat via open source. Serve niches (privacy, BYO-key, custom commands) the giants will not. |
| App Store rejection or restrictive policy change | **High** | Build host app to clearly stand alone and pass 'unique functionality' bar. Proactive comms with App Review team. Maintain Android as a hedge. Maintain web fallback for power-user workflows. |
| Full Access activation rate is too low (<40%) | **High** | Spend disproportionate UX time on onboarding. Use the host-app playground to deliver value before activation. Reframe Full Access as 'enable AI features' with crystal-clear privacy explanation. Iterate ruthlessly based on funnel data. |
| Inference costs spike with viral growth | **Medium** | Aggressive free-tier rate limits. Cache common image prompts. Maintain 2+ provider relationships per command. Push power users to BYO-key tier. |
| Model providers change pricing or terms | **Medium** | Multi-provider routing from day one. Quarterly cost benchmarking. Open self-hosting path for users who want long-term predictability. |
| Privacy incident (real or perceived) | **High** | Open-source code is the public proof. Third-party security audit before public launch. Transparent incident response plan published in repo. No selling user data, ever, codified in public privacy policy. |
| Novelty wears off; retention collapses after 30 days | **Medium** | Ship retention-driving text commands (/fix, /tone, /reply) early — these become daily-use habits, image generation is the hook. Add new commands monthly to stay fresh. |
| Copycat keyboards launch with more capital | **Medium** | Speed of iteration. Open-source community. Build a brand and trust position no closed copycat can replicate quickly. |
| Founder burnout | **Medium** | Realistic scope per phase. Outsource non-critical work (legal, design polish) once revenue allows. First hire by month 12 if metrics support it. |

# **13\. Open Questions & Decisions Required**

These decisions are not yet locked. Each requires either user research, a prototype, or a strategic call by the founder.

## **13.1 Product**

-   Final brand name — is 'Turtle Keyboard' the project name or only the headline command?
-   Should /reply read clipboard automatically or require explicit paste? Privacy vs. magic tradeoff.
-   Should free tier images carry a watermark, or is that too aggressive for the wedge persona?
-   Anonymous-mode by default vs. account-required at first launch — which converts better?
-   Quick Panel (§6.6) default state — on by default, or opt-in? Tradeoff: discoverability vs. respecting muscle memory for double-space-for-period.
-   Panel layout — fixed grid vs. horizontal carousel — which surfaces commands faster on small screens?
-   Onboarding sequence — should we teach panel-first, slash-first, or show both in parallel? Each shapes the user's mental model of which path is primary.

## **13.2 Technical**

-   Cloudflare Workers vs. Bun on Fly.io for backend — to be benchmarked in week 2.
-   Where to host generated images for sharing (R2 vs. S3 vs. embedded base64)?
-   How aggressively to cache common /cap prompts — privacy implications?
-   Self-hosting documentation depth — minimal vs. polished from day one?

## **13.3 Business**

-   Final price points — $4.99 vs. $6.99 for Pro?
-   Should we accept VC at any point, or stay bootstrapped indefinitely?
-   How to handle inevitable acquisition interest from Apple, Google, Meta, or AI labs?
-   When to incorporate, in which jurisdiction?

# **14\. Appendix**

## **14.1 Glossary**

-   **Slash command.** A user input pattern beginning with '/' followed by a command name and arguments, used to invoke an AI action.
-   **Full Access.** iOS permission required for keyboard extensions to make network requests.
-   **App Group.** iOS mechanism for sharing data between a host app and its extensions (e.g. keyboard).
-   **Model routing.** Backend logic that decides which model from which provider should serve a given command for a given user.
-   **BYO-key.** Bring-your-own-API-key — a tier where users provide their own model provider keys and Turtle Keyboard charges only for the keyboard service.
-   **WASC.** Weekly Active Slash Commands — Turtle Keyboard's north-star metric. Counts commands invoked via either the slash form or the Quick Panel, since both run through the same dispatcher.
-   **Quick Panel.** Secondary invocation surface inside Turtle Keyboard, opened by double-tapping the space bar. Presents a tap-driven grid of registered commands. Complements but does not replace the slash primitive.

## **14.2 Inspirations and prior art**

-   Discord and Slack — established the slash command primitive for tens of millions of users.
-   Bitmoji and Grammarly Keyboard — proved third-party keyboards can reach scale on iOS despite Full Access friction.
-   Arc Browser — proved a small team can build a category-defining product against trillion-dollar incumbents through taste, speed, and a clear point of view.
-   Obsidian and Tailscale — proved open-client-plus-paid-service is a durable indie business model.
-   Raycast — slash commands as the central interaction model for productivity, on a different surface.

## **14.3 Why this PRD will change**

This document is a v1 plan, not a contract. It is expected to be wrong in specific ways — wrong commands, wrong pricing, wrong models, wrong order. Its job is to give the team (initially: a single founder) a clear shared frame to start building, learning, and revising. Every phase ends with a built-in checkpoint to update this document based on what real users do, not what we hoped they would do.

If the wedge persona uses Turtle Keyboard differently than this PRD predicts, the PRD changes. If retention curves require different commands, the roadmap changes. If a model provider emerges that changes the latency-cost-quality frontier, the routing changes. The fixed points are the vision and the strategic position. Everything below those is provisional and intentionally so.

*— End of document —*