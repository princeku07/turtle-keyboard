"use client";

import { useState } from "react";

/**
 * The on-screen sandbox (emotion.txt §3): a real, touchable sea-glass poll.
 * The visitor taps an option and the response is instantaneous — the brain
 * files Turtle under "absolutely reliable" before they ever join the list.
 */

const OPTIONS = [
  { label: "movie", votes: 6 },
  { label: "bowling", votes: 4 },
  { label: "drinks", votes: 2 },
];

export default function LivePollDemo() {
  const [picked, setPicked] = useState<number | null>(null);

  const total = OPTIONS.reduce((s, o) => s + o.votes, 0) + (picked !== null ? 1 : 0);

  return (
    <div className="sea-glass rounded-2xl p-3">
      <div className="flex items-center justify-between">
        <span className="text-[11px] font-semibold">friday plans</span>
        <span className="flex items-center gap-1 font-mono text-[8px] uppercase tracking-widest text-iris">
          <span className="h-1.5 w-1.5 rounded-full bg-iris" /> live
        </span>
      </div>

      <div className="mt-2 space-y-1.5">
        {OPTIONS.map((o, i) => {
          const votes = o.votes + (picked === i ? 1 : 0);
          const pct = Math.round((votes / total) * 100);
          const mine = picked === i;
          return (
            <button
              key={o.label}
              type="button"
              onClick={() => setPicked(i)}
              aria-pressed={mine}
              className={`relative block w-full overflow-hidden rounded-lg bg-white/75 text-left transition-shadow duration-300 ${
                mine ? "shadow-[0_0_0_1.5px_var(--iris)]" : "hover:shadow-[0_0_0_1px_rgba(28,107,96,0.4)]"
              }`}
            >
              <span
                className="absolute inset-y-0 left-0 bg-iris/20 transition-[width] duration-700 ease-out"
                style={{ width: `${pct}%` }}
              />
              <span className="relative flex items-center justify-between px-2.5 py-1.5 text-[10px]">
                <span>
                  {o.label}
                  {mine && <span className="ml-1.5 text-iris">✓ you</span>}
                </span>
                <span className="font-mono text-slate">{votes}</span>
              </span>
            </button>
          );
        })}
      </div>

      <div className="mt-2 text-[9px] text-slate">
        {picked === null
          ? `${total} votes · go on, tap one — it's real`
          : `${total} votes · counted instantly. that's the whole idea 🐢`}
      </div>
    </div>
  );
}
