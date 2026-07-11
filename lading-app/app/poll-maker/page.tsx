import type { Metadata } from "next";
import Link from "next/link";
import Reveal from "../components/Reveal";
import TurtleMark from "../components/TurtleMark";
import { SITE_URL } from "@/lib/blog";
import PollMakerForm from "./PollMakerForm";

export const metadata: Metadata = {
  title: "Free Poll Link Generator — No Signup | Turtle Keyboard",
  description:
    "Type a question, get a shareable poll link in seconds. No signup for creator or voters, anonymous votes, live results — works in any chat app.",
  alternates: { canonical: "/poll-maker" },
  openGraph: {
    type: "website",
    url: "/poll-maker",
    title: "Create a poll link in seconds — free, no signup",
    description:
      "A poll link generator with no account, anonymous voting, and live results. Paste it into WhatsApp, iMessage, Slack — anywhere.",
  },
  twitter: {
    card: "summary_large_image",
    title: "Create a poll link in seconds — free, no signup",
    description:
      "No account, anonymous voting, live results. Paste the link into any chat.",
  },
};

const FAQS = [
  {
    q: "Do I need an account to create a poll?",
    a: "No — and neither do your voters. Type a question, get a link. Nobody signs up on either side, which is the whole point.",
  },
  {
    q: "Are the votes anonymous?",
    a: "Yes. Votes are counted without names attached. The group sees what won, not who tipped it.",
  },
  {
    q: "How long does my poll stay live?",
    a: "About 45 minutes — Turtle polls are built for right-now decisions (where to eat, which movie, what time), not week-long surveys. Results update live for everyone while it runs.",
  },
  {
    q: "Where can I share the poll link?",
    a: "Anywhere a link works: WhatsApp, iMessage, Slack, Discord, SMS, email, Instagram DMs. Voters open it in their browser on any device — iPhone, Android, or a laptop.",
  },
  {
    q: "Is there a way to create polls without leaving my chat app?",
    a: "That's the actual product: the Turtle keyboard lets you type /poll directly in any conversation and drops the link in place. This page is the same engine, in your browser.",
  },
];

