import type { Metadata } from "next";
import Link from "next/link";
import Reveal from "../components/Reveal";
import TurtleMark from "../components/TurtleMark";
import StoreBadges from "../components/StoreBadges";
import { SITE_URL } from "@/lib/blog";
import { GITHUB_URL, appJsonLd } from "@/lib/store";

export const metadata: Metadata = {
  title: "Open-Source AI Keyboard for iOS & Android — Turtle",
  description:
    "Turtle is an open-source (MIT) AI keyboard for iPhone and Android. Type a slash to bring polls, quizzes, AI, and your favorite tools into any chat.",
  alternates: { canonical: "/open-source-ai-keyboard" },
  keywords: [
    "open source AI keyboard",
    "open source keyboard app",
    "open source AI keyboard iPhone",
    "open source AI keyboard Android",
    "slash command keyboard",
    "MIT licensed keyboard",
  ],
  openGraph: {
    type: "website",
    url: "/open-source-ai-keyboard",
    title: "Turtle — the open-source AI keyboard",
    description:
      "The open-source AI keyboard that turns any text field into a command line: polls, quizzes, AI, and connected tools in any chat. iOS + Android, MIT-licensed.",
  },
  twitter: {
    card: "summary_large_image",
    title: "Turtle — the open-source AI keyboard",
    description:
      "Open-source (MIT) AI keyboard for iOS & Android. Slash commands bring polls, quizzes, AI, and your tools into any chat.",
  },
};

const FAQS = [
  {
    q: "What is an open-source AI keyboard?",
    a: "An open-source AI keyboard is a mobile keyboard with AI-powered features — commands, rewriting, polls, connected tools — whose full source code is published under an open license, so anyone can read and audit what it does. Turtle is one; its iOS and Android code is MIT-licensed and public on GitHub.",
  },
  {
    q: "Is there an open-source AI keyboard for iPhone?",
    a: "Yes. Turtle is an open-source AI keyboard for iOS (and Android), built as a native keyboard extension. Most iPhone AI keyboards — CleverType, SwiftKey, Gboard — are closed-source; most open-source keyboards, like HeliBoard, have no AI features. Turtle is built to be both open and genuinely useful.",
  },
  {
    q: "What can Turtle actually do?",
    a: "Slash commands bring tools into any chat: /poll and /quiz drop live interactive widgets, /summarize condenses a thread, and /github, /notion, and /linear connect those apps straight to the keyboard. Anything with an API can become a command through the open MCP-based plugin system.",
  },
  {
    q: "Does Turtle read everything I type?",
    a: "No. Turtle only ever acts on what you type after a slash command — that command's text is sent to fulfill it (say, generating an image or creating a poll). Ordinary typing is never captured, logged, or transmitted, and because the keyboard is open source, that invariant is one you can verify in the code.",
  },
  {
    q: "What license is Turtle released under?",
    a: "The keyboard clients are MIT-licensed — read them, fork them, ship your own build, or write your own slash commands against the open MCP-based plugin system. The routing backend that powers premium models is a separate, closed-source service; the keyboard itself is fully open.",
  },
];

function jsonLd() {
  return [
    appJsonLd(`${SITE_URL}/open-source-ai-keyboard`),
    {
      "@context": "https://schema.org",
      "@type": "FAQPage",
      mainEntity: FAQS.map((f) => ({
        "@type": "Question",
        name: f.q,
        acceptedAnswer: { "@type": "Answer", text: f.a },
      })),
    },
  ];
}

/** the market-gap table: the only keyboard that's both open AND does things */
const MATRIX: {
  name: string;
  ai: string;
  tools: string;
  open: "yes" | "no" | "partial";
  turtle?: boolean;
}[] = [
  { name: "Turtle", ai: "Rewrite, summarize", tools: "Polls, quizzes, GitHub, Notion, MCP", open: "yes", turtle: true },
  { name: "HeliBoard", ai: "None", tools: "None", open: "yes" },
  { name: "FUTO Keyboard", ai: "Prediction + voice", tools: "None", open: "partial" },
  { name: "CleverType", ai: "Writing, grammar", tools: "None", open: "no" },
  { name: "SwiftKey", ai: "Copilot chat", tools: "None", open: "no" },
  { name: "Gboard", ai: "Proofread, replies", tools: "None", open: "no" },
];

function OpenCell({ open }: { open: "yes" | "no" | "partial" }) {
  const map = {
    yes: { label: "Open source", cls: "text-iris" },
    partial: { label: "Source-available", cls: "text-violet" },
    no: { label: "Closed", cls: "text-slate/70" },
  } as const;
  return <span className={`font-medium ${map[open].cls}`}>{map[open].label}</span>;
}

