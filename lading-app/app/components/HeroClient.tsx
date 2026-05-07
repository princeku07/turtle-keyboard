"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import SceneWrapper from "./SceneWrapper";
import FlatKeyboard from "./FlatKeyboard";
import type { KbdLayout, PressMap } from "./Scene";
import { useCommandActivity } from "./CommandActivity";
import { COMMANDS, COMMAND_ORDER, type CommandContent, type CommandId } from "../commands";

const PRESS_ACCENT = "#15803d";

type Visual = { gradient: string; emoji: string };

const PROMPT_VISUALS: Array<{ match: RegExp } & Visual> = [
  { match: /samurai|katana|sword|warrior/i, gradient: "from-orange via-pink to-ink", emoji: "🐱⚔️" },
  { match: /retriever|golden|dog|puppy/i,   gradient: "from-orange via-cream to-pink", emoji: "🐕" },
  { match: /cyber|punk|neon|future|robot/i, gradient: "from-blue via-pink to-orange", emoji: "🤖" },
  { match: /cat|kitten|kitty/i,             gradient: "from-pink via-orange to-blue", emoji: "🐈" },
  { match: /coffee|latte|cup/i,             gradient: "from-orange via-pink to-cream", emoji: "☕" },
  { match: /pixel|8.?bit|retro/i,           gradient: "from-blue via-ink to-pink", emoji: "👾" },
  { match: /sticker|cute|kawaii/i,          gradient: "from-pink via-cream to-blue", emoji: "✨" },
  { match: /space|moon|star|galaxy/i,       gradient: "from-ink via-blue to-pink", emoji: "🌌" },
  { match: /food|burger|pizza|sushi/i,      gradient: "from-orange via-pink to-cream", emoji: "🍔" },
  { match: /bird|owl|eagle/i,               gradient: "from-blue via-cream to-orange", emoji: "🦉" },
  { match: /anime|ghibli|manga/i,           gradient: "from-pink via-cream to-blue", emoji: "🌸" },
  { match: /oil|baroque|portrait|painting/i,gradient: "from-orange via-ink to-pink", emoji: "🖼️" },
  { match: /wes anderson|hotel|lobby/i,     gradient: "from-pink via-orange to-cream", emoji: "🏨" },
  { match: /3d|pixar|render/i,              gradient: "from-blue via-pink to-cream", emoji: "🎬" },
];

const DEFAULT_VISUAL: Visual = { gradient: "from-blue via-pink to-orange", emoji: "🎨" };

function pickVisual(prompt: string): Visual {
  for (const v of PROMPT_VISUALS) if (v.match.test(prompt)) return v;
  return DEFAULT_VISUAL;
}

type Mode =
  | { kind: "free"; buf: string }
  | { kind: "command"; command: CommandId; prompt: string };

const COMMAND_TRIGGER_RX = new RegExp(
  "^/(" + COMMAND_ORDER.map((c) => c.slice(1)).join("|") + ")$",
  "i",
);

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

