import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import Link from 'next/link';

/**
 * Web fallback for poll links. Tapped by anyone who doesn't have Turtle installed —
 * shows the question + current vote counts read-only, plus a Play Store CTA so the
 * visitor can install Turtle and vote in-app on a re-tap.
 *
 * <p>For Turtle users on Android the App Link intent-filter intercepts before this page
 * ever renders; this Next.js route only fires when verification fails (no app installed
 * yet, web browser, iOS until Universal Links land).
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
  if (!poll) return { title: 'Poll · Turtle' };
  return {
    title: `${poll.question} · Turtle`,
    description: `Vote on this poll in Turtle: ${poll.options.map(o => o.label).join(' · ')}`,
    openGraph: {
      title: poll.question,
      description: `Vote in this poll on Turtle. ${poll.options.length} options.`,
      type: 'website',
      siteName: 'Turtle',
    },
  };
}

const ACCENTS = ['bg-lime', 'bg-pink', 'bg-blue', 'bg-orange'] as const;

export default async function PollPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const poll = await getPoll(id);
  if (!poll) notFound();

  const total = poll.options.reduce((sum, o) => sum + o.votes, 0);

  return (
    <div className="min-h-screen bg-cream grain">
      <header className="border-b-2 border-ink bg-cream px-6 py-4">
        <Link href="/" className="text-xl font-black text-ink">
          🐢 Turtle
        </Link>
      </header>

      <main className="mx-auto max-w-xl px-6 py-10">
        <p className="text-xs font-bold tracking-widest text-[var(--ink)] opacity-60 uppercase">
          Poll
        </p>
        <h1 className="mt-2 text-4xl font-black text-ink leading-tight">
          {poll.question}
        </h1>
        <p className="mt-2 text-sm text-[var(--ink)] opacity-60">
          {total} vote{total === 1 ? '' : 's'}
        </p>

        <div className="mt-8 space-y-3">
          {poll.options.map((opt, i) => {
            const pct = total > 0 ? Math.round((opt.votes / total) * 100) : 0;
            const accent = ACCENTS[i % ACCENTS.length];
            return (
              <div
                key={i}
                className="relative border-2 border-ink bg-white p-4 shadow-[4px_4px_0_0_var(--ink)]"
              >
                <div className="flex items-center justify-between gap-3">
                  <span className="font-bold text-ink text-lg">{opt.label}</span>
                  <span
                    className={`rounded-full ${accent} border border-ink px-3 py-1 text-xs font-bold text-white`}
                  >
                    {pct}%
                  </span>
                </div>
                {/* Width-percentage bar under the row, behind the content. */}
                <div className="mt-3 h-1 w-full bg-[var(--ink)] opacity-10" />
                <div
                  className={`-mt-1 h-1 ${accent}`}
                  style={{ width: `${pct}%` }}
                />
              </div>
            );
          })}
        </div>

        <section className="mt-12 border-2 border-ink bg-white p-6 shadow-[4px_4px_0_0_var(--ink)]">
          <h2 className="text-xl font-black text-ink">Vote with Turtle</h2>
          <p className="mt-2 text-sm text-[var(--ink)] opacity-70">
            Install the Turtle keyboard to vote, create your own polls, and play games
            with friends — all from inside any chat.
          </p>
          <a
            href={PLAY_STORE_URL}
            className="mt-6 inline-block border-2 border-ink bg-lime px-6 py-3 font-black text-white shadow-[4px_4px_0_0_var(--ink)] hover:translate-x-[2px] hover:translate-y-[2px] hover:shadow-[2px_2px_0_0_var(--ink)] transition-transform"
          >
            Get Turtle on Play Store →
          </a>
        </section>
      </main>
    </div>
  );
}
