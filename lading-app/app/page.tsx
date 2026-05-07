import HeroClient from "./components/HeroClient";
import { CommandActivityProvider } from "./components/CommandActivity";
import {
  DynamicProblem,
  DynamicUseCases,
  DynamicMock,
  DynamicMarquee,
  DynamicCta,
} from "./components/DynamicSections";

const FEATURES = [
  {
    tag: "01",
    title: "Slash. Picture. Send.",
    body: "Type a slash command inside any text field — WhatsApp, iMessage, Tinder, Gmail — and a custom image lands in your composer. No app switching, no screenshot dance.",
    color: "bg-lime",
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
    title: "Images, payments, Notion, Slack — one keyboard.",
    body: "v1 ships six image commands plus three live integrations: /split (your own Google Sheet), /notion (LLM-structured pages), /slack (post anywhere, with #channel routing). Text commands (/fix, /tone, /reply, /tl) follow.",
    color: "bg-orange",
  },
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
              className="font-mono text-xs sm:text-sm font-bold bg-ink text-cream px-3 sm:px-4 py-2 rounded-full border-2 border-ink hover:bg-lime hover:text-ink transition-colors whitespace-nowrap"
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

      <CommandActivityProvider>
        {/* HERO — full viewport, keyboard pinned to floor */}
        <HeroClient />

        {/* MARQUEE — adapts to active command */}
        <DynamicMarquee />

        {/* === COMMAND-AWARE SECTIONS (above the fold once you scroll) === */}
        <DynamicProblem />
        <DynamicUseCases />
        <DynamicMock />

        {/* === STATIC SECTIONS (below) === */}
        <section
          id="how"
          className="bg-ink text-cream py-20 sm:py-24 border-y-2 border-ink"
        >
          <div className="mx-auto max-w-[1400px] px-6">
            <div className="flex flex-col md:flex-row md:items-end justify-between gap-6 mb-12">
              <div>
                <div className="font-mono text-xs uppercase tracking-widest text-cream/60 mb-3">
                  § how it works
                </div>
                <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.2rem,5vw,4.4rem)]">
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

        {/* PRICING */}
        <section id="pricing" className="mx-auto max-w-[1400px] px-6 py-20 sm:py-24">
          <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
            § pricing
          </div>
          <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.2rem,5vw,4.4rem)]">
            Free forever. <span className="outline-text">Pro if</span> you want
            the good stuff.
          </h2>

          <div className="mt-12 grid grid-cols-1 md:grid-cols-3 gap-5">
            {[
              {
                name: "Free",
                price: "$0",
                tag: "for everyone",
                color: "bg-cream border-2 border-ink",
                perks: [
                  "20 images/day",
                  "Flux Schnell",
                  "All image commands",
                  "Split, Notion, Slack integrations",
                  "Free forever",
                ],
              },
              {
                name: "Pro",
                price: "$4.99",
                suffix: "/mo",
                tag: "★ most popular",
                color: "bg-lime text-cream border-2 border-ink relative",
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
        <section id="faq" className="mx-auto max-w-[1400px] px-6 py-20 sm:py-24">
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-12">
            <div className="lg:col-span-4">
              <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
                § faq
              </div>
              <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.2rem,5vw,3.6rem)]">
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

        {/* CTA — adapts to active command */}
        <section id="waitlist" className="mx-auto max-w-[1400px] px-6 pb-24">
          <DynamicCta />
        </section>
      </CommandActivityProvider>

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
