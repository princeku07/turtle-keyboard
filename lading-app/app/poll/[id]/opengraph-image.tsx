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
 * <p>Design goal: read as a real <i>poll widget</i>, not a marketing card.
 * Vertical list of options with vote bars + counts (Twitter-poll shape),
 * total votes at the bottom, light brand chrome at the top so the poll is the
 * hero. Same palette tokens that drive the landing page.
 */

export const alt = "Turtle poll";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

// Edge runtime + a short public cache so WhatsApp's crawler gets a fast
// response on first scrape (it bails on slow images) and subsequent shares of
// the same link don't re-render the PNG on every fetch.
export const runtime = "edge";

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
    const res = await fetch(`${WORKER_URL}/poll/${id}`, {
      next: { revalidate: 300 },
    });
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
    poll ? renderPoll(poll) : renderMissing(),
    {
      ...size,
      headers: {
        "Cache-Control":
          "public, max-age=300, s-maxage=300, stale-while-revalidate=86400",
      },
    },
  );
}

function renderPoll(poll: Poll) {
  const totalVotes = poll.options.reduce((sum, o) => sum + (o.votes || 0), 0);
  // 4 is the cap that fits comfortably at 28 sp/row with a hero question.
  const visibleOptions = poll.options.slice(0, 4);
  const overflowCount = poll.options.length - visibleOptions.length;
  // Question shrinks for long copy so the bars below still have room.
  const questionSize = poll.question.length > 56 ? 54 : 68;

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: "column",
        background: `linear-gradient(135deg, ${OCEAN} 0%, ${ABYSS} 100%)`,
        padding: "56px 80px",
        fontFamily: "sans-serif",
        color: FOAM,
      }}
    >
      {/* Brand strip — small turtle wordmark + POLL pill, so the card reads
          as "a poll on turtle" rather than "click for a website". */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <span style={{ fontSize: 46, lineHeight: 1 }}>🐢</span>
          <span
            style={{
              fontSize: 28,
              fontWeight: 600,
              letterSpacing: "-0.01em",
              color: FOAM,
            }}
          >
            turtle
          </span>
        </div>
        <div
          style={{
            display: "flex",
            fontSize: 20,
            fontWeight: 700,
            letterSpacing: "0.22em",
            color: CYAN,
            padding: "8px 18px",
            border: `2px solid ${CYAN}`,
            borderRadius: 999,
          }}
        >
          POLL
        </div>
      </div>

      {/* Question — hero */}
      <h1
        style={{
          display: "flex",
          margin: "36px 0 28px 0",
          fontSize: questionSize,
          fontWeight: 700,
          lineHeight: 1.05,
          letterSpacing: "-0.02em",
          color: FOAM,
        }}
      >
        {poll.question}
      </h1>

      {/* Options as vote bars — each row stacks label/count + the bar
          underneath. Empty polls show empty tracks (0 % everywhere) which
          still reads as "fresh poll, be the first to vote". */}
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        {visibleOptions.map((option) => {
          const pct =
            totalVotes > 0
              ? Math.round(((option.votes || 0) / totalVotes) * 100)
              : 0;
          return (
            <div
              key={option.label}
              style={{ display: "flex", flexDirection: "column", gap: 8 }}
            >
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                }}
              >
                <span style={{ fontSize: 28, color: FOAM, fontWeight: 500 }}>
                  {option.label}
                </span>
                <span
                  style={{
                    fontSize: 24,
                    color: FOAM_DIM,
                    fontWeight: 500,
                  }}
                >
                  {pct}%
                </span>
              </div>
              {/* Track + fill. Always render the track; fill width is the
                  percentage. With 0 votes everything reads empty. */}
              <div
                style={{
                  display: "flex",
                  height: 12,
                  background: "rgba(255, 255, 255, 0.08)",
                  borderRadius: 6,
                }}
              >
                <div
                  style={{
                    display: "flex",
                    width: `${pct}%`,
                    background: CYAN,
                    borderRadius: 6,
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>

      {/* Footer: aggregate stats + CTA. Keeps the card grounded so the
          recipient sees what they'd be joining. */}
      <div
        style={{
          display: "flex",
          marginTop: "auto",
          alignItems: "center",
          justifyContent: "space-between",
          fontSize: 22,
          color: FOAM_DIM,
        }}
      >
        <span style={{ display: "flex" }}>
          {totalVotes} {totalVotes === 1 ? "vote" : "votes"}
          {overflowCount > 0 ? `  ·  +${overflowCount} more options` : ""}
        </span>
        <span
          style={{
            display: "flex",
            color: CYAN,
            fontWeight: 600,
            letterSpacing: "0.04em",
          }}
        >
          tap to vote ↗
        </span>
      </div>
    </div>
  );
}

function renderMissing() {
  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        background: `linear-gradient(135deg, ${OCEAN} 0%, ${ABYSS} 100%)`,
        fontFamily: "sans-serif",
        color: FOAM,
        gap: 16,
      }}
    >
      <span style={{ fontSize: 72 }}>🐢</span>
      <div style={{ display: "flex", fontSize: 40, fontWeight: 600 }}>
        Poll not found
      </div>
      <div style={{ display: "flex", fontSize: 22, color: FOAM_DIM }}>
        It may have expired
      </div>
    </div>
  );
}
