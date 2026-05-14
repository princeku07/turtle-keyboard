"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import FlatKeyboard from "../components/FlatKeyboard";
import type { KbdLayout } from "../components/Scene";
import { COMMAND_ORDER, COMMANDS, type CommandId } from "../commands";

// Pivot anchored to where the `/` key sits — offsets from the bottom-right
// of the phone screen. Tune these to nudge the spawn point.
const PIVOT_RIGHT_PX = 78;
const PIVOT_BOTTOM_PX = 54;

// Chips travel along a gentle outward spiral — each successive chip rotates
// a bit further CCW (up-left) and sits a bit further from the pivot. That
// gives a curved arc instead of a straight line.
//
// Angle convention: 0° = straight up from pivot, +° = clockwise (right),
// -° = counter-clockwise (left). CSS y-down is handled at the call site.
const BASE_ANGLE_DEG = -52;       // angle of chip 0 (starts leaning up-left)
const ANGLE_STEP_DEG = 7;         // CW rotation per chip → arc curves to the right as it ascends
const BASE_RADIUS = 72;           // px — distance to chip 0
const RADIUS_STEP = 56;           // px — extra radius per step
const REVEAL_INTERVAL_MS = 70;    // delay between each chip popping out

// Soft fade window in step units. Beyond these, chips fade across FADE_BAND.
const VIS_MIN_STEP = -0.6;
const VIS_MAX_STEP = 5.6;
const FADE_BAND = 0.7;

// Per-chip rotation jitter (deg) added on top of the natural tangent
// rotation, so the line feels "tossed" instead of mechanical. Indices wrap.
const CHIP_TILTS = [-10, 6, -4, 12, -14, 5, -8, 10, -3];

const ACCENT_BG: Record<string, string> = {
  pink: "bg-pink",
  lime: "bg-lime",
  blue: "bg-blue",
  orange: "bg-orange",
  ink: "bg-ink",
};
const ACCENT_FG: Record<string, string> = {
  pink: "text-cream",
  lime: "text-cream",
  blue: "text-cream",
  orange: "text-ink",
  ink: "text-cream",
};

