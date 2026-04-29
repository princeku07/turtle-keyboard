import HeroClient from "./components/HeroClient";

const SLASH_COMMANDS = [
  "/cap a golden retriever as a samurai",
  "/cap monday mood as a renaissance painting",
  "/cap my cat but cyberpunk",
  "/cap a sticker that says 'on my way'",
  "/cap moody polaroid of a rainy window",
  "/cap birthday card for jared",
  "/cap a meme template for late replies",
  "/cap pixel-art coffee cup",
];

const FEATURES = [
  {
    tag: "01",
    title: "Slash. Picture. Send.",
    body: "Type /cap inside any text field — WhatsApp, iMessage, Tinder, Gmail — and a custom image lands in your composer in under two seconds. No app switching, no screenshot dance.",
    color: "bg-[#15803d]",
  },
  {
    tag: "02",
    title: "Every image model. One keyboard.",
    body: "Flux Schnell for speed, Flux Pro for the polished hero shot, SDXL for stylised stuff. We route each prompt to the model that's actually best at it. You don't pick. Unless you want to.",
    color: "bg-pink",
  },
  {
    tag: "03",
    title: "Open source. Audit the keys.",
    body: "The keyboard is MIT-licensed and on GitHub. Full Access scares you? Read the source. Or fork it. Trust is built, not declared.",
    color: "bg-blue text-cream",
  },
  {
    tag: "04",
    title: "2-second images, then everything else.",
    body: "v1 ships /cap — images in ~1.5s, into your chat. Text commands (/fix, /tone, /reply, /tl) follow once the image loop feels truly instant. We'd rather ship one thing that flies than five that limp.",
    color: "bg-orange",
  },
];

const COMMANDS_GRID = [
  { cmd: "/cap",     tag: "v1 · prompt",  desc: "Custom image from any prompt, ~1.5s, into the chat.",   color: "bg-pink text-cream" },
  { cmd: "/sticker", tag: "v1 · cutout",  desc: "Transparent-bg sticker, sized for iMessage + WhatsApp.", color: "bg-[#15803d]" },
  { cmd: "/edit",    tag: "v1 · inpaint", desc: "Drop an image, describe the change. Edits in place.",   color: "bg-blue text-cream" },
  { cmd: "/avatar",  tag: "v1 · you",     desc: "Restyle your selfie — anime, oil paint, pixel, 3D.",    color: "bg-orange" },
  { cmd: "/scene",   tag: "v1 · compose", desc: "Combine subject + setting into one staged image.",      color: "bg-cream border-2 border-ink" },
  { cmd: "/meme",    tag: "v1 · remix",   desc: "Meme template + AI-written caption, ready to paste.",   color: "bg-ink text-cream" },
];

const FAQ = [
  {
    q: "Wait, isn't a third-party keyboard sketchy?",
    a: "Reasonable concern. That's exactly why the keyboard is open source. You can read every line that touches your text — and we never log anything outside of slash commands. The closed part is the routing backend; the surface that sees your typing is fully auditable.",
  },
  {
    q: "Why can't Apple or Google just build this?",
    a: "They can build an AI keyboard. They can't build a model-agnostic one — Apple's keyboard exists to push Apple Intelligence; Gboard exists to push Gemini. The whole point of Turtle is that it routes to whichever model is actually best, and that's structurally off-limits for the platforms.",
  },
  {
    q: "How fast is fast?",
    a: "Target end-to-end: under 2 seconds for /cap, measured from the last keystroke to the image sitting in your composer. The latency budget is the product. If we can't hit that, it doesn't ship — which is exactly why text commands aren't in v1 yet.",
  },
  {
    q: "Do I have to pay?",
    a: "Free tier covers 20 images per day on Flux Schnell. Pro ($4.99/mo) unlocks premium image models (Flux Pro, SDXL, Ideogram for text-in-image), no watermark, priority queue, and custom commands. Pro+ lets you bring your own keys.",
  },
];

