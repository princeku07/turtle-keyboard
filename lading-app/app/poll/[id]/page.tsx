import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { PollOptions } from './PollOptions';

/**
 * Web fallback for poll links. Tapped by anyone who doesn't have Turtle installed —
 * shows the question + current vote counts read-only, plus a Play Store CTA so the
 * visitor can install Turtle and vote in-app on a re-tap.
 *
 * <p>For Turtle users on Android the App Link intent-filter intercepts before this page
 * ever renders; this Next.js route only fires when verification fails (no app installed
 * yet, web browser, iOS until Universal Links land).
 *
 * <p>Styling mirrors the landing site exactly — same ocean/abyss gradient body, foam
 * text, cyan accent, glass cards, Geist Sans/Mono. No new visual language; this page
 * has to feel like the rest of turtle.
 */

const WORKER_URL =
  process.env.NEXT_PUBLIC_WORKER_URL || 'https://turtle-worker.trtlk.workers.dev';
const PLAY_STORE_URL =
  'https://play.google.com/store/apps/details?id=com.prince.turtlekeyboard';

type Option = { label: string; votes: number };
type Poll = {
  id: string;
  createdAt: number;
  question: string;
  options: Option[];
};

async function getPoll(id: string): Promise<Poll | null> {
  try {
    const res = await fetch(`${WORKER_URL}/poll/${id}`, { cache: 'no-store' });
    if (!res.ok) return null;
    return (await res.json()) as Poll;
  } catch {
    return null;
  }
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  const poll = await getPoll(id);
  if (!poll) return { title: 'poll · turtle' };

  const url = `/poll/${id}`;
  // Short option list for the description — long lists get truncated so the
  // preview line stays readable in chat clients. The OG image itself shows
  // the actual chips, so this is just a textual fallback.
  const optionPreview = poll.options
    .slice(0, 4)
    .map(o => o.label)
    .join(' · ');
  const description =
    poll.options.length > 4
      ? `${optionPreview} · +${poll.options.length - 4} more`
      : `${optionPreview} — vote in the turtle keyboard.`;

  // Explicit image entry — WhatsApp's crawler is strict and skips previews
  // when og:image:width / og:image:height / og:image:type aren't in the
  // initial HTML. Don't rely on Next's auto-emission from the file-convention
  // opengraph-image.tsx; declare them ourselves so the tags are guaranteed.
  // metadataBase on the root layout resolves the relative URL to absolute.
  const ogImage = {
    url: `/poll/${id}/opengraph-image`,
    width: 1200,
    height: 630,
    alt: poll.question,
    type: 'image/png',
  } as const;

  return {
    title: `${poll.question} · turtle`,
    description,
    openGraph: {
      title: poll.question,
      description,
      type: 'website',
      siteName: 'turtle',
      url,
      images: [ogImage],
    },
    twitter: {
      card: 'summary_large_image',
      title: poll.question,
      description,
      images: [ogImage.url],
    },
    alternates: { canonical: url },
  };
}

export default async function PollPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const poll = await getPoll(id);
  if (!poll) notFound();

  return (
    <main className="min-h-screen w-full text-foam overflow-x-clip">
      {/* Nav — matches landing exactly */}
      <header className="sticky top-0 z-40 backdrop-blur-md">
        <div className="mx-auto max-w-[1400px] px-4 sm:px-6 py-3 sm:py-4 flex items-center justify-between gap-3">
          <a
            href="/"
            className="flex items-center gap-2 font-sans font-semibold text-base sm:text-lg shrink-0 tracking-tight text-foam"
          >
            <span className="text-xl sm:text-2xl leading-none">🐢</span>
            turtle
          </a>
          <a
            href={PLAY_STORE_URL}
            target="_blank"
            rel="noreferrer"
            className="font-mono text-xs sm:text-sm font-semibold bg-foam text-ink px-3 sm:px-4 py-2 rounded-full hover:bg-cyan transition-colors whitespace-nowrap"
          >
            get app →
          </a>
        </div>
      </header>

      {/* Poll */}
      <section className="relative">
        <div className="mx-auto max-w-[640px] px-5 sm:px-6 pt-10 pb-20 sm:pt-16 sm:pb-28">
          <p className="font-mono text-xs tracking-[0.22em] text-foam-dim uppercase">
            poll
          </p>
          <h1 className="mt-3 font-sans font-semibold tracking-[-0.03em] leading-[1.05] text-[clamp(2rem,5.5vw,3.4rem)] text-foam">
            {poll.question}
          </h1>

          <PollOptions pollId={poll.id} initialOptions={poll.options} />

          {/* Install CTA */}
          <div className="mt-16 sm:mt-20 glass rounded-2xl p-6 sm:p-8">
            <h2 className="font-sans font-semibold tracking-[-0.02em] text-2xl sm:text-3xl text-foam">
              vote in the app
            </h2>
            <p className="mt-3 font-mono text-sm sm:text-[15px] text-foam-dim leading-relaxed">
              install the turtle keyboard. type{' '}
              <span className="text-cyan">/poll</span> in any chat to make your own,
              tap to vote on theirs — all without leaving the conversation.
            </p>
            <a
              href={PLAY_STORE_URL}
              target="_blank"
              rel="noreferrer"
              className="mt-7 inline-flex items-center gap-2 bg-cyan text-ink font-mono font-semibold px-5 py-3 rounded-full hover:bg-foam transition-colors"
            >
              get turtle <span aria-hidden>↗</span>
            </a>
          </div>
        </div>
      </section>

      {/* Footer — slim variant of the landing footer */}
      <footer className="border-t border-white/10">
        <div className="mx-auto max-w-[1400px] px-6 py-8 flex flex-col sm:flex-row items-center justify-between gap-4 font-mono text-xs text-foam/55">
          <div className="flex items-center gap-3">
            <span className="text-lg leading-none">🐢</span>
            <span className="font-semibold text-foam">turtle</span>
            <span>© 2026 · MIT</span>
          </div>
          <a href="/" className="hover:text-foam transition-colors">
            ← turtle home
          </a>
        </div>
      </footer>
    </main>
  );
}
