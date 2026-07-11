import type { Metadata } from "next";
import Link from "next/link";
import Reveal from "../components/Reveal";
import TurtleMark from "../components/TurtleMark";
import StoreBadges from "../components/StoreBadges";
import CommandKeyboard from "../components/CommandKeyboard";
import { SITE_URL } from "@/lib/blog";
import { GITHUB_URL, PLAY_STORE_URL, APP_STORE_URL, appJsonLd } from "@/lib/store";

export const metadata: Metadata = {
  title: "Download Turtle Keyboard — Free, Open-Source AI Keyboard",
  description:
    "Get Turtle, the free open-source AI keyboard. On Google Play now (Android); iOS coming soon. Type a slash for polls, quizzes, AI, and connected tools.",
  alternates: { canonical: "/download" },
  keywords: [
    "download Turtle Keyboard",
    "Turtle Keyboard app",
    "AI keyboard app Android",
    "AI keyboard app iPhone",
    "open source keyboard download",
    "free AI keyboard",
  ],
  openGraph: {
    type: "website",
    url: "/download",
    title: "Download Turtle Keyboard",
    description:
      "The free, open-source AI keyboard. On Google Play now; iOS coming soon. Slash commands bring polls, quizzes, AI, and your tools into any chat.",
  },
  twitter: {
    card: "summary_large_image",
    title: "Download Turtle Keyboard",
    description:
      "Free, open-source AI keyboard. Google Play now, iOS soon. Type / for polls, quizzes, AI, and connected tools.",
  },
};

const FAQS = [
  {
    q: "Is Turtle Keyboard free?",
    a: "Yes. Turtle is free to download and use, and the keyboard is open source under the MIT license. A paid tier may later fund the most expensive AI features, but your data is never the product.",
  },
  {
    q: "Is Turtle Keyboard on the App Store?",
    a: "The iOS App Store listing is coming soon — it's in review. The Android app is available on Google Play today. Join the waitlist to be told the moment the iPhone version is live.",
  },
  {
    q: "What platforms does Turtle support?",
    a: "Android (on Google Play now) and iOS (coming soon). Both are native keyboards — a UIInputViewController extension on iOS and an InputMethodService on Android — not a cross-platform wrapper.",
  },
  {
    q: "How do I enable Turtle after installing it?",
    a: "On Android: open the app, then Settings → System → Languages & input → On-screen keyboard → Manage keyboards, toggle Turtle on, and switch to it with the keyboard icon. On iOS: Settings → General → Keyboard → Keyboards → Add New Keyboard → Turtle, then allow Full Access.",
  },
  {
    q: "Is it safe to install an AI keyboard?",
    a: "A keyboard sees everything you type, so the honest safeguard is verifiability. Turtle's code is public and MIT-licensed, and it only ever sends the text of an explicit slash command — never your ordinary typing. You can read exactly what it does on GitHub.",
  },
];

