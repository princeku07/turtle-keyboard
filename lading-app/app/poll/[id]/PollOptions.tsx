"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { onValue, ref, set } from "firebase/database";
import { auth, db, ensureAnonAuth } from "@/lib/firebase-client";

type Option = { label: string; votes: number };

/**
 * Live web view of a poll. Receives the server-rendered options + initial counts
 * as a snapshot, then mounts a Firebase Anonymous Auth + RTDB realtime listener
 * to keep counts in sync with the in-app sheet view in real time.
 *
 * <p>Voting writes {@code polls/<id>/voters/<anonUid>: optionIndex}. The anon
 * uid is sticky per browser (Firebase Auth persists to localStorage), so this
 * is "one vote per browser install" — same human can vote again from a
 * different browser / incognito. Acceptable for casual social polls.
 *
 * <p>Visual matches {@code opengraph-image.tsx} so the chat preview and the
 * real page read as the same surface: dark track + green fill, leader gets the
 * accent, others fade.
 */
export function PollOptions({
  pollId,
  initialOptions,
  expired,
}: {
  pollId: string;
  initialOptions: Option[];
  expired: boolean;
}) {
  const [options, setOptions] = useState<Option[]>(initialOptions);
  const [myVote, setMyVote] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const myUidRef = useRef<string | null>(null);

  useEffect(() => {
    if (expired) return;

    let cancelled = false;
    let unsubscribe: (() => void) | null = null;

    (async () => {
      try {
        const uid = await ensureAnonAuth();
        if (cancelled) return;
        myUidRef.current = uid;
      } catch (e) {
        // Anon auth failed — fall back to the SSR snapshot, no live updates.
        if (typeof console !== "undefined") {
          console.warn("anon auth failed", e);
        }
        return;
      }

      const pollRef = ref(db, `polls/${pollId}`);
      const off = onValue(pollRef, snap => {
        if (!snap.exists()) return;
        const data = snap.val() as {
          options?: unknown;
          voters?: Record<string, unknown>;
        };
        const labels = Array.isArray(data.options)
          ? data.options.filter((o): o is string => typeof o === "string")
          : [];
        if (labels.length === 0) return;

        const counts = new Array<number>(labels.length).fill(0);
        const uid = myUidRef.current ?? auth.currentUser?.uid ?? null;
        let mine: number | null = null;
        if (data.voters && typeof data.voters === "object") {
          for (const [voterUid, idx] of Object.entries(data.voters)) {
            if (typeof idx === "number" && idx >= 0 && idx < counts.length) {
              counts[idx]++;
              if (uid && voterUid === uid) mine = idx;
            }
          }
        }
        setOptions(labels.map((label, i) => ({ label, votes: counts[i] })));
        setMyVote(mine);
      });
      unsubscribe = () => off();
    })();

    return () => {
      cancelled = true;
      if (unsubscribe) unsubscribe();
    };
  }, [pollId, expired]);

  const total = options.reduce((sum, o) => sum + o.votes, 0);
  // Leader index — first option with the max votes. Used for the bright
  // accent bar vs the dimmer accent on losing options.
  let leaderIdx = -1;
  if (total > 0) {
    let leaderVotes = -1;
    for (let i = 0; i < options.length; i++) {
      if (options[i].votes > leaderVotes) {
        leaderVotes = options[i].votes;
        leaderIdx = i;
      }
    }
  }
  const locked = myVote !== null || expired;

  const vote = useCallback(
    async (index: number) => {
      if (locked || pending) return;
      setError(null);
      setPending(true);
      try {
        const uid = await ensureAnonAuth();
        myUidRef.current = uid;
        await set(ref(db, `polls/${pollId}/voters/${uid}`), index);
        // No manual setState — the realtime listener will fire with the new
        // vote and update both `options` and `myVote`.
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message.toLowerCase() : "";
        if (msg.includes("permission")) {
          setError("you've already voted on this poll.");
        } else {
          setError("couldn't record your vote. try again.");
        }
      } finally {
        setPending(false);
      }
    },
    [pollId, locked, pending],
  );

  return (
    <>
      <p className="mt-4 font-mono text-sm text-[#888888]">
        {total} {total === 1 ? "vote" : "votes"}
        {expired && " · ended"}
      </p>

      <div className="mt-10 space-y-3">
        {options.map((opt, i) => {
          const pct = total > 0 ? Math.round((opt.votes / total) * 100) : 0;
          const isLeader = i === leaderIdx;
          const isMine = myVote === i;
          const isPending = pending;
          return (
            <button
              key={i}
              type="button"
              onClick={() => vote(i)}
              disabled={locked || pending}
              aria-pressed={isMine}
              className={`group relative w-full overflow-hidden rounded-2xl border border-[#2E2E2E] bg-[#1E1E1E] px-5 py-4 text-left transition-transform ${
                locked
                  ? "cursor-default"
                  : "cursor-pointer hover:-translate-y-0.5 active:translate-y-0 hover:border-[#15803D]/40"
              } ${
                isMine
                  ? "ring-2 ring-[#15803D]"
                  : ""
              } ${
                isPending ? "opacity-80" : ""
              }`}
            >
              {/* Vote bar — track stays a subtle wash, fill is the keyboard
                  accent (full for leader, dimmed for others). */}
              <div
                aria-hidden
                className="absolute inset-y-0 left-0 transition-[width] duration-500 ease-out"
                style={{
                  width: `${pct}%`,
                  background: isLeader
                    ? "rgba(21, 128, 61, 0.32)"
                    : "rgba(21, 128, 61, 0.10)",
                }}
              />
              <div className="relative flex items-center justify-between gap-4">
                <span className="font-sans text-base sm:text-lg text-[#F5F5F5] flex items-center gap-3">
                  {opt.label}
                  {isMine && (
                    <span className="font-mono text-[10px] tracking-[0.18em] uppercase text-[#15803D]">
                      your vote
                    </span>
                  )}
                </span>
                <span
                  className={`font-mono text-sm font-semibold tabular-nums ${
                    isLeader ? "text-[#15803D]" : "text-[#888888]"
                  }`}
                >
                  {pct}%
                </span>
              </div>
            </button>
          );
        })}
      </div>

      {error && <p className="mt-4 font-mono text-xs text-[#F5F5F5]/80">{error}</p>}
      {!expired && (
        <p className="mt-6 font-mono text-xs text-[#888888]">
          one vote per browser. install the keyboard for in-chat polls.
        </p>
      )}
    </>
  );
}
