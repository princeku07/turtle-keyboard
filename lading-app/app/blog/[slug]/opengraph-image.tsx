import { ImageResponse } from "next/og";
import { getPost, TAG_DEPTH, formatDate } from "@/lib/blog";

/**
 * Open Graph card for logbook posts — abyssal navy with bioluminescent
 * turquoise, matching the site's "deep" section rather than the poll
 * card's in-keyboard black/green (that one mimics the keyboard theme;
 * this one is brand chrome).
 */

export const alt = "Turtle Keyboard blog";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";
export const runtime = "edge";

const NAVY = "#0c2233";
const NAVY_2 = "#14344c";
const TURQ = "#0db3a5";
const TURQ_BRIGHT = "#3ee8d9";
const SAND = "#f7f3ea";
const MUTED = "rgba(247, 243, 234, 0.55)";

export default async function Image({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const post = getPost(slug);

  const title = post?.title ?? "The Turtle Logbook";
  const depth = post ? TAG_DEPTH[post.tag] : "field notes";
  const date = post ? formatDate(post.date) : "";
  const titleSize = title.length > 58 ? 52 : 62;

  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          background: `linear-gradient(160deg, ${NAVY_2} 0%, ${NAVY} 55%, #081827 100%)`,
          padding: "64px 80px",
          fontFamily: "sans-serif",
          color: SAND,
          position: "relative",
        }}
      >
        {/* bioluminescent glow, top-right */}
        <div
          style={{
            position: "absolute",
            top: -180,
            right: -140,
            width: 620,
            height: 620,
            background: `radial-gradient(circle, rgba(62, 232, 217, 0.20) 0%, rgba(62, 232, 217, 0) 70%)`,
            display: "flex",
          }}
        />
        {/* faint second glow, bottom-left */}
        <div
          style={{
            position: "absolute",
            bottom: -220,
            left: -160,
            width: 560,
            height: 560,
            background: `radial-gradient(circle, rgba(13, 179, 165, 0.14) 0%, rgba(13, 179, 165, 0) 70%)`,
            display: "flex",
          }}
        />

        {/* brand strip */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            position: "relative",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
            <span style={{ fontSize: 44, lineHeight: 1 }}>🐢</span>
            <span style={{ fontSize: 30, fontWeight: 600, letterSpacing: "-0.01em" }}>
              turtle
            </span>
            <span
              style={{
                display: "flex",
                fontSize: 17,
                color: TURQ_BRIGHT,
                border: `1.5px solid rgba(62, 232, 217, 0.4)`,
                borderRadius: 999,
                padding: "5px 16px",
              }}
            >
              /logbook
            </span>
          </div>
          <span
            style={{
              display: "flex",
              fontSize: 18,
              letterSpacing: "0.22em",
              textTransform: "uppercase",
              color: MUTED,
            }}
          >
            {depth}
          </span>
        </div>

        {/* title */}
        <h1
          style={{
            display: "flex",
            margin: 0,
            fontSize: titleSize,
            fontWeight: 700,
            lineHeight: 1.12,
            letterSpacing: "-0.02em",
            maxWidth: 980,
            position: "relative",
          }}
        >
          {title}
        </h1>

        {/* footer */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            fontSize: 21,
            color: MUTED,
            position: "relative",
          }}
        >
          <span style={{ display: "flex" }}>{date}</span>
          <span style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <span style={{ color: TURQ, fontWeight: 700 }}>/</span>
            <span style={{ color: TURQ_BRIGHT, fontWeight: 600 }}>
              turtlekeyboard.com/blog
            </span>
          </span>
        </div>
      </div>
    ),
    size,
  );
}
