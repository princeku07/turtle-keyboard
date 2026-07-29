import Link from "next/link";
import LivePollDemo from "./components/LivePollDemo";
import PhoneMock from "./components/PhoneMock";
import Reveal from "./components/Reveal";
import WaitlistForm from "./components/WaitlistForm";

const GITHUB_URL = "https://github.com/princeku07/turtle-keyboard";

export default function Home() {
  return (
    <main className="relative min-h-screen w-full overflow-x-clip text-navy">
      <Masthead />
      <Hero />
      <InPractice />
      <Privacy />
      <Builders />
      <Footer />
    </main>
  );
}

/* ────────────────────────────────────────────────────────────────
   shared marks
   ──────────────────────────────────────────────────────────────── */
function TurtleMark({ className = "" }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 46 30"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      <path d="M9 20a14 12 0 0 1 28 0" />
      <path d="M16 20c0-5 2.6-8.2 7-8.2S30 15 30 20" opacity="0.55" />
      <path d="M5 20h33" />
      <circle cx="41" cy="17.5" r="3" />
      <path d="M5 20l-2.5 3.5" />
      <path d="M13 20l-2.5 5M31 20l2.5 5" />
    </svg>
  );
}

/** section header — folio number, kicker, serif title, optional lede */
function SectionHead({
  folio,
  kicker,
  title,
  lede,
  light = false,
}: {
  folio: string;
  kicker: string;
  title: React.ReactNode;
  lede?: string;
  light?: boolean;
}) {
  return (
    <div className="max-w-3xl">
      <div className="flex items-center gap-4">
        <span className={`folio text-sm ${light ? "!text-turq-bright" : ""}`}>{folio}</span>
        <span className={`kicker ${light ? "!text-white/70" : ""}`}>{kicker}</span>
      </div>
      <h2
        className={`mt-5 font-display text-[clamp(2.2rem,5vw,3.6rem)] font-semibold leading-[1.02] tracking-[-0.015em] ${
          light ? "text-white" : "text-ink"
        }`}
      >
        {title}
      </h2>
      {lede && (
        <p className={`mt-5 text-lg leading-relaxed ${light ? "text-white/70" : "text-slate"}`}>
          {lede}
        </p>
      )}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────
   masthead
   ──────────────────────────────────────────────────────────────── */
function Masthead() {
  return (
    <header className="fixed inset-x-0 top-0 z-50 border-b border-ink/10 bg-sand/85 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6">
        <Link href="/" className="flex items-baseline gap-2 text-ink">
          <TurtleMark className="w-7 translate-y-1 text-ink" />
          <span className="font-display text-[22px] font-semibold tracking-tight">Turtle</span>
          <span className="hidden font-mono text-[10px] uppercase tracking-[0.24em] text-slate sm:inline">
            Keyboard
          </span>
        </Link>
        <nav className="hidden items-center gap-8 font-mono text-[11px] uppercase tracking-[0.16em] text-slate md:flex">
          <a href="#current" className="transition-colors duration-300 hover:text-ink">In&nbsp;practice</a>
          <a href="#deep" className="transition-colors duration-300 hover:text-ink">Privacy</a>
          <a href="#horizon" className="transition-colors duration-300 hover:text-ink">Builders</a>
          <Link href="/blog" className="transition-colors duration-300 hover:text-ink">Journal</Link>
          <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-ink">
            GitHub
          </a>
        </nav>
        <a href="#waitlist" className="btn-grad rounded-full px-4 py-2 font-mono text-[12px] font-semibold uppercase tracking-wider">
          Join beta
        </a>
      </div>
    </header>
  );
}

/* ────────────────────────────────────────────────────────────────
   §1 THE SURFACE — the editorial cover.
   ──────────────────────────────────────────────────────────────── */
function Hero() {
  return (
    <section className="relative overflow-hidden">
      {/* the coastal vista — the cover photograph, warmed into the paper */}
      <div className="absolute inset-0" aria-hidden>
        <img
          src="/bg-hero.jpg"
          alt=""
          className="h-full w-full object-cover object-[center_38%]"
        />
        <div className="absolute inset-0 bg-gradient-to-b from-sand/70 via-sand/25 to-sand" />
      </div>

      <div className="relative z-10 mx-auto max-w-6xl px-6 pb-16 pt-28 sm:pt-36">
        <Reveal>
          <div className="kicker flex items-center gap-3">
            <span className="h-px w-8 bg-iris/60" />
            The open interface for your digital life
          </div>
        </Reveal>

        <Reveal delay={90}>
          <h1 className="mt-7 max-w-[15ch] font-display text-[clamp(3rem,8.5vw,6.6rem)] font-semibold leading-[0.96] tracking-[-0.02em] text-ink">
            Slash is the new{" "}
            <span className="italic text-iris">&ldquo;hey&nbsp;siri.&rdquo;</span>
          </h1>
        </Reveal>

        <div className="mt-12 grid items-end gap-x-10 gap-y-12 lg:grid-cols-12">
          {/* left — copy & capture */}
          <div className="lg:col-span-6">
            <Reveal delay={170}>
              <p className="max-w-md text-xl leading-[1.5] text-slate">
                Summon polls, Notion, and Slack in any app with a single
                keystroke. Processed on your phone. Open source.
              </p>
            </Reveal>

            <Reveal delay={250}>
              <div className="mt-9 flex flex-wrap items-center gap-x-7 gap-y-4">
                <a href="#waitlist" className="btn-grad rounded-full px-7 py-3.5 text-[15px] font-semibold">
                  Join the beta →
                </a>
                <a href="#current" className="btn-ghost font-mono text-[13px] uppercase tracking-wider">
                  see it work
                </a>
              </div>
              <div className="kicker mt-5">beta · ios + android · open source</div>
            </Reveal>

            <Reveal delay={330}>
              <div className="rule mt-10 flex flex-wrap items-center gap-x-4 gap-y-2 pt-5">
                <span className="kicker !tracking-[0.18em]">Works in</span>
                <span className="font-mono text-[12px] text-slate">
                  whatsapp · imessage · slack · gmail · notion
                </span>
              </div>
            </Reveal>
          </div>

          {/* right — the product, as a plate */}
          <Reveal delay={230} className="lg:col-span-6 lg:justify-self-end">
            <figure className="relative">
              <PhoneMock />
              <figcaption className="kicker mt-5 flex items-center gap-2 text-ink/70">
                <span className="text-iris">Fig.&nbsp;01</span>
                <span className="h-px w-5 bg-ink/25" />
                /poll, running live inside a group chat
              </figcaption>
            </figure>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

/* ────────────────────────────────────────────────────────────────
   §2 IN PRACTICE — three figures.
   ──────────────────────────────────────────────────────────────── */
const NUMERALS = ["i", "ii", "iii"];

function InPractice() {
  const figures = [
    {
      cmd: "/poll",
      title: "Settle the debate",
      body: "Drop a live poll into any thread. Everyone votes in their browser — no app, no account.",
      scene: <PollScene />,
    },
    {
      cmd: "/notion",
      title: "Never leave the app",
      body: "Pull a quote from your workspace straight into the email you're writing.",
      scene: <NotionScene />,
    },
    {
      cmd: "/quiz",
      title: "Spark the room",
      body: "Turn one prompt into a playable quiz with a live scoreboard the whole chat can watch.",
      scene: <QuizScene />,
    },
  ];
  return (
    <section id="current" className="relative scroll-mt-20 border-t border-ink/12 py-24 sm:py-32">
      <div className="mx-auto max-w-6xl px-6">
        <Reveal>
          <SectionHead
            folio="01 / 03"
            kicker="In practice"
            title={<>Don&rsquo;t fight the current.</>}
            lede="Switching apps breaks the conversation. Turtle brings the tool to the text field instead."
          />
        </Reveal>

        <div className="mt-16 grid gap-x-8 gap-y-14 md:grid-cols-3">
          {figures.map((f, i) => (
            <Reveal key={f.cmd} delay={i * 120}>
              <figure>
                <div className="sea-glass rounded-2xl p-4">{f.scene}</div>
                <figcaption className="mt-5 flex items-baseline gap-3">
                  <span className="folio text-sm">{NUMERALS[i]}</span>
                  <div>
                    <div className="font-mono text-[12px] font-semibold text-iris">{f.cmd}</div>
                    <h3 className="mt-1 font-display text-[1.4rem] font-semibold tracking-tight text-ink">
                      {f.title}
                    </h3>
                    <p className="mt-2 text-[15px] leading-relaxed text-slate">{f.body}</p>
                  </div>
                </figcaption>
              </figure>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

function CmdLine({ cmd, rest }: { cmd: string; rest: string }) {
  return (
    <div className="flex items-center gap-2 rounded-lg border border-ink/10 bg-sand/60 px-3 py-2">
      <span className="font-mono text-[11px] font-semibold text-iris">{cmd}</span>
      <span className="truncate font-mono text-[11px] text-slate">{rest}</span>
    </div>
  );
}

function PollScene() {
  return (
    <div className="space-y-2.5">
      <CmdLine cmd="/poll" rest="Movie, Bowling, or Drinks?" />
      <LivePollDemo />
    </div>
  );
}

function NotionScene() {
  return (
    <div className="space-y-2.5">
      <CmdLine cmd="/notion" rest="save quote from brief" />
      <div className="rounded-2xl border border-ink/10 bg-sand/50 p-3.5">
        <div className="flex items-center gap-2.5">
          <span className="grid h-9 w-9 place-items-center rounded-full bg-iris/12 text-iris">✓</span>
          <div className="leading-tight">
            <div className="text-[12px] font-semibold text-ink">Saved to Notion</div>
            <div className="text-[10px] text-slate">reading list · just now</div>
          </div>
        </div>
        <blockquote className="mt-3 rounded-r-lg border-l-2 border-iris bg-white-warm px-3 py-2 font-display text-[12px] italic leading-relaxed text-ink/85">
          &ldquo;iOS and Android at full parity, keyboard-first.&rdquo;
        </blockquote>
        <div className="mt-2.5 font-mono text-[10px] text-slate">you never left your email</div>
      </div>
    </div>
  );
}

function QuizScene() {
  return (
    <div className="space-y-2.5">
      <CmdLine cmd="/quiz" rest="90s music trivia" />
      <div className="rounded-2xl border border-ink/10 bg-sand/50 p-3.5">
        <div className="flex items-center gap-2.5">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-iris text-base">🎵</span>
          <div className="leading-tight">
            <div className="text-[12px] font-semibold text-ink">90s Music Trivia</div>
            <div className="text-[10px] text-slate">10 rounds · play right in chat</div>
          </div>
        </div>
        <div className="btn-grad mt-3 rounded-full py-1.5 text-center font-mono text-[11px] font-semibold">
          tap to play →
        </div>
        <div className="mt-2.5 font-mono text-[10px] text-slate">3 friends already in · no download</div>
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────
   §3 PRIVACY — the duotone deep. a full-bleed coastal plate.
   ──────────────────────────────────────────────────────────────── */
function Privacy() {
  return (
    <section id="deep" className="relative scroll-mt-20">
      <div className="relative isolate overflow-hidden">
        {/* duotone ocean */}
        <div className="duotone absolute inset-0" aria-hidden>
          <img src="/bg-hero.jpg" alt="" />
        </div>

        <div className="relative mx-auto max-w-6xl px-6 py-28 sm:py-36">
          <Reveal>
            <SectionHead
              folio="02 / 03"
              kicker="Privacy"
              light
              title={<>Safe in the deep.</>}
            />
          </Reveal>

          <Reveal delay={120}>
            <p className="mt-6 max-w-xl text-lg leading-relaxed text-white/75">
              Turtle only ever sends the command you type after a slash. Your
              ordinary typing — passwords, drafts, everything else — is never
              captured, logged, or sent. Not as a promise; as architecture, in
              code you can read.
            </p>
          </Reveal>

          <Reveal delay={200}>
            <p className="mt-8 font-mono text-sm tracking-wide text-turq-bright">
              → your data stays inside the shell
            </p>
          </Reveal>

          <Reveal delay={280}>
            <div className="mt-10 flex flex-wrap gap-3">
              {["only commands are sent", "no keystroke logging", "open source · MIT"].map((t) => (
                <span
                  key={t}
                  className="rounded-full border border-white/25 px-4 py-2 font-mono text-xs text-white/85"
                >
                  {t}
                </span>
              ))}
            </div>
          </Reveal>
        </div>

        <TurtleMark className="absolute bottom-7 right-7 w-9 text-white/25" />
      </div>
    </section>
  );
}

/* ────────────────────────────────────────────────────────────────
   §4 BUILDERS — the developer spread.
   ──────────────────────────────────────────────────────────────── */
function Builders() {
  return (
    <section id="horizon" className="relative scroll-mt-20 border-t border-ink/12 py-24 sm:py-32">
      <div className="mx-auto grid max-w-6xl items-center gap-x-14 gap-y-12 px-6 lg:grid-cols-2">
        <div>
          <Reveal>
            <SectionHead
              folio="03 / 03"
              kicker="For builders"
              title={<>An ocean of possibilities.</>}
              lede="Turtle is built on the Model Context Protocol. If a tool has an API, you can wire it to a slash command in about ten lines — and it works in every app."
            />
          </Reveal>
          <Reveal delay={160}>
            <div className="mt-9 flex flex-wrap items-center gap-x-7 gap-y-4">
              <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="btn-grad rounded-full px-7 py-3.5 text-[15px] font-semibold">
                Star on GitHub ↗
              </a>
              <Link href="/blog" className="btn-ghost font-mono text-[13px] uppercase tracking-wider">
                read the journal
              </Link>
            </div>
          </Reveal>
        </div>

        <Reveal delay={140}>
          <figure>
            <CodeWindow />
            <figcaption className="kicker mt-4 flex items-center gap-2">
              <span className="text-iris">Fig.&nbsp;02</span>
              <span className="h-px w-5 bg-ink/25" />
              a working command, start to finish
            </figcaption>
          </figure>
        </Reveal>
      </div>
    </section>
  );
}

function CodeWindow() {
  const K = (t: string) => <span className="text-iris">{t}</span>;
  const S = (t: string) => <span className="text-[#9a6a3c]">{t}</span>;
  const C = (t: string) => <span className="italic text-slate/70">{t}</span>;
  const F = (t: string) => <span className="text-ink">{t}</span>;

  const lines: React.ReactNode[] = [
    <>{K("import")} {"{ tool }"} {K("from")} {S('"@turtle/mcp"')}</>,
    <>&nbsp;</>,
    <>{C("// /notion-sync — save the draft you're typing")}</>,
    <>{K("export default")} {F("tool")}({S('"/notion-sync"')}, {K("async")} ({"{ text }"}) {"=> {"}</>,
    <>{"  "}{K("const")} page = {K("await")} notion.{F("save")}(text)</>,
    <>{"  "}{K("return")} {S("`saved → ${page.url}`")}  {C("// lands in your chat")}</>,
    <>{"}"})</>,
  ];

  return (
    <div className="sea-glass overflow-hidden rounded-2xl">
      <div className="flex items-center gap-2 border-b border-ink/10 px-5 py-3.5">
        <span className="h-2.5 w-2.5 rounded-full bg-ink/12" />
        <span className="h-2.5 w-2.5 rounded-full bg-ink/12" />
        <span className="h-2.5 w-2.5 rounded-full bg-ink/12" />
        <span className="ml-3 font-mono text-xs text-slate">notion-sync.ts</span>
        <span className="ml-auto font-mono text-[10px] uppercase tracking-widest text-iris/70">
          turtle sdk
        </span>
      </div>
      <div className="overflow-x-auto p-5">
        <pre className="font-mono text-[12.5px] leading-[1.9] text-ink/90">
          {lines.map((l, i) => (
            <div key={i} className="flex">
              <span className="w-6 shrink-0 select-none text-right text-ink/25">{i + 1}</span>
              <span className="whitespace-pre pl-4">{l}</span>
            </div>
          ))}
        </pre>
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────
   §5 THE COLOPHON — footer.
   ──────────────────────────────────────────────────────────────── */
function Footer() {
  return (
    <footer id="waitlist" className="relative scroll-mt-20 border-t border-ink/12">
      <div className="mx-auto max-w-3xl px-6 pb-14 pt-24 text-center sm:pt-32">
        <Reveal>
          <TurtleMark className="mx-auto w-12 text-iris" />
          <h2 className="mt-8 font-display text-[clamp(2.4rem,6vw,4.2rem)] font-semibold leading-[1.0] tracking-[-0.02em] text-ink">
            Slow and steady.
            <br />
            <span className="italic text-iris">Wins the race.</span>
          </h2>
        </Reveal>

        <Reveal delay={140}>
          <div className="mt-11 flex justify-center">
            <WaitlistForm center />
          </div>
        </Reveal>

        <Reveal delay={230}>
          <nav className="mt-14 flex flex-wrap items-center justify-center gap-x-7 gap-y-3 font-mono text-[12px] uppercase tracking-[0.14em] text-slate">
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-ink">github ↗</a>
            <Link href="/blog" className="transition-colors duration-300 hover:text-ink">journal</Link>
            <a href="#waitlist" className="transition-colors duration-300 hover:text-ink">waitlist</a>
            <a href="#" className="transition-colors duration-300 hover:text-ink">privacy</a>
          </nav>
          <p className="mt-9 font-mono text-[11px] text-slate/70">
            Set in Fraunces &amp; Geist · 🐢 turtle © 2026 · MIT
          </p>
        </Reveal>
      </div>
    </footer>
  );
}