export default function Home() {
  return (
    <main className="min-h-screen w-full bg-cream text-ink overflow-x-clip">
      {/* NAV */}
      <header className="sticky top-0 z-40 backdrop-blur-md bg-cream/70 border-b-2 border-ink">
        <div className="mx-auto max-w-[1400px] px-4 sm:px-6 py-3 sm:py-4 flex items-center justify-between gap-3">
          <a
            href="#"
            className="flex items-center gap-2 font-mono font-bold text-base sm:text-lg shrink-0"
          >
            <span className="text-2xl sm:text-3xl leading-none">🐢</span>
            turtle
          </a>
          <nav className="hidden md:flex items-center gap-7 font-mono text-sm">
            <a href="#commands" className="hover:underline underline-offset-4">
              commands
            </a>
            <a href="#how" className="hover:underline underline-offset-4">
              how it works
            </a>
            <a href="#pricing" className="hover:underline underline-offset-4">
              pricing
            </a>
            <a href="#faq" className="hover:underline underline-offset-4">
              faq
            </a>
            <a
              href="https://github.com/princeku07/turtle-keyboard"
              target="_blank"
              rel="noreferrer"
              className="hover:underline underline-offset-4"
            >
              github ↗
            </a>
          </nav>
          <div className="flex items-center gap-2 shrink-0">
            <a
              href="#waitlist"
              className="font-mono text-xs sm:text-sm font-bold bg-ink text-cream px-3 sm:px-4 py-2 rounded-full border-2 border-ink hover:bg-[#15803d] hover:text-ink transition-colors whitespace-nowrap"
            >
              join waitlist →
            </a>
            <details className="md:hidden relative [&_summary::-webkit-details-marker]:hidden">
              <summary className="list-none cursor-pointer w-10 h-10 flex flex-col items-center justify-center gap-1 border-2 border-ink rounded-full bg-cream">
                <span className="block w-4 h-0.5 bg-ink" />
                <span className="block w-4 h-0.5 bg-ink" />
                <span className="block w-4 h-0.5 bg-ink" />
              </summary>
              <nav className="absolute right-0 mt-2 w-56 bg-cream border-2 border-ink rounded-2xl p-3 font-mono text-sm flex flex-col gap-1 shadow-[4px_4px_0_0_var(--ink)]">
                <a href="#commands" className="px-3 py-2 rounded-lg hover:bg-ink hover:text-cream">commands</a>
                <a href="#how" className="px-3 py-2 rounded-lg hover:bg-ink hover:text-cream">how it works</a>
                <a href="#pricing" className="px-3 py-2 rounded-lg hover:bg-ink hover:text-cream">pricing</a>
                <a href="#faq" className="px-3 py-2 rounded-lg hover:bg-ink hover:text-cream">faq</a>
                <a
                  href="https://github.com/princeku07/turtle-keyboard"
                  target="_blank"
                  rel="noreferrer"
                  className="px-3 py-2 rounded-lg hover:bg-ink hover:text-cream"
                >
                  github ↗
                </a>
              </nav>
            </details>
          </div>
        </div>
      </header>

      {/* HERO */}
      <HeroClient />

      {/* MARQUEE */}
      <div className="border-y-2 border-ink bg-ink text-cream py-2.5 sm:py-3 md:py-4 overflow-hidden">
        <div className="flex animate-marquee whitespace-nowrap">
          {[...SLASH_COMMANDS, ...SLASH_COMMANDS].map((cmd, i) => (
            <span
              key={i}
              className="font-mono text-sm sm:text-base md:text-xl lg:text-2xl mx-4 sm:mx-6 md:mx-8 inline-flex items-center gap-2 sm:gap-3 md:gap-4"
            >
              <span className="text-cream font-bold">
                {cmd.split(" ")[0]}
              </span>
              <span className="text-cream/80">
                {cmd.split(" ").slice(1).join(" ")}
              </span>
              <span className="text-pink">✺</span>
            </span>
          ))}
        </div>
      </div>

      {/* PROBLEM */}
      <section className="mx-auto max-w-[1400px] px-6 py-24">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-end">
          <div className="lg:col-span-7">
            <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-4">
              § the problem
            </div>
            <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.4rem,5vw,4.6rem)]">
              Seven steps to drop{" "}
              <span className="bg-pink text-cream px-2 -rotate-1 inline-block">
                one image
              </span>{" "}
              in the chat. The moment's already gone.
            </h2>
          </div>
          <div className="lg:col-span-5">
            <p className="text-lg text-ink/80 leading-relaxed">
              Stop. Open Midjourney or ChatGPT. Type the prompt. Wait.
              Long-press. Save. Switch back. Attach. By then the group chat has
              moved on three messages. The friction tax kills the long tail of
              small daily wins — birthday cards, stickers, the perfect reply
              meme.
            </p>
            <p className="mt-4 font-mono text-sm text-ink/60">
              Turtle removes the tax. The keyboard is the universal layer above
              every app.
            </p>
          </div>
        </div>

        {/* steps strip */}
        <div className="mt-14 grid grid-cols-2 md:grid-cols-7 gap-2 font-mono text-xs">
          {[
            "stop",
            "open mj",
            "prompt",
            "wait",
            "save",
            "switch",
            "attach",
          ].map((s, i) => (
            <div
              key={s}
              className="border-2 border-ink rounded-full px-3 py-2 text-center bg-cream line-through decoration-pink decoration-[3px]"
            >
              {i + 1}. {s}
            </div>
          ))}
        </div>
        <div className="mt-3 flex justify-center">
          <div className="font-mono text-sm bg-[#15803d] text-white border-2 border-ink rounded-full px-4 py-2">
            with turtle:{" "}
            <span className="font-bold">/cap your prompt · paste · done.</span>
          </div>
        </div>
      </section>

      {/* FEATURES */}
      <section
        id="how"
        className="bg-ink text-cream py-24 border-y-2 border-ink"
      >
        <div className="mx-auto max-w-[1400px] px-6">
          <div className="flex flex-col md:flex-row md:items-end justify-between gap-6 mb-14">
            <div>
              <div className="font-mono text-xs uppercase tracking-widest text-cream/60 mb-3">
                § how it works
              </div>
              <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.4rem,5vw,4.6rem)]">
                A keyboard the platforms{" "}
                <span
                  className="outline-text"
                  style={{ ["--tw-text-opacity" as never]: 1 }}
                >
                  can't ship.
                </span>
              </h2>
            </div>
            <p className="max-w-md text-cream/70">
              Apple's keyboard pushes Apple Intelligence. Gboard pushes Gemini.
              They will never route to whichever model is best. We will. That's
              the whole point.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
            {FEATURES.map((f) => (
              <div
                key={f.tag}
                className={`${f.color} text-ink rounded-3xl border-2 border-ink p-8 md:p-10 relative overflow-hidden`}
              >
                <div className="font-mono text-xs uppercase tracking-widest opacity-70 mb-4">
                  [ {f.tag} ]
                </div>
                <h3 className="font-sans font-black tracking-tight text-2xl md:text-3xl leading-tight">
                  {f.title}
                </h3>
                <p className="mt-4 text-base leading-relaxed opacity-90">
                  {f.body}
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* COMMANDS */}
      <section id="commands" className="mx-auto max-w-[1400px] px-6 py-24">
        <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
          § commands
        </div>
        <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.4rem,5vw,4.6rem)] max-w-4xl">
          Six image commands at launch.{" "}
          <span className="bg-blue text-cream px-2 -rotate-1 inline-block">
            Text
          </span>{" "}
          right after.
        </h2>
        <p className="mt-6 max-w-2xl text-lg text-ink/80">
          v1 is laser-focused on pictures: prompt, sticker, edit, avatar, scene,
          meme — all invoked by typing /, or tapping the Quick Panel (double-tap
          space). Text commands ship once the image loop is bulletproof.
        </p>

        <div className="mt-14 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
          {COMMANDS_GRID.map((c, i) => (
            <div
              key={c.cmd}
              className={`${c.color} rounded-3xl border-2 border-ink p-7 relative min-h-[230px] flex flex-col justify-between transition-transform hover:-translate-y-1 hover:shadow-[6px_6px_0_0_var(--ink)]`}
            >
              <div className="flex items-start justify-between">
                <span className="font-mono font-black text-3xl md:text-4xl tracking-tight">
                  {c.cmd}
                </span>
                <span className="font-mono text-[10px] uppercase tracking-widest border-2 border-current rounded-full px-2 py-0.5 opacity-80">
                  {c.tag}
                </span>
              </div>
              <div>
                <div className="font-mono text-xs opacity-60 mb-2">
                  no.{String(i + 1).padStart(2, "0")}
                </div>
                <p className="text-base leading-snug">{c.desc}</p>
              </div>
            </div>
          ))}
        </div>

        <div className="mt-10 flex flex-wrap items-center gap-3 font-mono text-sm">
          <span className="opacity-60">on the roadmap:</span>
          {[
            "/fix",
            "/tone",
            "/reply",
            "/tl",
            "/sum",
            "/jared (your custom)",
          ].map((t) => (
            <span
              key={t}
              className="border-2 border-ink rounded-full px-3 py-1.5 bg-cream"
            >
              {t}
            </span>
          ))}
        </div>
      </section>

      {/* SHOW DON'T TELL — fake chat */}
      <section className="mx-auto max-w-[1400px] px-6 pb-24">
        <div className="rounded-[2rem] border-2 border-ink bg-cream p-6 md:p-10 grain">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-10 items-center">
            <div>
              <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
                § in the wild
              </div>
              <h3 className="font-sans font-black text-3xl md:text-5xl leading-[0.95] tracking-[-0.02em]">
                The moment doesn't{" "}
                <span className="line-through decoration-pink decoration-[4px]">
                  die
                </span>{" "}
                wait for a context switch.
              </h3>
              <p className="mt-5 text-ink/80 max-w-md">
                The group chat is moving. Your reply needs to land in the next 8
                seconds. You type{" "}
                <span className="font-mono bg-ink text-cream px-1.5 rounded">
                  /cap a samurai cat
                </span>
                . Two seconds later, image is on the clipboard. Paste. Send.
                Status acquired.
              </p>
            </div>

            {/* mock device */}
            <div className="relative mx-auto w-full max-w-sm">
              <div className="rounded-[2.5rem] border-2 border-ink bg-cream p-3 shadow-[10px_10px_0_0_var(--ink)]">
                <div className="rounded-[2rem] border-2 border-ink bg-white overflow-hidden">
                  {/* messages */}
                  <div className="p-4 bg-[#e9e2d2] space-y-3 min-h-[320px] font-sans text-sm">
                    <div className="flex">
                      <div className="bg-white border border-ink/10 rounded-2xl rounded-bl-sm px-3 py-2 max-w-[70%]">
                        jared just sent a samurai meme 😭
                      </div>
                    </div>
                    <div className="flex justify-end">
                      <div className="bg-[#15803d] border-2 border-ink rounded-2xl rounded-br-sm px-3 py-2 max-w-[70%] font-mono">
                        /cap a golden retriever as a samurai
                        <span className="caret">▍</span>
                      </div>
                    </div>
                    <div className="flex justify-end">
                      <div className="bg-pink text-cream border-2 border-ink rounded-2xl rounded-br-sm p-2 max-w-[70%]">
                        <div className="aspect-square w-44 rounded-xl bg-gradient-to-br from-orange via-pink to-blue grain border border-ink/30 flex items-center justify-center text-3xl">
                          🐕‍🦺⚔️
                        </div>
                        <div className="font-mono text-[10px] mt-1 opacity-90">
                          generated · 1.6s · flux schnell
                        </div>
                      </div>
                    </div>
                    <div className="flex">
                      <div className="bg-white border border-ink/10 rounded-2xl rounded-bl-sm px-3 py-2 max-w-[70%]">
                        😂😂 send it everywhere
                      </div>
                    </div>
                  </div>
                  {/* keyboard */}
                  <div className="bg-[#d6d0c2] p-2 border-t-2 border-ink">
                    <div className="flex gap-1.5 mb-1.5">
                      <button className="flex-1 bg-pink text-cream border-2 border-ink rounded-md py-2 font-mono text-xs">
                        /cap
                      </button>
                      <button className="flex-1 bg-[#15803d] text-cream border-2 border-ink rounded-md py-2 font-mono text-xs">
                        /fix
                      </button>
                      <button className="flex-1 bg-blue text-cream border-2 border-ink rounded-md py-2 font-mono text-xs">
                        /reply
                      </button>
                      <button className="flex-1 bg-orange border-2 border-ink rounded-md py-2 font-mono text-xs">
                        /tone
                      </button>
                    </div>
                    <div className="grid grid-cols-10 gap-1">
                      {"qwertyuiopasdfghjkl_zxcvbnm__".split("").map((k, i) => (
                        <div
                          key={i}
                          className={`h-6 rounded bg-white border border-ink/30 flex items-center justify-center text-[10px] font-mono ${
                            k === "_" ? "opacity-0" : ""
                          }`}
                        >
                          {k}
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* PRICING */}
      <section id="pricing" className="mx-auto max-w-[1400px] px-6 py-24">
        <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
          § pricing
        </div>
        <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.4rem,5vw,4.6rem)]">
          Free forever. <span className="outline-text">Pro if</span> you want
          the good stuff.
        </h2>

        <div className="mt-14 grid grid-cols-1 md:grid-cols-3 gap-5">
          {[
            {
              name: "Free",
              price: "$0",
              tag: "for everyone",
              color: "bg-cream border-2 border-ink",
              perks: [
                "20 images/day",
                "Flux Schnell (~1.5s)",
                "/cap, /sticker, /meme, /edit",
                "Small watermark",
                "Free forever",
              ],
            },
            {
              name: "Pro",
              price: "$4.99",
              suffix: "/mo",
              tag: "★ most popular",
              color: "bg-[#15803d] text-cream border-2 border-ink relative",
              perks: [
                "Unlimited images",
                "Flux Pro, SDXL, Ideogram",
                "No watermark",
                "Priority queue",
                "Custom image commands",
              ],
            },
            {
              name: "Pro+",
              price: "$9.99",
              suffix: "/mo",
              tag: "BYO keys",
              color: "bg-ink text-cream border-2 border-ink",
              perks: [
                "Everything in Pro",
                "Bring your own API keys",
                "Cross-device sync",
                "First access when text commands ship",
                "We charge zero margin on inference",
              ],
            },
          ].map((p) => (
            <div key={p.name} className={`${p.color} rounded-3xl p-8 relative`}>
              {p.tag && (
                <span className="absolute -top-3 left-6 bg-pink text-cream font-mono text-[10px] uppercase tracking-widest border-2 border-ink rounded-full px-3 py-1">
                  {p.tag}
                </span>
              )}
              <div className="font-mono text-sm uppercase tracking-widest opacity-70 mb-4">
                {p.name}
              </div>
              <div className="flex items-baseline gap-1 mb-6">
                <span className="font-sans font-black text-6xl tracking-tight">
                  {p.price}
                </span>
                {p.suffix && (
                  <span className="font-mono opacity-70">{p.suffix}</span>
                )}
              </div>
              <ul className="space-y-2 mb-8">
                {p.perks.map((perk) => (
                  <li key={perk} className="flex gap-2 text-sm">
                    <span className="font-mono opacity-60">/</span>
                    <span>{perk}</span>
                  </li>
                ))}
              </ul>
              <a
                href="#waitlist"
                className={`block text-center font-mono text-sm font-bold rounded-full py-3 border-2 border-ink ${
                  p.name === "Pro+" ? "bg-cream text-ink" : "bg-ink text-cream"
                }`}
              >
                {p.name === "Free" ? "start free" : "join waitlist"}
              </a>
            </div>
          ))}
        </div>
      </section>

      {/* FAQ */}
      <section id="faq" className="mx-auto max-w-[1400px] px-6 py-24">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-12">
          <div className="lg:col-span-4">
            <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
              § faq
            </div>
            <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.4rem,5vw,3.6rem)]">
              The skeptical questions.
            </h2>
            <p className="mt-5 text-ink/70">
              The ones we'd ask too. The honest answers.
            </p>
          </div>
          <div className="lg:col-span-8 space-y-4">
            {FAQ.map((item) => (
              <details
                key={item.q}
                className="group border-2 border-ink rounded-3xl bg-cream p-6 [&_summary::-webkit-details-marker]:hidden"
              >
                <summary className="flex items-center justify-between cursor-pointer list-none">
                  <span className="font-sans font-bold text-xl tracking-tight pr-4">
                    {item.q}
                  </span>
                  <span className="font-mono text-2xl shrink-0 transition-transform group-open:rotate-45">
                    +
                  </span>
                </summary>
                <p className="mt-4 text-ink/80 leading-relaxed">{item.a}</p>
              </details>
            ))}
          </div>
        </div>
      </section>

      {/* CTA */}
      <section id="waitlist" className="mx-auto max-w-[1400px] px-6 pb-24">
        <div className="rounded-[2rem] border-2 border-ink bg-[#15803d] text-cream p-10 md:p-16 text-center relative overflow-hidden">
          <div className="absolute -top-10 -left-10 w-40 h-40 rounded-full bg-pink border-2 border-ink wobble" />
          <div className="absolute -bottom-12 -right-8 w-32 h-32 rounded-full bg-blue border-2 border-ink float-y" />
          <div className="relative">
            <div className="font-mono text-xs uppercase tracking-widest mb-4">
              § join the alpha
            </div>
            <h2 className="font-sans font-black tracking-[-0.04em] leading-[0.9] text-[clamp(2.6rem,7vw,6rem)] max-w-4xl mx-auto">
              One slash. <span className="outline-text">Any image.</span> In
              your chat.
            </h2>
            <form className="mt-10 flex flex-col sm:flex-row gap-3 max-w-xl mx-auto">
              <input
                type="email"
                placeholder="you@somewhere.cool"
                className="flex-1 bg-cream border-2 border-ink rounded-full px-5 py-4 font-mono text-base focus:outline-none focus:bg-white"
              />
              <button
                type="submit"
                className="bg-ink text-cream font-mono font-bold px-6 py-4 rounded-full border-2 border-ink hover:bg-pink hover:text-cream transition-colors"
              >
                grab my spot →
              </button>
            </form>
            <div className="mt-6 font-mono text-xs opacity-70">
              ~3,200 people in line · ios alpha rolling out monthly
            </div>
          </div>
        </div>
      </section>

      {/* FOOTER */}
      <footer className="border-t-2 border-ink">
        <div className="mx-auto max-w-[1400px] px-6 py-10 flex flex-col md:flex-row items-center justify-between gap-6 font-mono text-sm">
          <div className="flex items-center gap-3">
            <span className="text-2xl leading-none">🐢</span>
            <span className="font-bold">turtle</span>
            <span className="opacity-60">© 2026 · MIT-licensed</span>
          </div>
          <div className="flex items-center gap-6">
            <a href="#" className="hover:underline">
              github
            </a>
            <a href="#" className="hover:underline">
              twitter
            </a>
            <a href="#" className="hover:underline">
              privacy
            </a>
            <a href="#" className="hover:underline">
              discord
            </a>
          </div>
          <div className="opacity-60">slow and steady. ✶</div>
        </div>
      </footer>
    </main>
  );
}