export default function OpenSourceAIKeyboard() {
  return (
    <div className="min-h-screen w-full overflow-x-clip text-navy">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(jsonLd()).replace(/</g, "\\u003c"),
        }}
      />

      {/* nav — current sea-glass pill */}
      <header className="fixed inset-x-0 top-0 z-50 px-4 pt-4 sm:px-6">
        <div className="sea-glass mx-auto flex max-w-5xl items-center justify-between rounded-full py-2.5 pl-5 pr-5 sm:pl-6">
          <Link href="/" className="flex items-center gap-2.5 text-navy">
            <TurtleMark className="w-8 text-navy" />
            <span className="text-[17px] font-semibold tracking-tight">Turtle Keyboard</span>
            <span className="hidden rounded-full border border-iris/40 px-2 py-0.5 font-mono text-[10px] text-iris sm:inline">
              open source
            </span>
          </Link>
          <nav className="hidden items-center gap-8 text-[14px] font-medium text-slate md:flex">
            <Link href="/download" className="transition-colors duration-300 hover:text-navy">Download</Link>
            <Link href="/blog" className="transition-colors duration-300 hover:text-navy">Blog</Link>
            <Link href="/#current" className="transition-colors duration-300 hover:text-navy">How it works</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              GitHub
            </a>
          </nav>
          <Link href="/#waitlist" className="btn-grad rounded-full px-4 py-2 text-[13px] font-semibold">
            Grab My Spot →
          </Link>
        </div>
      </header>

      <main>
        {/* hero — answer-first, utility-led */}
        <section className="relative overflow-hidden pt-36 sm:pt-44">
          <div className="caustics" aria-hidden />
          <div className="relative mx-auto max-w-3xl px-6 text-center">
            <Reveal>
              <div className="inline-flex items-center gap-2 rounded-full bg-iris/10 px-3.5 py-1.5 font-mono text-[11px] uppercase tracking-[0.18em] text-iris">
                MIT-licensed · iOS + Android
              </div>
            </Reveal>
            <Reveal delay={100}>
              <h1 className="mt-6 font-display text-[clamp(2.5rem,5.6vw,4.1rem)] font-semibold leading-[1.05] tracking-[-0.02em]">
                The open-source <span className="slash-glow">AI keyboard</span>
              </h1>
            </Reveal>
            <Reveal delay={200}>
              <p className="mx-auto mt-6 max-w-2xl text-lg leading-relaxed text-slate">
                Turtle is an <strong className="font-semibold text-navy">open-source AI
                keyboard</strong> for iPhone and Android that turns any text field into a
                command line. Type a slash to drop <strong className="font-semibold text-navy">live
                polls, quizzes, AI, and your favorite tools</strong> — GitHub, Notion, Linear —
                right into the conversation, without leaving the app. And every line of the
                keyboard is public and MIT-licensed.
              </p>
            </Reveal>
            <Reveal delay={300}>
              <div className="mt-9 flex flex-wrap justify-center gap-3">
                <a
                  href={GITHUB_URL}
                  target="_blank"
                  rel="noreferrer"
                  className="rounded-full border border-navy/20 px-7 py-3.5 text-base font-medium text-navy transition-colors duration-300 hover:border-iris hover:text-iris"
                >
                  View source on GitHub ↗
                </a>
              </div>
            </Reveal>

            <Reveal delay={380}>
              <StoreBadges className="mt-6 justify-center" />
              <p className="mt-3 font-mono text-xs text-slate/80">
                free on Google Play · iOS coming soon
              </p>
            </Reveal>
          </div>
        </section>

        {/* the market gap — open AND useful */}
        <section className="mx-auto max-w-4xl px-6 py-20 sm:py-24">
          <Reveal>
            <h2 className="font-display text-[clamp(1.8rem,3.8vw,2.6rem)] font-semibold leading-tight tracking-[-0.01em]">
              Every keyboard makes you choose. Turtle doesn&rsquo;t.
            </h2>
            <p className="mt-4 max-w-2xl text-[16.5px] leading-[1.8] text-slate">
              The market splits in two: open-source keyboards that respect you but do
              nothing clever, and AI keyboards that are useful but closed and boxed into
              grammar fixes. Turtle is the bridge — genuinely open, and packed with{" "}
              <em>commands that do things</em> right inside your chats.
            </p>
          </Reveal>

          <Reveal delay={120}>
            <div className="mt-9 overflow-x-auto rounded-2xl border border-navy/10 bg-white-warm">
              <table className="w-full border-collapse text-left text-[14.5px]">
                <thead>
                  <tr className="bg-mist/70">
                    {["Keyboard", "AI writing", "Slash-command tools", "Source"].map((h) => (
                      <th
                        key={h}
                        className="whitespace-nowrap border-b border-navy/10 px-4 py-3 font-mono text-[11px] uppercase tracking-[0.14em] text-navy/70"
                      >
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {MATRIX.map((row, i) => (
                    <tr
                      key={row.name}
                      className={row.turtle ? "bg-iris/[0.06]" : i % 2 ? "bg-sand/40" : ""}
                    >
                      <td className="border-b border-navy/5 px-4 py-3 align-top font-semibold text-navy">
                        {row.name}
                        {row.turtle && (
                          <span className="ml-2 rounded-full bg-iris/15 px-2 py-0.5 font-mono text-[9px] uppercase tracking-wider text-iris">
                            ours
                          </span>
                        )}
                      </td>
                      <td className="border-b border-navy/5 px-4 py-3 align-top text-navy/85">{row.ai}</td>
                      <td className="border-b border-navy/5 px-4 py-3 align-top text-navy/85">{row.tools}</td>
                      <td className="border-b border-navy/5 px-4 py-3 align-top">
                        <OpenCell open={row.open} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Reveal>
          <Reveal delay={180}>
            <p className="mt-4 font-mono text-xs text-slate/80">
              A fuller breakdown lives in{" "}
              <Link href="/blog/clevertype-alternatives" className="text-iris transition-colors duration-300 hover:text-iris-deep">
                open-source &amp; private AI keyboards compared
              </Link>
              .
            </p>
          </Reveal>
        </section>

        {/* why it's different */}
        <section className="mx-auto max-w-3xl px-6 pb-20 sm:pb-24">
          <Reveal>
            <h2 className="font-display text-[clamp(1.8rem,3.8vw,2.6rem)] font-semibold leading-tight tracking-[-0.01em]">
              Open, useful, and yours to shape
            </h2>
          </Reveal>
          <div className="mt-8 grid gap-5 sm:grid-cols-3">
            {[
              {
                h: "Works in every app",
                p: "The keyboard goes where you go, so one slash command brings your tools into iMessage, WhatsApp, Gmail, and Slack — no app-switching, no bots to install.",
                href: "/blog/slack-slash-commands-everywhere",
                cta: "a workday without the shuffle →",
              },
              {
                h: "Only acts on your slash",
                p: "Turtle never captures ordinary typing — only the text after a slash command is ever processed. Because the code is open, that's a claim you can check, not just trust.",
                href: "/blog/what-is-an-ai-keyboard",
                cta: "what to look for →",
              },
              {
                h: "Yours to extend",
                p: "Commands are MCP plugins. GitHub, Notion, and Linear are built in — and about thirty lines of code adds your own tool to every text field on your phone.",
                href: "/blog/bringing-mcp-to-mobile-keyboards",
                cta: "the plugin model →",
              },
            ].map((c) => (
              <Reveal key={c.h} className="h-full">
                <div className="sea-glass flex h-full flex-col rounded-3xl p-6">
                  <h3 className="font-display text-lg font-semibold tracking-tight">{c.h}</h3>
                  <p className="mt-2.5 flex-1 text-[14.5px] leading-relaxed text-slate">{c.p}</p>
                  <Link
                    href={c.href}
                    className="mt-4 font-mono text-[13px] font-semibold text-iris transition-colors duration-300 hover:text-iris-deep"
                  >
                    {c.cta}
                  </Link>
                </div>
              </Reveal>
            ))}
          </div>
        </section>

        {/* what it does — the utility hero */}
        <section className="mx-auto max-w-3xl px-6 pb-20 sm:pb-24">
          <Reveal>
            <h2 className="font-display text-[clamp(1.8rem,3.8vw,2.6rem)] font-semibold leading-tight tracking-[-0.01em]">
              What you can do with it
            </h2>
            <p className="mt-4 text-[16.5px] leading-[1.8] text-slate">
              Turtle uses <Link href="/blog/what-is-an-ai-keyboard" className="font-medium text-iris underline decoration-iris/30 underline-offset-4 transition-colors duration-300 hover:text-iris-deep">slash commands</Link> instead
              of an always-on suggestion strip — nothing fires until you type{" "}
              <span className="slash-glow font-mono font-semibold">/</span>:
            </p>
          </Reveal>
          <Reveal delay={120}>
            <ul className="mt-6 space-y-3.5">
              {[
                ["/poll & /quiz", "Drop a live, anonymous poll or a prompted quiz into any chat — even one with a mix of iPhone and Android."],
                ["/cap & /sticker", "Generate an image, a sticker, or a meme from a prompt and send it in line."],
                ["/summarize", "Condense a long email or thread in place, without switching to another app."],
                ["/github, /notion, /linear", "Connected apps, built into the keyboard — check a PR or pull a doc without leaving the conversation."],
                ["/your-command", "Anything with an API. Write an MCP plugin and it lives in every text field."],
              ].map(([cmd, desc]) => (
                <li key={cmd} className="flex gap-3.5 text-[16px] leading-[1.7] text-navy/85">
                  <span className="mt-1 shrink-0 rounded-md bg-navy px-2 py-0.5 font-mono text-[12px] font-semibold text-turq-bright">
                    {cmd}
                  </span>
                  <span>{desc}</span>
                </li>
              ))}
            </ul>
          </Reveal>
          <Reveal delay={180}>
            <p className="mt-6 text-[15px] leading-relaxed text-slate">
              No keyboard yet? You can{" "}
              <Link href="/poll-maker" className="font-medium text-iris underline decoration-iris/30 underline-offset-4 transition-colors duration-300 hover:text-iris-deep">
                create a poll link in your browser
              </Link>{" "}
              right now — same engine, no install.
            </p>
          </Reveal>
        </section>

        {/* FAQ */}
        <section className="mx-auto max-w-3xl px-6 pb-20 sm:pb-24">
          <Reveal>
            <h2 className="font-display text-[clamp(1.8rem,3.8vw,2.6rem)] font-semibold leading-tight tracking-[-0.01em]">
              Frequently asked questions
            </h2>
          </Reveal>
          <div className="mt-7 space-y-5">
            {FAQS.map((f) => (
              <Reveal key={f.q}>
                <div className="sea-glass rounded-2xl px-5 py-4">
                  <h3 className="font-display text-[17px] font-semibold leading-snug tracking-tight">{f.q}</h3>
                  <p className="mt-2 text-[15px] leading-[1.75] text-navy/85">{f.a}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </section>

        {/* CTA */}
        <section className="mx-auto max-w-3xl px-6 pb-24 sm:pb-28">
          <Reveal>
            <div className="sea-glass grain rounded-[28px] px-7 py-11 text-center sm:px-12">
              <TurtleMark className="mx-auto w-11 text-iris" />
              <h2 className="mt-6 font-display text-[clamp(1.7rem,3.6vw,2.4rem)] font-semibold leading-tight tracking-[-0.01em]">
                An AI keyboard you can actually read.
              </h2>
              <p className="mx-auto mt-4 max-w-md text-[15.5px] leading-relaxed text-slate">
                Turtle is on Google Play now, with iOS coming soon. Download it,
                star it, or fork it.
              </p>
              <div className="mt-8 flex justify-center">
                <StoreBadges />
              </div>
              <div className="mt-5 flex flex-wrap justify-center gap-3">
                <Link href="/#waitlist" className="btn-grad rounded-full px-6 py-3.5 text-sm font-semibold">
                  Grab my spot →
                </Link>
                <a
                  href={GITHUB_URL}
                  target="_blank"
                  rel="noreferrer"
                  className="rounded-full border border-navy/20 px-6 py-3.5 text-sm font-medium text-navy transition-colors duration-300 hover:border-iris hover:text-iris"
                >
                  Star on GitHub ↗
                </a>
              </div>
            </div>
          </Reveal>
        </section>
      </main>

      <footer className="relative border-t border-navy/10">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-6 px-6 py-12 text-center">
          <Link href="/" aria-label="Turtle Keyboard — home">
            <TurtleMark className="w-10 text-iris" />
          </Link>
          <nav className="flex flex-wrap items-center justify-center gap-x-7 gap-y-3 font-mono text-sm text-slate">
            <Link href="/" className="transition-colors duration-300 hover:text-navy">home</Link>
            <Link href="/blog" className="transition-colors duration-300 hover:text-navy">blog</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              github ↗
            </a>
            <Link href="/#waitlist" className="transition-colors duration-300 hover:text-navy">waitlist</Link>
          </nav>
          <p className="font-mono text-xs text-slate/75">🐢 Turtle Keyboard © 2026 · MIT</p>
        </div>
      </footer>
    </div>
  );
}
