"use client";

import { useState, type FormEvent } from "react";

/**
 * Email capture with the micro-interactions from design.txt §5:
 *  - input border glows bioluminescent turquoise over 200ms on focus
 *    (`.input-glow` / `.input-glow-dark` in globals.css)
 *  - on submit the button text is replaced by a walking turtle for 1.5s,
 *    then the form resolves to "You're on the list 🐢"
 *
 * Persistence: emails are pushed to RTDB `waitlist/` (anonymous auth, same
 * pattern as poll voting) when NEXT_PUBLIC_FIREBASE_DATABASE_URL is set.
 * Without it (local dev / preview) the walk animation still plays and the
 * form resolves optimistically so the page remains demoable.
 */

type Status = "idle" | "walking" | "done" | "error";

async function persist(email: string): Promise<void> {
  if (!process.env.NEXT_PUBLIC_FIREBASE_DATABASE_URL) return;
  const [{ ensureAnonAuth, db }, { ref, push, serverTimestamp }] =
    await Promise.all([import("@/lib/firebase-client"), import("firebase/database")]);
  await ensureAnonAuth();
  await push(ref(db, "waitlist"), { email, joinedAt: serverTimestamp() });
}

export default function WaitlistForm({
  dark = false,
  center = false,
}: {
  /** true when the form floats on abyssal navy (footer) */
  dark?: boolean;
  center?: boolean;
}) {
  const [status, setStatus] = useState<Status>("idle");
  const [email, setEmail] = useState("");

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (status === "walking" || status === "done") return;

    setStatus("walking");
    // the turtle takes exactly 1.5s to cross, no matter how fast the write is
    const walk = new Promise((r) => setTimeout(r, 1500));
    try {
      await Promise.all([persist(email.trim()), walk]);
      setStatus("done");
    } catch {
      await walk;
      setStatus("error");
    }
  }

  if (status === "done") {
    return (
      <div
        className={`${dark ? "sea-glass-dark text-white" : "sea-glass text-navy"} ${
          center ? "mx-auto" : ""
        } flex h-[54px] max-w-md items-center justify-center rounded-full px-6 font-mono text-sm`}
        role="status"
      >
        You&rsquo;re on the list&nbsp;🐢
      </div>
    );
  }

  return (
    <form
      onSubmit={onSubmit}
      className={`flex w-full max-w-md flex-col gap-3 sm:flex-row ${center ? "mx-auto" : ""}`}
    >
      <label className="sr-only" htmlFor={dark ? "email-deep" : "email-surface"}>
        Email address
      </label>
      <input
        id={dark ? "email-deep" : "email-surface"}
        type="email"
        required
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        placeholder="enter email"
        autoComplete="email"
        className={`${dark ? "input-glow-dark" : "input-glow"} h-[54px] flex-1 rounded-full px-5 font-mono text-sm`}
      />
      <button
        type="submit"
        disabled={status === "walking"}
        className="btn-grad relative h-[54px] shrink-0 overflow-hidden rounded-full px-6 font-mono text-sm font-semibold disabled:cursor-wait"
      >
        {status === "walking" ? (
          <span className="turtle-lane" aria-label="joining…">
            <span className="turtle-walk text-xl">
              <span>🐢</span>
            </span>
          </span>
        ) : status === "error" ? (
          "hm — try again?"
        ) : (
          "grab my spot →"
        )}
      </button>
    </form>
  );
}
