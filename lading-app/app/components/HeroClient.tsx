"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import SceneWrapper from "./SceneWrapper";
import type { PressMap } from "./Scene";

const PRESS_ACCENT = "#15803d";

type Visual = { gradient: string; emoji: string };

const PROMPT_VISUALS: Array<{ match: RegExp } & Visual> = [
  { match: /samurai|katana|sword|warrior/i, gradient: "from-orange via-pink to-ink", emoji: "🐱⚔️" },
  { match: /retriever|golden|dog|puppy/i,  gradient: "from-orange via-cream to-pink", emoji: "🐕" },
  { match: /cyber|punk|neon|future|robot/i, gradient: "from-blue via-pink to-orange", emoji: "🤖" },
  { match: /cat|kitten|kitty/i,             gradient: "from-pink via-orange to-blue", emoji: "🐈" },
  { match: /coffee|latte|cup/i,             gradient: "from-orange via-pink to-cream", emoji: "☕" },
  { match: /pixel|8.?bit|retro/i,           gradient: "from-blue via-ink to-pink", emoji: "👾" },
  { match: /sticker|cute|kawaii/i,          gradient: "from-pink via-cream to-blue", emoji: "✨" },
  { match: /space|moon|star|galaxy/i,       gradient: "from-ink via-blue to-pink", emoji: "🌌" },
  { match: /food|burger|pizza|sushi/i,      gradient: "from-orange via-pink to-cream", emoji: "🍔" },
  { match: /bird|owl|eagle/i,               gradient: "from-blue via-cream to-orange", emoji: "🦉" },
];

const DEFAULT_VISUAL: Visual = { gradient: "from-blue via-pink to-orange", emoji: "🎨" };

function pickVisual(prompt: string): Visual {
  for (const v of PROMPT_VISUALS) if (v.match.test(prompt)) return v;
  return DEFAULT_VISUAL;
}

type Mode =
  | { kind: "free"; buf: string }
  | { kind: "command"; command: string; prompt: string };

const COMMAND_TRIGGER = /^\/(cap|image)$/i;

