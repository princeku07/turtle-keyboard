/**
 * Server-side reader for the RTDB-backed polls collection. Used by the poll
 * page + OG image generator at request time. Anonymous read is allowed by the
 * RTDB rules ({@code "polls/$pollId/.read": true}), so no auth token needed.
 *
 * Configured via env var:
 *   NEXT_PUBLIC_FIREBASE_DATABASE_URL
 *
 * Client-side voting + live listener live in `lib/firebase-client.ts` (Web SDK
 * with anonymous auth).
 */

const DB_URL = process.env.NEXT_PUBLIC_FIREBASE_DATABASE_URL ?? "";

/** Match the Cloud Function sweep window. Polls render as "ended" past this
 *  even if the sweeper hasn't deleted them yet. */
const POLL_TTL_MS = 47 * 60 * 1000;

export type Poll = {
  id: string;
  question: string;
  options: { label: string; votes: number }[];
  createdAt: number;
  expiresAt: number;
  /** True if {@code expiresAt} has passed. UI should render "this poll has
   *  ended" instead of the live state. */
  expired: boolean;
};

type RawPoll = {
  question?: unknown;
  options?: unknown;
  voters?: unknown;
  createdAt?: unknown;
  expiresAt?: unknown;
};

const REVALIDATE_SECONDS = 15;

/**
 * Single GET to {@code <db>/polls/<id>.json}. Returns the entire poll subtree
 * (question, options, voters tree) in one shot — RTDB's denormalized format
 * means no second call for vote dedup. Counts derived from voters subtree.
 *
 * Returns null if the poll doesn't exist or env vars are missing. Returns
 * {expired:true} if the poll is past TTL but still present in the database
 * (sweeper hasn't run yet).
 */
export async function fetchPoll(id: string): Promise<Poll | null> {
  if (!DB_URL) {
    if (typeof console !== "undefined") {
      console.warn("fetchPoll: NEXT_PUBLIC_FIREBASE_DATABASE_URL not set");
    }
    return null;
  }

  let raw: RawPoll | null;
  try {
    const res = await fetch(`${DB_URL}/polls/${encodeURIComponent(id)}.json`, {
      next: { revalidate: REVALIDATE_SECONDS },
    });
    if (!res.ok) return null;
    raw = (await res.json()) as RawPoll | null;
  } catch {
    return null;
  }
  if (!raw || typeof raw !== "object") return null;

  const labels = Array.isArray(raw.options)
    ? raw.options.filter((o): o is string => typeof o === "string")
    : [];
  if (labels.length === 0) return null;

  const counts = new Array<number>(labels.length).fill(0);
  if (raw.voters && typeof raw.voters === "object") {
    for (const idx of Object.values(raw.voters as Record<string, unknown>)) {
      if (typeof idx === "number" && idx >= 0 && idx < counts.length) counts[idx]++;
    }
  }

  const createdAt = typeof raw.createdAt === "number" ? raw.createdAt : 0;
  const expiresAt =
    typeof raw.expiresAt === "number" ? raw.expiresAt : createdAt + POLL_TTL_MS;
  const expired = expiresAt > 0 && Date.now() >= expiresAt;

  return {
    id,
    question: typeof raw.question === "string" ? raw.question : "",
    options: labels.map((label, i) => ({ label, votes: counts[i] })),
    createdAt,
    expiresAt,
    expired,
  };
}
