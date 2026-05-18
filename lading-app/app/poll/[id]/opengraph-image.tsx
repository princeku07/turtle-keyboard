import { ImageResponse } from "next/og";
import { fetchPoll, type Poll } from "@/lib/realtimedb";

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
 * hero. Palette matches {@code KeyboardTheme.turtleLight()} — black canvas
 * with a green accent. High contrast was the deciding factor for thumbnail
 * legibility in chat-app previews (typical render: 300×150 inside a message
 * bubble; pure black + #15803D survives that downscale cleanly).
 */

export const alt = "Turtle poll";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

// Edge runtime + a short public cache so WhatsApp's crawler gets a fast
// response on first scrape (it bails on slow images) and subsequent shares of
// the same link don't re-render the PNG on every fetch.
export const runtime = "edge";

// Palette mirrors KeyboardTheme.turtleLight() — the in-keyboard look.
const BG = "#000000";
const ACCENT = "#15803D";
const ACCENT_DIM = "rgba(21, 128, 61, 0.22)";
const TRACK = "rgba(255, 255, 255, 0.06)";
const TEXT = "#F5F5F5";
const MUTED = "#888888";
const BORDER = "#2E2E2E";

export default async function Image({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const poll = await fetchPoll(id);

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
  // 4 is the cap that fits comfortably alongside a hero question.
  const visibleOptions = poll.options.slice(0, 4);
  const overflowCount = poll.options.length - visibleOptions.length;
  // Question shrinks for long copy so the bars below still have room.
  const questionSize = poll.question.length > 56 ? 54 : 68;
  // Leader index for the bright bar fill — first option with the max votes.
  let leaderIdx = -1;
  if (totalVotes > 0) {
    let leaderVotes = -1;
    for (let i = 0; i < visibleOptions.length; i++) {
      if ((visibleOptions[i].votes || 0) > leaderVotes) {
        leaderVotes = visibleOptions[i].votes || 0;
        leaderIdx = i;
      }
    }
  }

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: "column",
        background: BG,
        padding: "56px 80px",
        fontFamily: "sans-serif",
        color: TEXT,
        position: "relative",
      }}
    >
      {/* Subtle radial green glow in the top-right corner — gives the black
          canvas a hint of depth without competing with the question. */}
      <div
        style={{
          position: "absolute",
          top: -120,
          right: -120,
          width: 540,
          height: 540,
          background:
            "radial-gradient(circle, rgba(21, 128, 61, 0.22) 0%, rgba(21, 128, 61, 0) 70%)",
          display: "flex",
        }}
      />

      {/* Brand strip — small turtle wordmark + POLL pill, so the card reads
          as "a poll on turtle" rather than "click for a website". */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          position: "relative",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <span style={{ fontSize: 46, lineHeight: 1 }}>🐢</span>
          <span
            style={{
              fontSize: 28,
              fontWeight: 600,
              letterSpacing: "-0.01em",
              color: TEXT,
            }}
          >
            turtle
          </span>
        </div>
        <div
          style={{
            display: "flex",
            fontSize: 18,
            fontWeight: 700,
            letterSpacing: "0.24em",
            color: TEXT,
            padding: "8px 18px",
            background: ACCENT,
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
          margin: "40px 0 32px 0",
          fontSize: questionSize,
          fontWeight: 700,
          lineHeight: 1.05,
          letterSpacing: "-0.02em",
          color: TEXT,
          position: "relative",
        }}
      >
        {poll.question}
      </h1>

      {/* Options as vote bars — leader gets a bright accent fill, others get
          the dimmer accent so the visual hierarchy reads at a glance even
          before the percentages register. */}
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          gap: 18,
          position: "relative",
        }}
      >
        {visibleOptions.map((option, idx) => {
          const pct =
            totalVotes > 0
              ? Math.round(((option.votes || 0) / totalVotes) * 100)
              : 0;
          const isLeader = idx === leaderIdx;
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
                <span
                  style={{
                    fontSize: 28,
                    color: isLeader ? TEXT : MUTED,
                    fontWeight: isLeader ? 600 : 500,
                  }}
                >
                  {option.label}
                </span>
                <span
                  style={{
                    fontSize: 24,
                    color: isLeader ? ACCENT : MUTED,
                    fontWeight: 600,
                  }}
                >
                  {pct}%
                </span>
              </div>
              {/* Track + fill. Track is a faint dark band so empty polls still
                  read as "structured", not blank. */}
              <div
                style={{
                  display: "flex",
                  height: 12,
                  background: TRACK,
                  borderRadius: 6,
                  border: `1px solid ${BORDER}`,
                }}
              >
                <div
                  style={{
                    display: "flex",
                    width: `${pct}%`,
                    background: isLeader ? ACCENT : ACCENT_DIM,
                    borderRadius: 6,
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>

      {/* Footer: aggregate stats + CTA. Grounds the card so the recipient
          sees what they'd be joining. */}
      <div
        style={{
          display: "flex",
          marginTop: "auto",
          alignItems: "center",
          justifyContent: "space-between",
          fontSize: 22,
          color: MUTED,
          position: "relative",
        }}
      >
        <span style={{ display: "flex" }}>
          {totalVotes} {totalVotes === 1 ? "vote" : "votes"}
          {overflowCount > 0 ? `  ·  +${overflowCount} more options` : ""}
        </span>
        <span
          style={{
            display: "flex",
            color: ACCENT,
            fontWeight: 700,
            letterSpacing: "0.04em",
          }}
        >
          tap to vote →
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
        background: BG,
        fontFamily: "sans-serif",
        color: TEXT,
        gap: 16,
      }}
    >
      <span style={{ fontSize: 72 }}>🐢</span>
      <div style={{ display: "flex", fontSize: 40, fontWeight: 600 }}>
        Poll not found
      </div>
      <div style={{ display: "flex", fontSize: 22, color: MUTED }}>
        It may have ended
      </div>
    </div>
  );
}
