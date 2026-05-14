"use client";

import { useCallback, useState, useSyncExternalStore } from "react";

const WORKER_URL =
  process.env.NEXT_PUBLIC_WORKER_URL || "https://turtle-worker.trtlk.workers.dev";

type Option = { label: string; votes: number };

function getOrCreateDeviceId(): string {
  let id = window.localStorage.getItem("turtle-device-id");
  if (!id) {
    id =
      typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID()
        : `web-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    window.localStorage.setItem("turtle-device-id", id);
  }
  return id;
}

const VOTE_EVENT = "turtle:poll-vote";

function subscribeVote(callback: () => void) {
  window.addEventListener("storage", callback);
  window.addEventListener(VOTE_EVENT, callback);
  return () => {
    window.removeEventListener("storage", callback);
    window.removeEventListener(VOTE_EVENT, callback);
  };
}

function useStoredVote(pollId: string): [number | null, (n: number | null) => void] {
  const key = `turtle-poll-vote-${pollId}`;
  const value = useSyncExternalStore(
    subscribeVote,
    () => {
      const stored = window.localStorage.getItem(key);
      if (stored === null) return null;
      const n = Number(stored);
      return Number.isInteger(n) && n >= 0 ? n : null;
    },
    () => null, // SSR: matches the un-voted first paint, hydration-safe.
  );
  const setValue = useCallback(
    (n: number | null) => {
      if (n === null) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, String(n));
      // Same-tab writes don't fire the native `storage` event — broadcast our own.
      window.dispatchEvent(new Event(VOTE_EVENT));
    },
    [key],
  );
  return [value, setValue];
}

export function PollOptions({
  pollId,
  initialOptions,
}: {
  pollId: string;
  initialOptions: Option[];
}) {
  const [options, setOptions] = useState<Option[]>(initialOptions);
  const [votedIndex, setVotedIndex] = useStoredVote(pollId);
  const [pending, setPending] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const total = options.reduce((sum, o) => sum + o.votes, 0);
  const leadingPct =
    total > 0 ? Math.max(...options.map(o => (o.votes / total) * 100)) : 0;

  async function vote(index: number) {
    if (votedIndex !== null || pending !== null) return;
    setError(null);
    setPending(index);

    const deviceId = getOrCreateDeviceId();
    const snapshot = options;
    setOptions(opts =>
      opts.map((o, i) => (i === index ? { ...o, votes: o.votes + 1 } : o)),
    );
    setVotedIndex(index);

    try {
      const res = await fetch(`${WORKER_URL}/poll/${pollId}/vote`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Turtle-Device": deviceId,
        },
        body: JSON.stringify({ optionIndex: index }),
      });

      if (res.status === 409) {
        // Worker already counted this device — keep the local lock, drop the optimistic +1.
        setOptions(snapshot);
        setError("you've already voted on this poll.");
        return;
      }
      if (!res.ok) throw new Error(`worker ${res.status}`);

      const fresh = await fetch(`${WORKER_URL}/poll/${pollId}`, {
        cache: "no-store",
      });
      if (fresh.ok) {
        const data = (await fresh.json()) as { options: Option[] };
        setOptions(data.options);
      }
    } catch {
      setOptions(snapshot);
      setVotedIndex(null);
      setError("couldn't record your vote. tap to try again.");
    } finally {
      setPending(null);
    }
  }

  const locked = votedIndex !== null;

  return (
    <>
      <p className="mt-4 font-mono text-sm text-foam-dim">
        {total} {total === 1 ? "vote" : "votes"}
      </p>

      <div className="mt-10 space-y-3">
        {options.map((opt, i) => {
          const pct = total > 0 ? Math.round((opt.votes / total) * 100) : 0;
          const isLeader =
            total > 0 && (opt.votes / total) * 100 === leadingPct && leadingPct > 0;
          const isMine = votedIndex === i;
          const isPending = pending === i;
          return (
            <button
              key={i}
              type="button"
              onClick={() => vote(i)}
              disabled={locked || pending !== null}
              aria-pressed={isMine}
              className={`w-full text-left glass rounded-2xl px-5 py-4 relative overflow-hidden transition-transform ${
                locked
                  ? "cursor-default"
                  : "cursor-pointer hover:-translate-y-0.5 active:translate-y-0 hover:border-white/25"
              } ${isMine ? "ring-1 ring-cyan/60" : ""} ${
                isPending ? "opacity-80" : ""
              }`}
            >
              <div
                aria-hidden
                className={`absolute inset-y-0 left-0 transition-[width] duration-500 ease-out ${
                  isLeader ? "bg-cyan/25" : "bg-cyan/12"
                }`}
                style={{ width: `${pct}%` }}
              />
              <div className="relative flex items-center justify-between gap-4">
                <span className="font-sans text-base sm:text-lg text-foam flex items-center gap-3">
                  {opt.label}
                  {isMine && (
                    <span className="font-mono text-[10px] tracking-[0.18em] uppercase text-cyan">
                      your vote
                    </span>
                  )}
                </span>
                <span
                  className={`font-mono text-sm font-semibold tabular-nums ${
                    isLeader ? "text-cyan" : "text-foam-dim"
                  }`}
                >
                  {pct}%
                </span>
              </div>
            </button>
          );
        })}
      </div>

      {error && <p className="mt-4 font-mono text-xs text-foam/80">{error}</p>}
    </>
  );
}
