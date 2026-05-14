import { ImageResponse } from "next/og";

/**
 * Dynamic Open Graph image for a poll share link. Resolved at
 * {@code https://www.turtlekeyboard.com/poll/<id>/opengraph-image} (Next.js
 * route-segment convention) and referenced from {@code page.tsx}'s metadata.
 *
 * <p>WhatsApp, iMessage, Slack, Twitter, Discord all fetch this URL when a poll
 * link is pasted into a chat — so what we render here is the visible card the
 * recipient sees without opening the link.
 *
 * <p>Visual: turtle's ocean → abyss gradient, foam typography, cyan accent.
 * Same palette tokens that drive the landing page, kept inline because
 * ImageResponse can't pull from {@code globals.css}.
 */

export const alt = "Turtle poll";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

const WORKER_URL =
  process.env.NEXT_PUBLIC_WORKER_URL || "https://turtle-worker.trtlk.workers.dev";

type Option = { label: string; votes: number };
type Poll = {
  id: string;
  createdAt: number;
  question: string;
  options: Option[];
};

async function getPoll(id: string): Promise<Poll | null> {
  try {
    const res = await fetch(`${WORKER_URL}/poll/${id}`, { cache: "no-store" });
    if (!res.ok) return null;
    return (await res.json()) as Poll;
  } catch {
    return null;
  }
}

// Palette mirrors lading-app/app/globals.css.
const ABYSS = "#08182a";
const OCEAN = "#0e2e44";
const REEF = "#1d5d72";
const CYAN = "#7ec5cc";
const FOAM = "#ece6d4";
const FOAM_DIM = "#a89e8a";

export default async function Image({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const poll = await getPoll(id);

  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          background: `linear-gradient(135deg, ${OCEAN} 0%, ${ABYSS} 100%)`,
          color: FOAM,
          padding: "64px 80px",
          fontFamily: "sans-serif",
        }}
      >
        {/* Brand strip */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 18,
            fontSize: 36,
            color: CYAN,
            letterSpacing: "0.18em",
          }}
        >
          <span style={{ fontSize: 60 }}>🐢</span>
          <span style={{ fontWeight: 600 }}>TURTLE · POLL</span>
        </div>

        {/* Question — hero. Sized by length so very short or very long
            questions both occupy the card without overflowing. */}
        <h1
          style={{
            display: "flex",
            margin: "44px 0 0 0",
            fontSize: poll && poll.question.length > 60 ? 64 : 84,
            fontWeight: 700,
            lineHeight: 1.05,
            letterSpacing: "-0.02em",
            color: FOAM,
          }}
        >
          {poll ? poll.question : "Poll not found"}
        </h1>

        {/* Options as chips. Max 4 shown; overflow collapses to "+N more". */}
        {poll && (
          <div
            style={{
              display: "flex",
              flexWrap: "wrap",
              gap: 14,
              marginTop: 36,
            }}
          >
            {poll.options.slice(0, 4).map((o) => (
              <div
                key={o.label}
                style={{
                  display: "flex",
                  fontSize: 30,
                  fontWeight: 500,
                  color: FOAM,
                  background: "rgba(126, 197, 204, 0.16)",
                  border: `2px solid ${REEF}`,
                  padding: "12px 22px",
                  borderRadius: 999,
                }}
              >
                {o.label}
              </div>
            ))}
            {poll.options.length > 4 && (
              <div
                style={{
                  display: "flex",
                  fontSize: 26,
                  color: FOAM_DIM,
                  padding: "12px 8px",
                  alignItems: "center",
                }}
              >
                +{poll.options.length - 4} more
              </div>
            )}
          </div>
        )}

        {/* Footer CTA */}
        <div
          style={{
            display: "flex",
            marginTop: "auto",
            fontSize: 26,
            color: FOAM_DIM,
            letterSpacing: "0.04em",
          }}
        >
          tap to vote · in turtle keyboard ↗
        </div>
      </div>
    ),
    size,
  );
}
