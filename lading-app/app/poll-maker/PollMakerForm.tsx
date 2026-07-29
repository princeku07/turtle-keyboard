"use client";

import { useState, type FormEvent } from "react";

/**
 * The poll-link generator. Creates polls/<pushId> in RTDB with the same shape
 * the keyboard and /poll/[id] page use ({question, options: string[],
 * createdAt, expiresAt}) via the established anonymous-auth pattern
 * (see PollOptions.tsx / WaitlistForm.tsx). Polls live ~45 minutes to stay
 * inside the Cloud Function sweep window (realtimedb.ts POLL_TTL_MS = 47 min).
 */

const POLL_LIVE_MS = 45 * 60 * 1000;
const MAX_OPTIONS = 6;

type Status = "idle" | "creating" | "done" | "error";

async function createPoll(question: string, options: string[]): Promise<string> {
  const [{ ensureAnonAuth, db }, { ref, push }] = await Promise.all([
    import("@/lib/firebase-client"),
    import("firebase/database"),
  ]);
  await ensureAnonAuth();
  const now = Date.now();
  const node = await push(ref(db, "polls"), {
    question,
    options,
    createdAt: now,
    expiresAt: now + POLL_LIVE_MS,
  });
  if (!node.key) throw new Error("no key");
  return node.key;
}

export default function PollMakerForm() {
  const [question, setQuestion] = useState("");
  const [options, setOptions] = useState<string[]>(["", ""]);
  const [status, setStatus] = useState<Status>("idle");
  const [link, setLink] = useState("");
  const [copied, setCopied] = useState(false);

  function setOption(i: number, value: string) {
    setOptions((prev) => prev.map((o, j) => (j === i ? value : o)));
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (status === "creating") return;

    const trimmed = options.map((o) => o.trim()).filter(Boolean);
    if (!question.trim() || trimmed.length < 2) return;

    if (!process.env.NEXT_PUBLIC_FIREBASE_DATABASE_URL) {
      setStatus("error");
      return;
    }

    setStatus("creating");
    try {
      const id = await createPoll(question.trim(), trimmed);
      setLink(`${window.location.origin}/poll/${id}`);
      setStatus("done");
    } catch {
      setStatus("error");
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard blocked — the link is still selectable */
    }
  }

  function reset() {
    setQuestion("");
    setOptions(["", ""]);
    setLink("");
    setCopied(false);
    setStatus("idle");
  }

  if (status === "done") {
    return (
      <div className="sea-glass rounded-[28px] p-7 sm:p-9" role="status">
        <div className="flex items-center gap-2 font-mono text-[11px] uppercase tracking-[0.28em] text-turq-deep">
          <span className="h-1.5 w-1.5 rounded-full bg-turq" /> your poll is live
        </div>
        <div className="mt-5 flex flex-col gap-3 sm:flex-row">
          <code className="input-glow flex-1 select-all overflow-x-auto whitespace-nowrap rounded-full px-5 py-[15px] font-mono text-sm text-navy">
            {link}
          </code>
          <button
            type="button"
            onClick={copy}
            className="h-[54px] shrink-0 rounded-full bg-turq px-6 font-mono text-sm font-semibold text-white shadow-[0_10px_30px_-10px_rgba(13,179,165,0.65)] transition-colors duration-300 hover:bg-turq-deep"
          >
            {copied ? "copied 🐢" : "copy link"}
          </button>
        </div>
        <p className="mt-4 text-sm leading-relaxed text-slate">
          Paste it into any chat — voters just tap, no account needed, results
          update live. This poll stays live for about 45 minutes.
        </p>
        <div className="mt-5 flex flex-wrap gap-x-6 gap-y-2 font-mono text-[13px]">
          <a href={link} target="_blank" rel="noreferrer" className="text-turq-deep transition-colors duration-300 hover:text-turq">
            open your poll ↗
          </a>
          <button type="button" onClick={reset} className="cursor-pointer text-slate transition-colors duration-300 hover:text-navy">
            create another →
          </button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="sea-glass rounded-[28px] p-7 sm:p-9">
      <label htmlFor="pm-question" className="font-mono text-[11px] uppercase tracking-[0.28em] text-slate">
        the question
      </label>
      <input
        id="pm-question"
        type="text"
        required
        maxLength={120}
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        placeholder="Movie, bowling, or drinks?"
        className="input-glow mt-2.5 h-[54px] w-full rounded-full px-5 text-[15px] text-navy"
      />

      <div className="mt-6 font-mono text-[11px] uppercase tracking-[0.28em] text-slate">
        the options
      </div>
      <div className="mt-2.5 space-y-2.5">
        {options.map((opt, i) => (
          <div key={i} className="flex items-center gap-2.5">
            <input
              type="text"
              required={i < 2}
              maxLength={60}
              value={opt}
              onChange={(e) => setOption(i, e.target.value)}
              placeholder={`option ${i + 1}`}
              aria-label={`option ${i + 1}`}
              className="input-glow h-[48px] flex-1 rounded-full px-5 text-[15px] text-navy"
            />
            {options.length > 2 && (
              <button
                type="button"
                aria-label={`remove option ${i + 1}`}
                onClick={() => setOptions((prev) => prev.filter((_, j) => j !== i))}
                className="grid h-9 w-9 shrink-0 cursor-pointer place-items-center rounded-full border border-navy/15 text-slate transition-colors duration-300 hover:border-navy/40 hover:text-navy"
              >
                ×
              </button>
            )}
          </div>
        ))}
      </div>

      {options.length < MAX_OPTIONS && (
        <button
          type="button"
          onClick={() => setOptions((prev) => [...prev, ""])}
          className="mt-3 cursor-pointer font-mono text-[13px] text-turq-deep transition-colors duration-300 hover:text-turq"
        >
          + add option
        </button>
      )}

      <button
        type="submit"
        disabled={status === "creating"}
        className="mt-7 h-[54px] w-full rounded-full bg-turq font-mono text-sm font-semibold text-white shadow-[0_10px_30px_-10px_rgba(13,179,165,0.65)] transition-all duration-300 hover:bg-turq-deep disabled:cursor-wait disabled:opacity-80"
      >
        {status === "creating" ? "minting your link…" : "create my poll link →"}
      </button>

      {status === "error" && (
        <p className="mt-4 text-center font-mono text-xs text-slate">
          hm — the poll service didn&rsquo;t answer. try again in a moment?
        </p>
      )}

      <p className="mt-4 text-center font-mono text-[11px] text-slate/80">
        no account · anonymous voting · live results · free
      </p>
    </form>
  );
}