function jsonLd() {
  return [
    {
      "@context": "https://schema.org",
      "@type": "WebApplication",
      name: "Turtle Poll Link Generator",
      url: `${SITE_URL}/poll-maker`,
      applicationCategory: "UtilitiesApplication",
      operatingSystem: "Any (web)",
      offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
      description:
        "Free poll link generator — no signup, anonymous voting, live results, shareable in any chat app.",
    },
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

const GITHUB_URL = "https://github.com/princeku07/turtle-keyboard";

const STEPS = [
  {
    n: "01",
    title: "Type the question",
    body: "Add two to six options. Twenty seconds, tops.",
  },
  {
    n: "02",
    title: "Get your link",
    body: "One tap mints a live poll page — no account, no dashboard, no email.",
  },
  {
    n: "03",
    title: "Paste it anywhere",
    body: "WhatsApp, iMessage, Slack, SMS. Voters tap, vote anonymously, and watch results live.",
  },
];

export default function PollMakerPage() {
  return (
    <div className="min-h-screen w-full overflow-x-clip text-navy">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(jsonLd()).replace(/</g, "\\u003c"),
        }}
      />

      {/* nav — same sea-glass pill as the rest of the site */}
      <header className="fixed inset-x-0 top-0 z-50 px-4 pt-4 sm:px-6">
        <div className="sea-glass mx-auto flex max-w-6xl items-center justify-between rounded-full py-2.5 pl-5 pr-2.5">
          <Link href="/" className="flex items-center gap-2.5 text-navy">
            <TurtleMark className="w-7 text-turq-deep" />
            <span className="text-[17px] font-semibold tracking-tight">turtle</span>
            <span className="hidden rounded-full border border-turq/40 px-2 py-0.5 font-mono text-[10px] text-turq-deep sm:inline">
              poll maker
            </span>
          </Link>
          <nav className="hidden items-center gap-7 font-mono text-[13px] text-slate md:flex">
            <Link href="/download" className="transition-colors duration-300 hover:text-navy">download</Link>
            <Link href="/blog" className="transition-colors duration-300 hover:text-navy">blog</Link>
            <Link href="/#current" className="transition-colors duration-300 hover:text-navy">use cases</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              github ↗
            </a>
          </nav>
          <Link
            href="/#waitlist"
            className="rounded-full bg-turq px-4 py-2.5 font-mono text-[13px] font-semibold text-white shadow-[0_8px_24px_-8px_rgba(13,179,165,0.7)] transition-colors duration-300 hover:bg-turq-deep"
          >
            get the keyboard →
          </Link>
        </div>
      </header>

      <main>
        {/* hero + the tool */}
        <section className="relative overflow-hidden pt-36 sm:pt-44">
          <div className="caustics" aria-hidden />
          <div className="relative mx-auto max-w-3xl px-6 pb-16 text-center">
            <Reveal>
              <div className="flex items-center justify-center gap-3 font-mono text-[11px] uppercase tracking-[0.28em] text-slate">
                <span className="h-px w-8 bg-turq/60" />
                free poll link generator
                <span className="h-px w-8 bg-turq/60" />
              </div>
            </Reveal>
            <Reveal delay={100}>
              <h1 className="mt-6 text-[clamp(2.4rem,5.4vw,4rem)] font-semibold leading-[1.04] tracking-[-0.045em]">
                Create a poll link
                <br />
                in <span className="slash-glow">seconds</span>.
              </h1>
            </Reveal>
            <Reveal delay={200}>
              <p className="mx-auto mt-5 max-w-lg text-lg leading-relaxed text-slate">
                No signup — for you <em>or</em> your voters. Anonymous votes,
                live results, and a link that works in every chat app.
              </p>
            </Reveal>
            <Reveal delay={300}>
              <div className="mx-auto mt-10 max-w-xl text-left">
                <PollMakerForm />
              </div>
            </Reveal>
          </div>
        </section>

        {/* how it works */}
        <section className="mx-auto max-w-5xl px-6 py-16 sm:py-20">
          <Reveal>
            <div className="grid gap-6 md:grid-cols-3">
              {STEPS.map((s) => (
                <div key={s.n} className="sea-glass rounded-3xl p-6">
                  <div className="font-mono text-[11px] uppercase tracking-[0.28em] text-turq-deep">
                    {s.n}
                  </div>
                  <h2 className="mt-3 text-lg font-semibold tracking-tight">{s.title}</h2>
                  <p className="mt-2 text-sm leading-relaxed text-slate">{s.body}</p>
                </div>
              ))}
            </div>
          </Reveal>
        </section>

        {/* why a link */}
        <section className="mx-auto max-w-3xl px-6 pb-16 sm:pb-20">
          <Reveal>
            <h2 className="text-[clamp(1.6rem,3.4vw,2.2rem)] font-semibold leading-tight tracking-[-0.03em]">
              Why a link beats an in-app poll
            </h2>
            <p className="mt-4 text-[16px] leading-[1.8] text-navy/85">
              Native polls have compatibility walls: iMessage polls need{" "}
              <Link href="/blog/imessage-polls-not-working" className="font-medium text-turq-deep underline decoration-turq/40 underline-offset-4 transition-colors duration-300 hover:text-turq">
                everyone on iOS 26
              </Link>
              , WhatsApp polls{" "}
              <Link href="/blog/how-to-create-a-poll-in-whatsapp" className="font-medium text-turq-deep underline decoration-turq/40 underline-offset-4 transition-colors duration-300 hover:text-turq">
                stay locked in WhatsApp
              </Link>{" "}
              (and are never anonymous), and most poll sites make <em>you</em>{" "}
              create an account and <em>voters</em> wade through ads. A link has
              none of that: it opens on{" "}
              <Link href="/blog/create-a-live-poll-in-any-chat-app" className="font-medium text-turq-deep underline decoration-turq/40 underline-offset-4 transition-colors duration-300 hover:text-turq">
                every phone in a mixed group
              </Link>
              , the votes stay anonymous, and the tally is one shared source of
              truth across every app you paste it into.
            </p>
          </Reveal>
        </section>

        {/* FAQ */}
        <section className="mx-auto max-w-3xl px-6 pb-16 sm:pb-20">
          <Reveal>
            <h2 className="text-[clamp(1.6rem,3.4vw,2.2rem)] font-semibold leading-tight tracking-[-0.03em]">
              Questions, answered
            </h2>
            <div className="mt-6 space-y-5">
              {FAQS.map((f) => (
                <div key={f.q} className="sea-glass rounded-2xl px-5 py-4">
                  <h3 className="text-[16px] font-semibold leading-snug tracking-tight">{f.q}</h3>
                  <p className="mt-2 text-[15px] leading-[1.75] text-navy/85">{f.a}</p>
                </div>
              ))}
            </div>
          </Reveal>
        </section>

        {/* keyboard CTA */}
        <section className="mx-auto max-w-3xl px-6 pb-24 sm:pb-28">
          <Reveal>
            <div className="sea-glass grain rounded-[28px] px-7 py-11 text-center sm:px-12">
              <TurtleMark className="mx-auto w-11 text-turq-deep" />
              <h2 className="mt-6 text-[clamp(1.6rem,3.4vw,2.2rem)] font-semibold leading-tight tracking-[-0.03em]">
                Next time, don&rsquo;t even open a browser.
              </h2>
              <p className="mx-auto mt-4 max-w-md text-[15px] leading-relaxed text-slate">
                The Turtle keyboard puts this exact poll engine behind{" "}
                <span className="slash-glow font-mono font-semibold">/poll</span>{" "}
                — type it in any chat and the link drops in place. Quizzes too.
              </p>
              <div className="mt-8">
                <Link
                  href="/#waitlist"
                  className="inline-block rounded-full bg-turq px-6 py-3.5 font-mono text-sm font-semibold text-white shadow-[0_10px_30px_-10px_rgba(13,179,165,0.65)] transition-colors duration-300 hover:bg-turq-deep"
                >
                  grab my spot →
                </Link>
              </div>
            </div>
          </Reveal>
        </section>
      </main>

      <footer className="grain relative border-t border-navy/10 bg-gradient-to-b from-sand to-sand-2">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-6 px-6 py-12 text-center">
          <Link href="/" aria-label="turtle — home">
            <TurtleMark className="w-10 text-turq-deep" />
          </Link>
          <nav className="flex flex-wrap items-center justify-center gap-x-7 gap-y-3 font-mono text-sm text-slate">
            <Link href="/" className="transition-colors duration-300 hover:text-navy">home</Link>
            <Link href="/blog" className="transition-colors duration-300 hover:text-navy">blog</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              github ↗
            </a>
          </nav>
          <p className="font-mono text-xs text-slate/75">🐢 turtle © 2026 · MIT</p>
        </div>
      </footer>
    </div>
  );
}