export default function MockPage() {
  const [layout, setLayout] = useState<KbdLayout>("qwerty");
  const [buf, setBuf] = useState("");

  const [open, setOpen] = useState(false);
  const [offset, setOffset] = useState(0);          // step units along the line
  const [revealedCount, setRevealedCount] = useState(0);
  const revealRef = useRef<number | null>(null);

  const stopReveal = () => {
    if (revealRef.current !== null) {
      window.clearInterval(revealRef.current);
      revealRef.current = null;
    }
  };

  const openFan = useCallback(() => {
    stopReveal();
    setOpen(true);
    setOffset(0);
    setRevealedCount(0);
    // staircase reveal — each chip "comes out" one by one
    let n = 0;
    revealRef.current = window.setInterval(() => {
      n += 1;
      setRevealedCount(n);
      if (n >= COMMAND_ORDER.length) stopReveal();
    }, REVEAL_INTERVAL_MS);
  }, []);

  const closeFan = useCallback(() => {
    stopReveal();
    setOpen(false);
    setRevealedCount(0);
  }, []);

  useEffect(() => {
    return () => stopReveal();
  }, []);

  const onKeyTap = useCallback(
    (id: string) => {
      if (id === "numpad") {
        setLayout((m) => (m === "qwerty" ? "numpad" : "qwerty"));
        return;
      }
      if (id === "shift") return;
      if (id === "backspace") {
        setBuf((b) => b.slice(0, -1));
        if (open) closeFan();
        return;
      }
      if (id === "slash") {
        setBuf((b) => (b + "/").slice(-120));
        openFan();
        return;
      }
      const ch =
        id === "space" ? " " : id.startsWith("n_") ? id.slice(2) : id;
      setBuf((b) => (b + ch).slice(-120));
    },
    [open, openFan, closeFan],
  );

  const onClearLine = useCallback(() => {
    setBuf("");
    if (open) closeFan();
  }, [open, closeFan]);

  const onChipTap = (cmd: CommandId) => {
    setBuf((b) => {
      const trimmed = b.endsWith("/") ? b.slice(0, -1) : b;
      return (trimmed + cmd + " ").slice(-120);
    });
    closeFan();
  };

  // Wheel + touch input shifts `offset` along the line.
  const fanRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const el = fanRef.current;
    if (!el) return;

    const MAX_OFFSET = Math.max(0, COMMAND_ORDER.length - 1.5);
    const clamp = (n: number) => Math.max(0, Math.min(MAX_OFFSET, n));
    // 1 step ≈ this many px of finger / wheel motion along the arc
    const PX_PER_STEP = 60;

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const px = e.deltaY + e.deltaX;
      setOffset((s) => clamp(s + px / PX_PER_STEP));
    };

    let last: number | null = null;
    const onTouchStart = (e: TouchEvent) => {
      last = e.touches[0]?.clientY ?? null;
    };
    const onTouchMove = (e: TouchEvent) => {
      if (last === null) return;
      e.preventDefault();
      const y = e.touches[0]?.clientY ?? last;
      const dy = y - last;
      last = y;
      // dragging DOWN reveals earlier chips (offset decreases),
      // dragging UP reveals later chips
      setOffset((s) => clamp(s - dy / PX_PER_STEP));
    };
    const onTouchEnd = () => {
      last = null;
    };

    el.addEventListener("wheel", onWheel, { passive: false });
    el.addEventListener("touchstart", onTouchStart, { passive: true });
    el.addEventListener("touchmove", onTouchMove, { passive: false });
    el.addEventListener("touchend", onTouchEnd);
    el.addEventListener("touchcancel", onTouchEnd);
    return () => {
      el.removeEventListener("wheel", onWheel);
      el.removeEventListener("touchstart", onTouchStart);
      el.removeEventListener("touchmove", onTouchMove);
      el.removeEventListener("touchend", onTouchEnd);
      el.removeEventListener("touchcancel", onTouchEnd);
    };
  }, [open]);

  // Polar position + tangent angle for a chip at this step along the spiral.
  const chipPolar = (step: number) => {
    const angleDeg = BASE_ANGLE_DEG + step * ANGLE_STEP_DEG;
    const radius = BASE_RADIUS + step * RADIUS_STEP;
    const r = (angleDeg * Math.PI) / 180;
    return {
      dx: radius * Math.sin(r),     // CSS x = r·sin θ (θ from up, CW positive)
      dy: -radius * Math.cos(r),    // CSS y-down → negate cos
      angleDeg,
    };
  };

  return (
    <main className="min-h-screen bg-cream px-4 py-8 text-ink grain flex flex-col items-center">
      <div className="text-center mb-5">
        <p className="font-mono text-[10px] uppercase tracking-widest text-ink/55">
          /mock
        </p>
        <h1 className="mt-1 text-2xl sm:text-3xl font-bold tracking-tight">
          slash · chip toss
        </h1>
        <p className="mt-1 text-xs sm:text-sm text-ink/65 max-w-md">
          press{" "}
          <code className="font-mono px-1 rounded bg-ink text-cream">/</code>{" "}
          on the keyboard · chips come out one by one · scroll/swipe to slide
          the line
        </p>
      </div>

      {/* phone frame */}
      <div className="relative w-[340px] sm:w-[380px] aspect-[10/19] bg-ink border-2 border-ink rounded-[40px] p-2 shadow-[10px_10px_0_0_var(--ink)]">
        <div className="relative w-full h-full bg-[#1f1d1a] text-cream rounded-[32px] overflow-hidden flex flex-col">
          {/* address bar */}
          <div className="flex items-center gap-2 px-3 pt-3 pb-2 text-[11px] font-mono text-cream/60">
            <span className="opacity-60">≡</span>
            <span className="flex-1 truncate bg-cream/10 rounded-md px-2 py-1 text-cream/80">
              https://storage.go…
            </span>
            <span className="opacity-60">🎤</span>
          </div>

          {/* composer body */}
          <div className="flex-1 px-4 py-3 overflow-hidden">
            <div className="text-[9px] uppercase tracking-widest text-cream/40 mb-1.5">
              composer
            </div>
            <div className="font-mono text-sm break-all text-cream/95 leading-relaxed">
              {buf || (
                <span className="italic text-cream/30">start typing…</span>
              )}
              <span className="caret text-cream/60 ml-0.5">▍</span>
            </div>
          </div>

          {/* fan overlay */}
          <div
            ref={fanRef}
            className="absolute inset-0 z-10"
            style={{
              pointerEvents: open ? "auto" : "none",
              touchAction: open ? "none" : "auto",
            }}
          >
            {/* dim/blur backdrop while open — tap to close */}
            <div
              onClick={closeFan}
              className="absolute inset-0 transition-opacity duration-300"
              style={{
                background: "rgba(12,12,12,0.40)",
                backdropFilter: "blur(2px)",
                WebkitBackdropFilter: "blur(2px)",
                opacity: open ? 1 : 0,
                pointerEvents: open ? "auto" : "none",
              }}
            />

            {COMMAND_ORDER.map((cmd, i) => {
              const c = COMMANDS[cmd];
              const revealed = open && i < revealedCount;

              const step = i - offset;
              const { dx, dy, angleDeg } = chipPolar(step);

              const distToEdge = Math.min(
                step - VIS_MIN_STEP,
                VIS_MAX_STEP - step,
              );
              const fade = Math.max(0, Math.min(1, distToEdge / FADE_BAND));
              // tangent-aligned rotation + per-chip jitter for the tossed look
              const tilt =
                angleDeg + CHIP_TILTS[i % CHIP_TILTS.length] * 0.5;
              const visibleOpacity = revealed ? fade : 0;

              return (
                <button
                  key={cmd}
                  type="button"
                  onClick={() => onChipTap(cmd)}
                  aria-hidden={!revealed}
                  tabIndex={revealed ? 0 : -1}
                  className={`absolute select-none whitespace-nowrap font-sans text-sm font-bold ${ACCENT_BG[c.accent]} ${ACCENT_FG[c.accent]} border-2 border-ink rounded-xl px-3.5 py-1.5 shadow-[3px_3px_0_0_var(--ink)]`}
                  style={{
                    right: PIVOT_RIGHT_PX,
                    bottom: PIVOT_BOTTOM_PX,
                    transform: revealed
                      ? `translate(${dx}px, ${dy}px) rotate(${tilt}deg)`
                      : `translate(0px, 0px) rotate(0deg) scale(0.35)`,
                    opacity: visibleOpacity,
                    transition:
                      "transform 380ms cubic-bezier(0.34, 1.56, 0.5, 1), opacity 260ms ease-out",
                    transformOrigin: "center",
                    willChange: "transform, opacity",
                  }}
                >
                  {cmd}
                </button>
              );
            })}
          </div>

          {/* keyboard */}
          <div className="px-1 pb-1 relative z-0">
            <FlatKeyboard
              layout={layout}
              onKeyTap={onKeyTap}
              onClearLine={onClearLine}
            />
          </div>
        </div>
      </div>

      <div className="mt-4 font-mono text-[10px] uppercase tracking-widest text-ink/45">
        {open
          ? `fan open · ${revealedCount}/${COMMAND_ORDER.length} out · offset ${offset.toFixed(2)}`
          : "fan closed"}
      </div>
    </main>
  );
}