function jsonLd() {
  return [
    appJsonLd(`${SITE_URL}/download`),
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

type Step = { n: string; text: string };

const ANDROID_STEPS: Step[] = [
  { n: "01", text: "Install Turtle from Google Play." },
  { n: "02", text: "Open the app — it walks you through the rest." },
  { n: "03", text: "Settings → System → Languages & input → On-screen keyboard → Manage keyboards, and toggle Turtle on." },
  { n: "04", text: "Tap the keyboard icon (or globe) in any text field to switch to Turtle. Type / to begin." },
];

const IOS_STEPS: Step[] = [
  { n: "01", text: "Install Turtle from the App Store (coming soon)." },
  { n: "02", text: "Settings → General → Keyboard → Keyboards → Add New Keyboard → Turtle." },
  { n: "03", text: "Tap Turtle again and turn on Allow Full Access, so slash commands can reach the network." },
  { n: "04", text: "Long-press the globe in any text field to switch to Turtle. Type / to begin." },
];

function EnableColumn({
  platform,
  steps,
  soon = false,
}: {
  platform: string;
  steps: Step[];
  soon?: boolean;
}) {
  return (
    <div className="sea-glass rounded-3xl p-6 sm:p-7">
      <div className="flex items-center gap-2">
        <h3 className="font-display text-xl font-semibold tracking-tight">{platform}</h3>
        {soon && (
          <span className="rounded-full border border-navy/15 px-2 py-0.5 font-mono text-[9px] uppercase tracking-wider text-slate">
            coming soon
          </span>
        )}
      </div>
      <ol className="mt-5 space-y-4">
        {steps.map((s) => (
          <li key={s.n} className="flex gap-3.5">
            <span className="font-mono text-[11px] font-semibold text-iris">{s.n}</span>
            <span className="text-[15px] leading-relaxed text-navy/85">{s.text}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

const FEATURES: [string, string][] = [
  ["/poll & /quiz", "Live polls and prompted quizzes in any chat — even a mixed iPhone + Android group."],
  ["/cap & /sticker", "Turn a prompt into an image, sticker, or meme, sent in line."],
  ["/summarize & /fix", "Condense a thread or clean up a draft, right where you're typing."],
  ["/github, /notion, /linear", "Connected apps, built into the keyboard. Add your own via MCP."],
];

export default function DownloadPage() {
  return (
    <div className="min-h-screen w-full overflow-x-clip text-navy">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(jsonLd()).replace(/</g, "\\u003c"),
        }}
      />

      {/* nav */}
      <header className="fixed inset-x-0 top-0 z-50 px-4 pt-4 sm:px-6">
        <div className="sea-glass mx-auto flex max-w-5xl items-center justify-between rounded-full py-2.5 pl-5 pr-5 sm:pl-6">
          <Link href="/" className="flex items-center gap-2.5 text-navy">
            <TurtleMark className="w-8 text-navy" />
            <span className="text-[17px] font-semibold tracking-tight">Turtle Keyboard</span>
            <span className="hidden rounded-full border border-iris/40 px-2 py-0.5 font-mono text-[10px] text-iris sm:inline">
              download
            </span>
          </Link>
          <nav className="hidden items-center gap-8 text-[14px] font-medium text-slate md:flex">
            <Link href="/blog" className="transition-colors duration-300 hover:text-navy">Blog</Link>
            <Link href="/open-source-ai-keyboard" className="transition-colors duration-300 hover:text-navy">About</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              GitHub
            </a>
          </nav>
          <a
            href={PLAY_STORE_URL ?? "/#waitlist"}
            target={PLAY_STORE_URL ? "_blank" : undefined}
            rel={PLAY_STORE_URL ? "noreferrer" : undefined}
            className="btn-grad rounded-full px-4 py-2 text-[13px] font-semibold"
          >
            Get it free →
          </a>
        </div>
      </header>

      <main>
        {/* hero — answer-first, badges primary */}
        <section className="relative overflow-hidden pt-36 sm:pt-44">
          <div className="caustics" aria-hidden />
          <div className="relative mx-auto max-w-3xl px-6 text-center">
            <Reveal>
              <div className="inline-flex items-center gap-2 rounded-full bg-iris/10 px-3.5 py-1.5 font-mono text-[11px] uppercase tracking-[0.18em] text-iris">
                free · open source · MIT
              </div>
            </Reveal>
            <Reveal delay={100}>
              <h1 className="mt-6 font-display text-[clamp(2.5rem,5.6vw,4.1rem)] font-semibold leading-[1.05] tracking-[-0.02em]">
                Download <span className="slash-glow">Turtle</span>
              </h1>
            </Reveal>
            <Reveal delay={200}>
              <p className="mx-auto mt-6 max-w-xl text-lg leading-relaxed text-slate">
                Turtle is a free, open-source AI keyboard. It&rsquo;s on{" "}
                <strong className="font-semibold text-navy">Google Play now</strong> for
                Android, with <strong className="font-semibold text-navy">iOS coming
                soon</strong>. Type a slash in any app for polls, quizzes, AI, and your
                favorite tools.
              </p>
            </Reveal>
            <Reveal delay={300}>
              <StoreBadges className="mt-9 justify-center" />
            </Reveal>
            <Reveal delay={380}>
              <p className="mt-4 font-mono text-xs text-slate/80">
                {APP_STORE_URL
                  ? "free on the App Store and Google Play"
                  : "free on Google Play · join the waitlist for iOS"}
              </p>
            </Reveal>
          </div>
        </section>

        {/* what you get — shown as the slash palette on a phone keyboard */}
        <section className="mx-auto max-w-5xl px-6 py-18 sm:py-24">
          <div className="grid items-center gap-12 lg:grid-cols-2">
            <div>
              <Reveal>
                <h2 className="font-display text-[clamp(1.7rem,3.6vw,2.4rem)] font-semibold leading-tight tracking-[-0.01em]">
                  What you get
                </h2>
                <p className="mt-4 max-w-md text-[16.5px] leading-[1.8] text-slate">
                  Type a slash in any text field and Turtle&rsquo;s command
                  palette opens right above your keyboard. Pick one, and the
                  result drops into the chat.
                </p>
              </Reveal>
              <Reveal delay={120}>
                <ul className="mt-6 space-y-3">
                  {FEATURES.map(([cmd, desc]) => (
                    <li key={cmd} className="flex gap-3 text-[15px] leading-[1.6] text-navy/85">
                      <span className="mt-0.5 shrink-0 rounded-md bg-navy px-1.5 py-0.5 font-mono text-[11px] font-semibold text-turq-bright">
                        {cmd}
                      </span>
                      <span>{desc}</span>
                    </li>
                  ))}
                </ul>
              </Reveal>
              <Reveal delay={180}>
                <p className="mt-6 text-[15px] leading-relaxed text-slate">
                  More on the thinking behind it in{" "}
                  <Link href="/open-source-ai-keyboard" className="font-medium text-iris underline decoration-iris/30 underline-offset-4 transition-colors duration-300 hover:text-iris-deep">
                    what makes Turtle an open-source AI keyboard
                  </Link>
                  .
                </p>
              </Reveal>
            </div>
            <Reveal delay={140}>
              <CommandKeyboard />
            </Reveal>
          </div>
        </section>

        {/* how to enable */}
        <section className="mx-auto max-w-4xl px-6 pb-18 sm:pb-24">
          <Reveal>
            <h2 className="font-display text-[clamp(1.7rem,3.6vw,2.4rem)] font-semibold leading-tight tracking-[-0.01em]">
              Setting it up takes a minute
            </h2>
            <p className="mt-4 max-w-2xl text-[16px] leading-relaxed text-slate">
              A phone won&rsquo;t switch to a new keyboard until you enable it — here&rsquo;s
              the one-time setup on each platform.
            </p>
          </Reveal>
          <div className="mt-8 grid gap-5 sm:grid-cols-2">
            <Reveal className="h-full">
              <EnableColumn platform="Android" steps={ANDROID_STEPS} />
            </Reveal>
            <Reveal delay={120} className="h-full">
              <EnableColumn platform="iPhone & iPad" steps={IOS_STEPS} soon={!APP_STORE_URL} />
            </Reveal>
          </div>
        </section>

        {/* FAQ */}
        <section className="mx-auto max-w-3xl px-6 pb-18 sm:pb-24">
          <Reveal>
            <h2 className="font-display text-[clamp(1.7rem,3.6vw,2.4rem)] font-semibold leading-tight tracking-[-0.01em]">
              Download &amp; install FAQ
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
              <h2 className="mt-6 font-display text-[clamp(1.6rem,3.4vw,2.2rem)] font-semibold leading-tight tracking-[-0.01em]">
                Type slash. Say the thing. Done.
              </h2>
              <p className="mx-auto mt-4 max-w-md text-[15.5px] leading-relaxed text-slate">
                Free, open source, and on Google Play now.
              </p>
              <div className="mt-8 flex justify-center">
                <StoreBadges />
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
            <Link href="/open-source-ai-keyboard" className="transition-colors duration-300 hover:text-navy">about</Link>
            <Link href="/blog" className="transition-colors duration-300 hover:text-navy">blog</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              github ↗
            </a>
          </nav>
          <p className="font-mono text-xs text-slate/75">🐢 Turtle Keyboard © 2026 · MIT</p>
        </div>
      </footer>
    </div>
  );
}