export default function HeroClient() {
  const pressedRef = useRef<PressMap>(new Map());
  const [mode, setMode] = useState<Mode>({ kind: "free", buf: "" });
  const [isMobile, setIsMobile] = useState(false);
  const [genState, setGenState] = useState<
    | { kind: "idle" }
    | { kind: "generating"; prompt: string }
    | { kind: "done"; prompt: string; visual: Visual; ms: number }
  >({ kind: "idle" });

  useEffect(() => {
    const mq = window.matchMedia("(max-width: 640px)");
    const update = () => setIsMobile(mq.matches);
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, []);

  const press = useCallback(
    (id: string, ch: string | null, mirrorTyped: boolean) => {
      pressedRef.current.set(id, { at: performance.now(), accent: PRESS_ACCENT });

      if (typeof navigator !== "undefined" && "vibrate" in navigator) {
        try {
          navigator.vibrate(8);
        } catch {}
      }

      if (!mirrorTyped) return;

      setMode((m) => {
        if (m.kind === "free") {
          if (id === "backspace") return { kind: "free", buf: m.buf.slice(0, -1) };
          if (ch === null) return m;
          // The headline only "wakes up" when the user starts a command with /
          if (m.buf.length === 0 && ch !== "/") return m;
          const next = (m.buf + ch).slice(-48);
          const trig = next.match(COMMAND_TRIGGER);
          if (trig) {
            return { kind: "command", command: "/" + trig[1].toLowerCase(), prompt: "" };
          }
          return { kind: "free", buf: next };
        }
        // command mode — keys flow into the prompt; backspace at empty prompt exits
        if (id === "backspace") {
          if (m.prompt.length === 0) {
            return { kind: "free", buf: m.command.slice(0, -1) };
          }
          return { ...m, prompt: m.prompt.slice(0, -1) };
        }
        if (ch === null) return m;
        // skip a leading space — the prompt sits visually after the command pill
        if (m.prompt.length === 0 && ch === " ") return m;
        return { ...m, prompt: (m.prompt + ch).slice(-64) };
      });
    },
    [],
  );

  const handleKeyTap = useCallback(
    (id: string) => {
      const ch =
        id === "space" ? " " : id === "slash" ? "/" : id === "backspace" || id === "shift" ? null : id;
      press(id, ch, true);
    },
    [press],
  );

  const inCommand = mode.kind === "command";
  const commandLabel = inCommand ? mode.command : null;
  const commandPrompt = inCommand ? mode.prompt : "";

  // Trigger image generation once the command has a non-trivial prompt
  useEffect(() => {
    if (!inCommand || commandPrompt.trim().length < 2) {
      setGenState({ kind: "idle" });
      return;
    }
    const prompt = commandPrompt.trim();
    setGenState({ kind: "generating", prompt });
    const startedAt = performance.now();
    const t = window.setTimeout(() => {
      const ms = performance.now() - startedAt;
      setGenState({ kind: "done", prompt, visual: pickVisual(prompt), ms });
    }, 1300 + Math.random() * 300);
    return () => window.clearTimeout(t);
  }, [inCommand, commandLabel, commandPrompt]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (document.activeElement?.tagName ?? "").toLowerCase();
      const otherInputFocused = tag === "input" || tag === "textarea";

      const k = e.key.toLowerCase();
      let id: string | null = null;
      let ch: string | null = null;
      if (k === " ") {
        id = "space";
        ch = " ";
      } else if (k === "backspace") {
        id = "backspace";
      } else if (k === "shift") {
        id = "shift";
      } else if (k === "/") {
        id = "slash";
        ch = "/";
      } else if (/^[a-z0-9]$/.test(k)) {
        id = k;
        ch = k;
      }
      if (!id) return;

      // when the user isn't focused in a real input, swallow the key so the
      // browser doesn't scroll on space or navigate back on backspace
      if (!otherInputFocused && (k === " " || k === "backspace" || k === "/")) {
        e.preventDefault();
      }

      press(id, ch, !otherInputFocused);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [press]);

  return (
    <section className="relative">
      <div className="mx-auto max-w-[1400px] w-full px-5 sm:px-6 pt-6 sm:pt-10 text-center">
        <div className="inline-flex items-center gap-2 font-mono text-[10px] sm:text-xs uppercase tracking-widest bg-ink text-cream px-3 py-1.5 rounded-full mb-5 sm:mb-7">
          <span className="w-2 h-2 rounded-full bg-[#15803d] animate-pulse" />
          now in closed alpha · ios first
        </div>

        <h1 className="font-sans font-black tracking-[-0.04em] leading-[0.92] text-[clamp(2.75rem,9vw,6rem)] min-h-[1.05em] break-words">
          {mode.kind === "command" ? (
            <span className="inline-flex items-baseline flex-wrap justify-center gap-x-3 gap-y-1">
              <span className="relative inline-block isolate font-mono">
                <span className="absolute inset-0 -rotate-2 bg-[#15803d] -z-10 rounded-md" />
                <span className="relative px-2 text-white">{mode.command}</span>
              </span>
              <span className="font-mono inline-flex items-baseline text-[0.45em] sm:text-[0.4em] tracking-normal font-medium">
                <span className="inline-flex items-baseline min-w-[19ch] whitespace-nowrap">
                  {mode.prompt && <span className="text-ink">{mode.prompt}</span>}
                  <span className="caret text-ink/60">▍</span>
                  {!mode.prompt && (
                    <span
                      className="italic font-normal text-ink/30 select-none pointer-events-none ml-1"
                      aria-hidden="true"
                    >
                      type image prompt…
                    </span>
                  )}
                </span>
              </span>
            </span>
          ) : mode.buf ? (
            <span className="font-mono inline-flex items-baseline">
              <span className="break-all">{mode.buf}</span>
              <span className="caret ml-1">▍</span>
            </span>
          ) : (
            <span>
              type a{" "}
              <span className="relative inline-block isolate">
                <span className="absolute inset-0 -rotate-2 bg-[#15803d] -z-10 rounded-md" />
                <span className="relative px-2 text-white">slash.</span>
              </span>
            </span>
          )}
        </h1>

        <p className="mt-5 sm:mt-7 max-w-xl mx-auto text-base sm:text-lg leading-relaxed text-ink/75">
          Turtle is the open-source AI keyboard that turns any text field into a
          generator. Type{" "}
          <em className="not-italic font-mono bg-pink text-cream px-1.5 rounded">
            /cap a samurai cat
          </em>{" "}
          and a custom image lands in your composer in ~1.5 seconds.
        </p>

        <div className="mt-6 sm:mt-7 flex flex-wrap items-center justify-center gap-3">
          <a
            href="https://github.com/princeku07/turtle-keyboard"
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-2 font-mono text-sm px-5 py-2.5 rounded-full border-2 border-ink bg-cream hover:bg-ink hover:text-cream transition-colors"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" className="w-4 h-4 fill-current">
              <path d="M12 .5C5.73.5.5 5.73.5 12c0 5.08 3.29 9.39 7.86 10.91.58.11.79-.25.79-.56 0-.27-.01-1.17-.02-2.12-3.2.7-3.87-1.36-3.87-1.36-.52-1.32-1.27-1.67-1.27-1.67-1.04-.71.08-.7.08-.7 1.15.08 1.76 1.18 1.76 1.18 1.02 1.76 2.69 1.25 3.35.96.1-.74.4-1.25.72-1.54-2.55-.29-5.24-1.28-5.24-5.69 0-1.26.45-2.29 1.18-3.1-.12-.29-.51-1.46.11-3.05 0 0 .96-.31 3.15 1.18.91-.25 1.89-.38 2.86-.38.97 0 1.95.13 2.86.38 2.18-1.49 3.14-1.18 3.14-1.18.62 1.59.23 2.76.11 3.05.74.81 1.18 1.84 1.18 3.1 0 4.42-2.69 5.39-5.25 5.68.41.36.78 1.06.78 2.14 0 1.55-.01 2.79-.01 3.17 0 .31.21.68.8.56C20.21 21.39 23.5 17.07 23.5 12 23.5 5.73 18.27.5 12 .5z" />
            </svg>
            github ↗
          </a>
        </div>
      </div>

      {/* generated-image demo card — slides in when user types /cap or /image */}
      {genState.kind !== "idle" && (
        <div className="mx-auto w-full px-5 sm:px-6 mt-6 flex justify-center">
          <GenCard state={genState} />
        </div>
      )}

      {/* keyboard */}
      <div className="mx-auto w-full px-4 sm:px-6 mt-8 sm:mt-10 flex justify-center">
        <div className="relative w-full max-w-[960px] aspect-[2.4]">
          <SceneWrapper pressedRef={pressedRef} flat={isMobile} onKeyTap={handleKeyTap} />
        </div>
      </div>

      {/* footer meta — pinned close to keyboard, not stranded by a spacer */}
      <div className="mx-auto max-w-[1100px] w-full px-6 mt-5 sm:mt-6 pb-10 flex flex-wrap items-center justify-center gap-x-5 gap-y-2 font-mono text-[11px] sm:text-xs text-ink/55">
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-ink" /> open source
        </div>
        <span className="opacity-30">·</span>
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-pink" /> image-first launch
        </div>
        <span className="opacity-30">·</span>
        <div className="flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-blue" /> ~1.5s end-to-end
        </div>
        <span className="opacity-30 hidden sm:inline">·</span>
        <span className="hidden sm:inline opacity-60">{isMobile ? "tap" : "press"} a key — it lands in the headline ↑</span>
      </div>
    </section>
  );
}

type GenCardProps = {
  state:
    | { kind: "generating"; prompt: string }
    | { kind: "done"; prompt: string; visual: Visual; ms: number }
    | { kind: "idle" };
};

function GenCard({ state }: GenCardProps) {
  if (state.kind === "idle") return null;

  const generating = state.kind === "generating";
  const visual = state.kind === "done" ? state.visual : DEFAULT_VISUAL;
  const seconds = state.kind === "done" ? (state.ms / 1000).toFixed(1) : "1.5";

  return (
    <div
      className="gen-card-pop relative w-full max-w-[340px] sm:max-w-[380px] border-2 border-ink rounded-2xl bg-cream shadow-[6px_6px_0_0_var(--ink)] p-3 sm:p-4"
      role="status"
      aria-live="polite"
    >
      <div className="flex items-center justify-between mb-2.5">
        <span className="font-mono text-[10px] uppercase tracking-widest bg-ink text-cream px-2 py-1 rounded-full">
          /cap result
        </span>
        <span className="font-mono text-[10px] text-ink/60">
          {generating ? "flux schnell" : `flux schnell · ${seconds}s`}
        </span>
      </div>

      <div
        className={`relative aspect-square w-full rounded-xl border-2 border-ink overflow-hidden bg-gradient-to-br ${visual.gradient} grain`}
      >
        {generating ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-cream/30 backdrop-blur-sm">
            <div className="flex gap-1.5">
              <span className="w-2 h-2 rounded-full bg-ink animate-bounce [animation-delay:-0.2s]" />
              <span className="w-2 h-2 rounded-full bg-ink animate-bounce [animation-delay:-0.1s]" />
              <span className="w-2 h-2 rounded-full bg-ink animate-bounce" />
            </div>
            <span className="font-mono text-[11px] uppercase tracking-widest text-ink/80">
              generating…
            </span>
          </div>
        ) : (
          <div className="absolute inset-0 flex items-center justify-center text-5xl sm:text-6xl select-none">
            {visual.emoji}
          </div>
        )}
      </div>

      <div className="mt-3 flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="font-mono text-[10px] uppercase tracking-widest text-ink/50 mb-0.5">
            prompt
          </div>
          <div className="font-mono text-xs sm:text-sm text-ink truncate">
            {state.prompt}
          </div>
        </div>
        <button
          type="button"
          disabled={generating}
          className="shrink-0 font-mono text-xs font-bold bg-ink text-cream border-2 border-ink rounded-full px-3 py-1.5 hover:bg-[#15803d] hover:text-ink transition-colors disabled:opacity-40 disabled:hover:bg-ink disabled:hover:text-cream"
        >
          {generating ? "…" : "send →"}
        </button>
      </div>
    </div>
  );
}