export default function HeroClient() {
  const pressedRef = useRef<PressMap>(new Map());
  const [mode, setMode] = useState<Mode>({ kind: "free", buf: "" });
  const [isMobile, setIsMobile] = useState(false);
  const [hasInteracted, setHasInteracted] = useState(false);
  const [kbdLayout, setKbdLayout] = useState<KbdLayout>("qwerty");
  const [genState, setGenState] = useState<
    | { kind: "idle" }
    | { kind: "generating"; prompt: string }
    | { kind: "done"; prompt: string; visual: Visual; ms: number }
  >({ kind: "idle" });

  const { setActive, registerInjectListener } = useCommandActivity();

  useEffect(() => {
    const mq = window.matchMedia("(max-width: 640px)");
    const update = () => setIsMobile(mq.matches);
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, []);

  // Sync the active command up to context whenever the user enters/leaves command mode
  useEffect(() => {
    setActive(mode.kind === "command" ? mode.command : null);
  }, [mode, setActive]);

  // Auto-switch to the numpad layout for commands that primarily take numbers
  useEffect(() => {
    if (mode.kind === "command" && COMMANDS[mode.command].kind === "split") {
      setKbdLayout("numpad");
    } else {
      setKbdLayout("qwerty");
    }
  }, [mode]);

  const press = useCallback(
    (id: string, ch: string | null, mirrorTyped: boolean) => {
      pressedRef.current.set(id, { at: performance.now(), accent: PRESS_ACCENT });

      if (typeof navigator !== "undefined" && "vibrate" in navigator) {
        try {
          navigator.vibrate(8);
        } catch {}
      }

      if (mirrorTyped) setHasInteracted(true);
      if (!mirrorTyped) return;

      setMode((m) => {
        if (m.kind === "free") {
          if (id === "backspace") return { kind: "free", buf: m.buf.slice(0, -1) };
          if (ch === null) return m;
          if (m.buf.length === 0 && ch !== "/") return m;
          const next = (m.buf + ch).slice(-48);
          const trig = next.match(COMMAND_TRIGGER_RX);
          if (trig) {
            const cmd = ("/" + trig[1].toLowerCase()) as CommandId;
            return { kind: "command", command: cmd, prompt: "" };
          }
          return { kind: "free", buf: next };
        }
        if (id === "backspace") {
          if (m.prompt.length === 0) {
            return { kind: "free", buf: m.command.slice(0, -1) };
          }
          return { ...m, prompt: m.prompt.slice(0, -1) };
        }
        if (ch === null) return m;
        if (m.prompt.length === 0 && ch === " ") return m;
        return { ...m, prompt: (m.prompt + ch).slice(-64) };
      });
    },
    [],
  );

  const handleKeyTap = useCallback(
    (id: string) => {
      if (id === "numpad") {
        setKbdLayout((m) => (m === "qwerty" ? "numpad" : "qwerty"));
      }
      const ch =
        id === "space"
          ? " "
          : id === "slash"
          ? "/"
          : id === "backspace" || id === "shift" || id === "numpad"
          ? null
          : id.startsWith("n_")
          ? id.slice(2)
          : id;
      press(id, ch, true);
    },
    [press],
  );

  // Inject a command from outside (e.g. tapping a command chip elsewhere on the page)
  const injectFromChip = useCallback((cmd: CommandId) => {
    setHasInteracted(true);
    setMode({ kind: "command", command: cmd, prompt: "" });
    if (typeof window !== "undefined") {
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  }, []);

  useEffect(() => {
    registerInjectListener(injectFromChip);
    return () => registerInjectListener(null);
  }, [injectFromChip, registerInjectListener]);

  const handleChipTap = useCallback(
    (cmd: CommandId) => {
      setHasInteracted(true);
      setMode({ kind: "command", command: cmd, prompt: "" });
    },
    [],
  );

  // Tapping the suggestion pill above the keyboard fills the prompt with the
  // canonical example for the active command, kicking off the demo result.
  const useExampleNow = useCallback(() => {
    setHasInteracted(true);
    setMode((m) => {
      if (m.kind !== "command") return m;
      return { ...m, prompt: COMMANDS[m.command].hero.typed };
    });
  }, []);

  // Long-press the backspace key → clear the current line (prompt, or the free buffer).
  const clearLine = useCallback(() => {
    setHasInteracted(true);
    setMode((m) => {
      if (m.kind === "command") return { ...m, prompt: "" };
      return { kind: "free", buf: "" };
    });
    if (typeof navigator !== "undefined" && "vibrate" in navigator) {
      try {
        navigator.vibrate([12, 30, 12]);
      } catch {}
    }
  }, []);

  const inCommand = mode.kind === "command";
  const commandLabel = inCommand ? mode.command : null;
  const commandPrompt = inCommand ? mode.prompt : "";
  const activeContent = inCommand ? COMMANDS[mode.command] : null;

  // Generate when prompt has content. /split is local math — no fake delay.
  useEffect(() => {
    if (!inCommand || commandPrompt.trim().length < 2) {
      setGenState({ kind: "idle" });
      return;
    }
    const prompt = commandPrompt.trim();
    if (activeContent?.kind === "split") {
      setGenState({ kind: "done", prompt, visual: pickVisual(prompt), ms: 0 });
      return;
    }
    setGenState({ kind: "generating", prompt });
    const startedAt = performance.now();
    const t = window.setTimeout(() => {
      const ms = performance.now() - startedAt;
      setGenState({ kind: "done", prompt, visual: pickVisual(prompt), ms });
    }, 1300 + Math.random() * 300);
    return () => window.clearTimeout(t);
  }, [inCommand, commandLabel, commandPrompt, activeContent?.kind]);

  // (Auto-typer removed — placeholder hint shows instead until user types.)

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

      if (!otherInputFocused && (k === " " || k === "backspace" || k === "/")) {
        e.preventDefault();
      }

      press(id, ch, !otherInputFocused);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [press]);

  return (
    <section className="relative flex flex-col min-h-[calc(100dvh-64px)] md:min-h-[700px]">
      {/* TOP — badge + headline + subhead (auto height, no preview here) */}
      <div className="mx-auto max-w-[1100px] w-full px-5 sm:px-6 pt-10 sm:pt-14 text-center shrink-0">
        <div className="inline-flex items-center gap-2 font-mono text-[10px] sm:text-xs uppercase tracking-widest bg-ink text-cream px-3 py-1.5 rounded-full mb-6 sm:mb-8 self-center">
          <span className="w-2 h-2 rounded-full bg-lime animate-pulse" />
          {activeContent
            ? activeContent.kind === "image"
              ? `${activeContent.cmd} · in your chat`
              : activeContent.kind === "split"
              ? `${activeContent.cmd} · inside any payment app`
              : `${activeContent.cmd} · fire-and-forget`
            : "now in closed alpha · android first"}
        </div>

        {/* Headline */}
        <h1 className="font-sans font-black tracking-[-0.035em] leading-[1.0] text-[clamp(1.7rem,5vw,3.6rem)] min-h-[1.05em] break-words">
          {mode.kind === "command" ? (
            <span className="inline-flex items-baseline flex-wrap justify-center gap-x-2 gap-y-1">
              <span
                className={`font-mono ${ACCENT_BG[COMMANDS[mode.command].accent]} ${ACCENT_FG[COMMANDS[mode.command].accent]} px-2 py-0.5 rounded-md`}
              >
                {mode.command}
              </span>
              <span className="font-mono text-[0.5em] sm:text-[0.45em] tracking-normal font-medium text-ink/80 inline-flex items-baseline">
                {mode.prompt ? (
                  <span>{mode.prompt}</span>
                ) : (
                  <span className="italic text-ink/30">
                    {COMMANDS[mode.command].hero.placeholder}
                  </span>
                )}
                <span className="caret text-ink/60 ml-0.5">▍</span>
              </span>
            </span>
          ) : mode.buf ? (
            <span className="font-mono inline-flex items-baseline">
              <span className="break-all">{mode.buf}</span>
              <span className="caret ml-1">▍</span>
            </span>
            ) : (
              <span>
                One{" "}
                <span className="font-mono bg-lime text-cream px-2 py-0.5 rounded-md">
                  /
                </span>
                . Generate, split, send — inside any app.
              </span>
            )}
        </h1>

        {activeContent && (
          <p className="mt-3 sm:mt-4 max-w-xl mx-auto text-sm sm:text-base leading-relaxed text-ink/70">
            {activeContent.hero.subhead}
          </p>
        )}
      </div>

      {/* MIDDLE — GenCard sits at the bottom of this zone (just above the chip strip)
           when active, IdleHint centers when nothing's selected */}
      <div className="relative flex-1 min-h-0 flex flex-col items-center justify-end px-4 py-4 gap-3">
        {genState.kind !== "idle" ? (
          <>
            <GenCard state={genState} content={activeContent} />
            {activeContent && <ScrollDownHint cmdLabel={activeContent.cmd} />}
          </>
        ) : activeContent ? (
          <ScrollDownHint cmdLabel={activeContent.cmd} />
        ) : (
          <div className="my-auto">
            <IdleHint />
          </div>
        )}
      </div>

      {/* BOTTOM — hint strip + keyboard */}
      <div className="w-full shrink-0">
        <CommandHintStrip
          activeCmd={activeContent?.cmd ?? null}
          onTap={handleChipTap}
        />
        {activeContent && !commandPrompt && (
          <CommandSuggestion
            cmdLabel={activeContent.cmd}
            example={activeContent.hero.typed}
            onTap={useExampleNow}
          />
        )}
        <div className="mx-auto w-full px-3 sm:px-6 pb-3 sm:pb-4 flex justify-center">
          {isMobile ? (
            <div className="w-full max-w-[520px]">
              <FlatKeyboard
                layout={kbdLayout}
                onKeyTap={handleKeyTap}
                onClearLine={clearLine}
              />
            </div>
          ) : (
            <div className="relative w-full max-w-[780px] aspect-[2.5]">
              <SceneWrapper
                pressedRef={pressedRef}
                flat={false}
                onKeyTap={handleKeyTap}
                layout={kbdLayout}
              />
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

function IdleHint() {
  return (
    <div className="flex flex-col items-center gap-2 text-center">
      <div className="font-mono text-[10px] sm:text-xs uppercase tracking-widest text-ink/45">
        9 slash commands · pick one
      </div>
      <span className="font-mono text-4xl sm:text-5xl text-ink/30 leading-none animate-bounce-soft">
        ↓
      </span>
    </div>
  );
}

function ScrollDownHint({ cmdLabel }: { cmdLabel: CommandId | null }) {
  return (
    <button
      type="button"
      onClick={() => {
        if (typeof document === "undefined") return;
        const target = document.getElementById("problem");
        if (target) target.scrollIntoView({ behavior: "smooth", block: "start" });
      }}
      className="scroll-hint-pulse group inline-flex items-center gap-2 font-mono text-[11px] sm:text-xs bg-ink text-cream px-3 py-2 rounded-full border-2 border-ink hover:bg-lime hover:text-ink transition-colors"
    >
      <span>
        {cmdLabel ? `see how ${cmdLabel} is used` : "see use cases"}
      </span>
      <span className="inline-block animate-bounce-soft group-hover:translate-y-0.5 transition-transform">
        ↓
      </span>
    </button>
  );
}

function CommandSuggestion({
  cmdLabel,
  example,
  onTap,
}: {
  cmdLabel: CommandId;
  example: string;
  onTap: () => void;
}) {
  return (
    <div className="w-full flex justify-center px-3 pt-2 pb-1">
      <button
        type="button"
        onClick={onTap}
        className="group inline-flex items-center gap-2 sm:gap-3 font-mono bg-cream border-2 border-ink rounded-full pl-2 pr-3 py-1.5 shadow-[3px_3px_0_0_var(--ink)] hover:-translate-y-0.5 hover:shadow-[5px_5px_0_0_var(--ink)] active:translate-y-0 active:shadow-[2px_2px_0_0_var(--ink)] transition-all"
      >
        <span className="font-mono text-[10px] uppercase tracking-widest bg-ink text-cream px-2 py-0.5 rounded-full">
          try this
        </span>
        <span className="font-bold text-sm sm:text-base">{cmdLabel}</span>
        <span className="text-ink/70 text-sm sm:text-base truncate max-w-[40vw] sm:max-w-[400px]">
          {example}
        </span>
        <span className="font-mono text-base text-ink/50 group-hover:text-ink transition-colors">
          ↵
        </span>
      </button>
    </div>
  );
}

function CommandHintStrip({
  activeCmd,
  onTap,
}: {
  activeCmd: CommandId | null;
  onTap: (cmd: CommandId) => void;
}) {
  return (
    <div className="border-t-2 border-ink bg-cream/95 backdrop-blur-sm w-full overflow-visible">
      <div className="w-full px-2 sm:px-4 pt-4 sm:pt-5 pb-2.5 sm:pb-3">
        <div className="flex items-center justify-start md:justify-center gap-1.5 sm:gap-2 overflow-x-auto overflow-y-visible no-scrollbar py-1">
          <span className="font-mono text-[10px] uppercase tracking-widest text-ink/40 px-1 shrink-0 hidden sm:inline">
            try:
          </span>
          {COMMAND_ORDER.map((cmd) => {
            const c = COMMANDS[cmd];
            const isActive = activeCmd === cmd;
            return (
              <button
                key={cmd}
                type="button"
                onClick={() => onTap(cmd)}
                aria-pressed={isActive}
                className={`shrink-0 inline-flex items-center gap-1.5 font-mono text-xs sm:text-sm px-3 py-1.5 rounded-full border-2 border-ink transition-all ${
                  isActive
                    ? `${ACCENT_BG[c.accent]} ${ACCENT_FG[c.accent]} shadow-[2px_2px_0_0_var(--ink)] -translate-y-0.5`
                    : "bg-cream text-ink hover:-translate-y-0.5 hover:shadow-[2px_2px_0_0_var(--ink)]"
                }`}
              >
                <span className="font-bold">{cmd}</span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

type GenCardProps = {
  state:
    | { kind: "generating"; prompt: string }
    | { kind: "done"; prompt: string; visual: Visual; ms: number }
    | { kind: "idle" };
  content: CommandContent | null;
};

function GenCard({ state, content }: GenCardProps) {
  if (state.kind === "idle") return null;

  const generating = state.kind === "generating";
  const kind = content?.kind ?? "image";
  const cmdLabel = content?.cmd ?? "/cap";
  const accent = content?.accent ?? "pink";
  const meta =
    kind === "image"
      ? "flux schnell"
      : kind === "split"
      ? "google sheets · synced"
      : `${content?.sendTarget?.toLowerCase()} api · sent`;

  return (
    <div
      className="gen-card-pop relative w-full max-w-[240px] flex flex-col border-2 border-ink rounded-2xl bg-cream shadow-[6px_6px_0_0_var(--ink)] p-2.5"
      role="status"
      aria-live="polite"
    >
      <div className="flex items-center justify-between mb-2 shrink-0">
        <span className="font-mono text-[9px] uppercase tracking-widest bg-ink text-cream px-2 py-0.5 rounded-full">
          {content?.resultLabel ?? `${cmdLabel} result`}
        </span>
        <span className="font-mono text-[9px] text-ink/60 truncate ml-2">
          {generating ? meta + "…" : meta}
        </span>
      </div>

      {kind === "image" && (
        <ImageResult state={state} />
      )}
      {kind === "split" && (
        <SplitResult generating={generating} content={content!} prompt={state.prompt} />
      )}
      {kind === "send" && (
        <SendResult generating={generating} accent={accent} target={content?.sendTarget ?? "Notion"} />
      )}

      <div className="mt-2.5 flex items-start justify-between gap-3 shrink-0">
        <div className="min-w-0 flex-1 text-left">
          <div className="font-mono text-[9px] uppercase tracking-widest text-ink/50 mb-0.5">
            {kind === "image" ? "prompt" : kind === "split" ? "amount + people" : "message"}
          </div>
          <div className="font-mono text-xs text-ink truncate">{state.prompt}</div>
        </div>
        <button
          type="button"
          disabled={generating}
          className="shrink-0 font-mono text-xs font-bold bg-ink text-cream border-2 border-ink rounded-full px-3 py-1.5 hover:bg-lime hover:text-ink transition-colors disabled:opacity-40 disabled:hover:bg-ink disabled:hover:text-cream"
        >
          {generating ? "…" : kind === "image" ? "send →" : kind === "split" ? "split →" : "open ↗"}
        </button>
      </div>
    </div>
  );
}

function ImageResult({
  state,
}: {
  state:
    | { kind: "generating"; prompt: string }
    | { kind: "done"; prompt: string; visual: Visual; ms: number };
}) {
  const generating = state.kind === "generating";
  const visual = state.kind === "done" ? state.visual : DEFAULT_VISUAL;
  return (
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
          <span className="font-mono text-[10px] uppercase tracking-widest text-ink/80">
            generating…
          </span>
        </div>
      ) : (
        <div className="absolute inset-0 flex items-center justify-center text-5xl sm:text-6xl select-none">
          {visual.emoji}
        </div>
      )}
    </div>
  );
}

function parseSplit(prompt: string): { total: string; people: number; each: string } | null {
  if (!prompt || !prompt.trim()) return null;
  const cleaned = prompt.toLowerCase().replace(/,/g, "");
  // first number = amount, second = people
  const numbers = cleaned.match(/\d+(?:\.\d+)?/g);
  if (!numbers || numbers.length === 0) return null;
  const amount = parseFloat(numbers[0]);
  if (!isFinite(amount) || amount <= 0) return null;

  let people = 0;
  // prefer "with N" or "N people/friends/ppl/ways"
  const withMatch = cleaned.match(/with\s+(\d+)/);
  const peopleMatch = cleaned.match(/(\d+)\s*(?:people|friends|ppl|ways|persons?)/);
  if (withMatch) people = parseInt(withMatch[1], 10);
  else if (peopleMatch) people = parseInt(peopleMatch[1], 10);
  else if (numbers.length >= 2) people = parseInt(numbers[1], 10);

  if (!isFinite(people) || people < 2) people = 3;
  if (people > 20) people = 20;

  const each = Math.round(amount / people);
  const fmt = (n: number) => "₹" + n.toLocaleString("en-IN");
  return { total: fmt(amount), people, each: fmt(each) };
}

function SplitResult({
  generating,
  content,
  prompt,
}: {
  generating: boolean;
  content: CommandContent;
  prompt: string;
}) {
  const parsed = parseSplit(prompt);
  const fallback = content.splitSample ?? { total: "₹1,500", people: 3, each: "₹500" };
  const data = parsed ?? fallback;
  const peopleToRender = Math.min(data.people, 8);
  const overflow = data.people - peopleToRender;
  return (
    <div
      className={`relative w-full rounded-xl border-2 border-ink overflow-hidden ${ACCENT_BG[content.accent]} ${ACCENT_FG[content.accent]} p-3`}
    >
      <div className="flex items-baseline justify-between">
        <div className="font-mono text-[10px] uppercase tracking-widest opacity-80">total</div>
        <div className="font-sans font-black text-2xl tracking-tight">{data.total}</div>
      </div>
      <div className="mt-1 flex items-center flex-wrap gap-1">
        {Array.from({ length: peopleToRender }).map((_, i) => (
          <span
            key={i}
            className="w-5 h-5 rounded-full border-2 border-ink bg-cream/90 text-ink text-[10px] font-mono flex items-center justify-center"
          >
            {i + 1}
          </span>
        ))}
        {overflow > 0 && (
          <span className="font-mono text-[11px] opacity-80 ml-0.5">+{overflow}</span>
        )}
        <span className="font-mono text-[11px] opacity-80 ml-1">× {data.people}</span>
      </div>
      <div className="mt-2 pt-2 border-t-2 border-current/30 flex items-baseline justify-between">
        <div className="font-mono text-[10px] uppercase tracking-widest opacity-80">each</div>
        <div className="font-sans font-black text-3xl tracking-tight">{data.each}</div>
      </div>
      {generating && (
        <div className="absolute inset-0 flex items-center justify-center bg-cream/40 backdrop-blur-sm">
          <span className="font-mono text-[10px] uppercase tracking-widest text-ink/80">computing…</span>
        </div>
      )}
    </div>
  );
}

function SendResult({ generating, accent, target }: { generating: boolean; accent: string; target: string }) {
  return (
    <div className={`relative w-full rounded-xl border-2 border-ink overflow-hidden ${ACCENT_BG[accent]} ${ACCENT_FG[accent]} p-4 flex flex-col items-center text-center min-h-[120px] justify-center`}>
      {generating ? (
        <>
          <div className="flex gap-1.5 mb-2">
            <span className="w-2 h-2 rounded-full bg-current animate-bounce [animation-delay:-0.2s]" />
            <span className="w-2 h-2 rounded-full bg-current animate-bounce [animation-delay:-0.1s]" />
            <span className="w-2 h-2 rounded-full bg-current animate-bounce" />
          </div>
          <span className="font-mono text-[11px] uppercase tracking-widest opacity-80">
            sending to {target.toLowerCase()}…
          </span>
        </>
      ) : (
        <>
          <div className="w-12 h-12 rounded-full border-2 border-current flex items-center justify-center mb-2 text-2xl">
            ✓
          </div>
          <div className="font-sans font-black text-lg tracking-tight leading-tight">
            sent to {target}
          </div>
          <div className="font-mono text-[10px] uppercase tracking-widest opacity-80 mt-1">
            tap notification to open
          </div>
        </>
      )}
    </div>
  );
}
