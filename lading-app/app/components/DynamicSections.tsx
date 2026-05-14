"use client";

import { COMMANDS, COMMAND_ORDER, type Accent, type CommandContent, type CommandId } from "../commands";
import { useActiveContent, useCommandActivity } from "./CommandActivity";

const FALLBACK_MARQUEE = [
  "/cap a golden retriever as a samurai",
  "/split 1500 with 3 (dinner)",
  "/notion save this article to reading list",
  "/slack #engineering prod is back ✓",
  "/sticker a turtle saying 'on my way'",
  "/edit remove the trash can on the left",
  "/avatar studio ghibli style",
  "/scene my dog in a wes anderson hotel",
  "/meme this is fine, about prod on a friday",
  "/split 36000 with 3 (rent)",
  "/notion idea: weekly habit recap email",
  "/slack #standup did x, doing y",
];

// Old → new pastel mapping. We keep the Accent token in commands.ts unchanged
// and remap to the turtle palette here.
//   pink   → coral    (sunset)
//   lime   → seafoam  (shallow water)
//   blue   → sky      (ocean surface)
//   orange → sand     (beach)
//   ink    → dusk     (twilight)
const CTA_BG: Record<Accent, string> = {
  pink: "bg-coral",
  lime: "bg-seafoam",
  blue: "bg-sky",
  orange: "bg-sand",
  ink: "bg-dusk",
};
const CTA_FG: Record<Accent, string> = {
  pink: "text-ink",
  lime: "text-ink",
  blue: "text-ink",
  orange: "text-ink",
  ink: "text-ink",
};

export function DynamicCta() {
  const c = useActiveContent();
  const cta = c?.cta ?? { lead: "One slash. ", hi: "Anything", tail: ". In any app." };
  const accent = c?.accent ?? "lime";
  const bg = CTA_BG[accent];
  const fg = CTA_FG[accent];

  return (
    <div
      key={c?.cmd ?? "default"}
      className={`section-swap rounded-[2rem] border-2 border-ink ${bg} ${fg} p-10 md:p-16 text-center relative overflow-hidden`}
    >
      <div className="absolute -top-10 -left-10 w-40 h-40 rounded-full bg-pink border-2 border-ink wobble" />
      <div className="absolute -bottom-12 -right-8 w-32 h-32 rounded-full bg-blue border-2 border-ink float-y" />
      <div className="relative">
        <div className="font-mono text-xs uppercase tracking-widest mb-4">
          {c ? `§ ${c.cmd} · join the alpha` : "§ join the alpha"}
        </div>
        <h2 className="font-sans font-black tracking-[-0.04em] leading-[0.9] text-[clamp(2.6rem,7vw,6rem)] max-w-4xl mx-auto">
          {cta.lead}
          <span className="outline-text">{cta.hi}</span>
          {cta.tail}
        </h2>
        <form className="mt-10 flex flex-col sm:flex-row gap-3 max-w-xl mx-auto">
          <input
            type="email"
            placeholder="you@somewhere.cool"
            className="flex-1 bg-cream text-ink border-2 border-ink rounded-full px-5 py-4 font-mono text-base focus:outline-none focus:bg-white"
          />
          <button
            type="submit"
            className="bg-ink text-cream font-mono font-bold px-6 py-4 rounded-full border-2 border-ink hover:bg-pink hover:text-cream transition-colors"
          >
            grab my spot →
          </button>
        </form>
        <div className="mt-6 font-mono text-xs opacity-70">
          ~3,200 people in line · android alpha rolling out monthly
        </div>
      </div>
    </div>
  );
}

export function DynamicMarquee() {
  const c = useActiveContent();
  const items = c
    ? c.useCases.map((u) => u.example)
    : FALLBACK_MARQUEE;

  return (
    <div
      key={c?.cmd ?? "default"}
      className="relative bg-ink text-foam py-3 sm:py-3.5 md:py-4 overflow-hidden"
    >
      <div className="flex animate-marquee whitespace-nowrap">
        {[...items, ...items].map((cmd, i) => {
          const head = cmd.split(" ")[0];
          const rest = cmd.split(" ").slice(1).join(" ");
          return (
            <span
              key={i}
              className="font-mono text-sm sm:text-base md:text-xl lg:text-2xl mx-4 sm:mx-6 md:mx-8 inline-flex items-center gap-2 sm:gap-3 md:gap-4"
            >
              <span className="text-foam font-semibold">{head}</span>
              {rest && <span className="text-foam/70">{rest}</span>}
              <span className="text-coral-mid">✺</span>
            </span>
          );
        })}
      </div>
    </div>
  );
}

const ACCENT_BG: Record<Accent, string> = {
  pink: "bg-coral",
  lime: "bg-seafoam",
  blue: "bg-sky",
  orange: "bg-sand",
  ink: "bg-dusk",
};
const ACCENT_FG: Record<Accent, string> = {
  pink: "text-ink",
  lime: "text-ink",
  blue: "text-ink",
  orange: "text-ink",
  ink: "text-ink",
};
// Highlighter — used for headline word emphasis. We want this to stand out
// more than the soft tints, so it uses the saturated mid hue + white text.
const ACCENT_HI: Record<Accent, string> = {
  pink: "bg-coral-mid text-white",
  lime: "bg-seafoam-mid text-white",
  blue: "bg-sky-mid text-white",
  orange: "bg-sand-mid text-white",
  ink: "bg-dusk-mid text-white",
};
// Per-command radial gradient — used on CommandPickCard tiles so each command
// reads as its own little pastel scene, not a flat color block.
const ACCENT_GRAD: Record<Accent, string> = {
  pink: "bg-gradient-to-br from-coral via-rose to-foam",
  lime: "bg-gradient-to-br from-seafoam via-mint to-foam",
  blue: "bg-gradient-to-br from-sky via-foam to-lilac",
  orange: "bg-gradient-to-br from-sand via-coral to-foam",
  ink: "bg-gradient-to-br from-dusk via-lilac to-foam",
};

function CommandSwitcher({
  activeCmd,
  reasonHint,
}: {
  activeCmd: CommandId | null;
  reasonHint?: string;
}) {
  const { injectCommand, setActive } = useCommandActivity();
  return (
    <div className="flex flex-wrap items-center gap-2 mb-6">
      {reasonHint && (
        <span className="font-mono text-[10px] uppercase tracking-widest text-ink/40 mr-1">
          {reasonHint}
        </span>
      )}
      {COMMAND_ORDER.map((cmd) => {
        const c = COMMANDS[cmd];
        const isActive = activeCmd === cmd;
        return (
          <button
            key={cmd}
            type="button"
            onClick={() => injectCommand(cmd)}
            aria-pressed={isActive}
            className={`font-mono text-xs sm:text-sm px-3 py-1.5 rounded-full border-2 border-ink transition-all ${
              isActive
                ? `${ACCENT_BG[c.accent]} ${ACCENT_FG[c.accent]} shadow-[2px_2px_0_0_var(--ink)]`
                : "bg-cream text-ink hover:-translate-y-0.5 hover:shadow-[2px_2px_0_0_var(--ink)]"
            }`}
          >
            <span className="font-bold">{cmd}</span>
          </button>
        );
      })}
      {activeCmd && (
        <button
          type="button"
          onClick={() => setActive(null)}
          className="font-mono text-xs px-3 py-1.5 rounded-full border-2 border-ink bg-cream text-ink/60 hover:text-ink"
          title="Show all commands"
        >
          ← all
        </button>
      )}
    </div>
  );
}

// ───────────────────────────────────────────────────── PROBLEM
export function DynamicProblem() {
  const c = useActiveContent();

  // default state (no command picked yet)
  if (!c) {
    return (
      <section className="mx-auto max-w-[1400px] px-6 py-20 sm:py-24" id="problem">
        <CommandSwitcher activeCmd={null} reasonHint="pick one to see why it exists →" />
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-end">
          <div className="lg:col-span-7">
            <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-4">
              § every slash, the same idea
            </div>
            <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.2rem,5vw,4.4rem)]">
              The thing you wanted to send is{" "}
              <span className="bg-pink text-cream px-2 -rotate-1 inline-block">
                seven taps away
              </span>
              . The moment is one.
            </h2>
          </div>
          <div className="lg:col-span-5">
            <p className="text-lg text-ink/80 leading-relaxed">
              Every Turtle command exists to delete the same 7-step detour: stop typing, leave the app, do the thing in another app, screenshot, switch back, attach, hope the moment hasn't passed. It always has.
            </p>
            <p className="mt-4 font-mono text-sm text-ink/60">
              Tap a command above. The page below will explain that one in detail.
            </p>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section
      key={c.cmd}
      className="section-swap mx-auto max-w-[1400px] px-6 py-20 sm:py-24"
      id="problem"
    >
      <CommandSwitcher activeCmd={c.cmd} reasonHint="exploring →" />
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-10 items-end">
        <div className="lg:col-span-7">
          <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-4">
            {c.problem.eyebrow}
          </div>
          <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.2rem,5vw,4.4rem)]">
            {c.problem.headlineLead}
            <span className={`${ACCENT_HI[c.accent]} px-2 -rotate-1 inline-block`}>
              {c.problem.headlineHi}
            </span>
            {c.problem.headlineTail}
          </h2>
        </div>
        <div className="lg:col-span-5">
          <p className="text-lg text-ink/80 leading-relaxed">{c.problem.body}</p>
        </div>
      </div>

      {/* WITHOUT TURTLE — clearly framed pain ladder */}
      <div className="mt-14 rounded-3xl border-2 border-ink bg-cream p-6 sm:p-8 grain">
        <div className="flex items-center gap-2 mb-5">
          <span className="font-mono text-[11px] sm:text-xs uppercase tracking-widest bg-pink text-cream px-3 py-1 rounded-full border-2 border-ink">
            without turtle
          </span>
          <span className="font-mono text-[11px] sm:text-xs uppercase tracking-widest text-ink/50">
            today's friction ↓
          </span>
        </div>
        <div
          className="grid gap-2 font-mono text-xs"
          style={{
            gridTemplateColumns: `repeat(${c.problem.oldSteps.length}, minmax(0, 1fr))`,
          }}
        >
          {c.problem.oldSteps.map((s, i) => (
            <div
              key={`${s}-${i}`}
              className="border-2 border-ink rounded-full px-3 py-2 text-center bg-cream truncate text-ink/70"
              title={s}
            >
              <span className="text-pink font-bold mr-1">×</span>
              {s}
            </div>
          ))}
        </div>
      </div>

      {/* TRANSITION — pulls the eye from pain → fix */}
      <div className="mt-6 flex flex-col items-center gap-2 text-ink/40">
        <span className="font-mono text-3xl leading-none animate-bounce-soft">↓</span>
      </div>

      {/* WITH TURTLE — the answer, visually distinct */}
      <div
        className={`mt-6 rounded-3xl border-2 border-ink ${ACCENT_BG[c.accent]} ${ACCENT_FG[c.accent]} p-6 sm:p-8 shadow-[6px_6px_0_0_var(--ink)]`}
      >
        <div className="flex items-center gap-2 mb-3">
          <span className="font-mono text-[11px] sm:text-xs uppercase tracking-widest bg-cream text-ink px-3 py-1 rounded-full border-2 border-ink">
            with turtle
          </span>
          <span className="font-mono text-[11px] sm:text-xs uppercase tracking-widest opacity-70">
            {c.cmd} replaces all of that with ↓
          </span>
        </div>
        <p className="font-sans font-black tracking-tight text-xl sm:text-2xl leading-snug">
          {c.problem.fix}
        </p>
      </div>
    </section>
  );
}

// ───────────────────────────────────────────────────── USE CASES / SPOTLIGHT
export function DynamicUseCases() {
  const c = useActiveContent();

  if (!c) {
    return (
      <section
        id="commands"
        className="band-reef grain relative overflow-hidden"
      >
        {/* soft blur orbs for depth */}
        <div className="blob blob-md drift" style={{ background: "var(--mint)",  top: "8%",  left: "-80px" }} />
        <div className="blob blob-md float-y" style={{ background: "var(--sand)", bottom: "12%", right: "-100px" }} />

        <div className="relative mx-auto max-w-[1400px] px-6 py-20 sm:py-28">
          <div className="font-mono text-xs uppercase tracking-widest text-ink/55 mb-3">
            § the commands
          </div>
          <h2 className="font-sans font-semibold tracking-[-0.03em] leading-[0.95] text-[clamp(2.2rem,5vw,4.4rem)] max-w-4xl text-ink">
            Six image commands. Three live integrations.{" "}
            <span className="bg-sky-mid text-white px-3 py-1 rounded-xl inline-block">
              Tap one
            </span>{" "}
            to see how it's used.
          </h2>
          <p className="mt-6 max-w-2xl text-lg text-ink/75">
            Pick any command — the rest of this page will rewrite itself around that one's real-world use cases. Splitting a bill in GPay works nothing like generating a samurai cat. Each one gets its own story.
          </p>

          <div className="mt-12 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
            {COMMAND_ORDER.map((cmd, i) => (
              <CommandPickCard key={cmd} cmd={cmd} index={i} />
            ))}
          </div>

          <div className="mt-10 flex flex-wrap items-center gap-3 font-mono text-sm text-ink/75">
            <span className="text-ink/55">on the roadmap:</span>
            {["/fix", "/tone", "/reply", "/tl", "/sum", "/jared (your custom)"].map((t) => (
              <span
                key={t}
                className="rounded-full px-3 py-1.5 bg-white/55 backdrop-blur-sm hairline"
              >
                {t}
              </span>
            ))}
          </div>
        </div>
      </section>
    );
  }

  return (
    <section
      key={c.cmd}
      id="commands"
      className="section-swap mx-auto max-w-[1400px] px-6 py-20 sm:py-24"
    >
      <CommandSwitcher activeCmd={c.cmd} reasonHint="six ways to use →" />
      <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
        § /{c.cmd.slice(1)} · use cases
      </div>
      <h2 className="font-sans font-black tracking-[-0.03em] leading-[0.95] text-[clamp(2.2rem,5vw,4.4rem)] max-w-4xl">
        Six ways to use{" "}
        <span className={`${ACCENT_HI[c.accent]} px-2 -rotate-1 inline-block font-mono`}>
          {c.cmd}
        </span>
        .
      </h2>
      <p className="mt-5 max-w-2xl text-lg text-ink/80">
        Each example below is one literal line you'd type, no app switching, no setup. Tap any to see the prompt; the keyboard above is already loaded with {c.cmd}.
      </p>

      <div className="mt-12 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
        {c.useCases.map((u, i) => (
          <UseCaseCard key={u.title} useCase={u} accent={c.accent} index={i} />
        ))}
      </div>
    </section>
  );
}

function CommandPickCard({ cmd, index }: { cmd: CommandId; index: number }) {
  const { injectCommand } = useCommandActivity();
  const c = COMMANDS[cmd];
  return (
    <button
      type="button"
      onClick={() => injectCommand(cmd)}
      className={`relative text-left ${ACCENT_GRAD[c.accent]} text-ink rounded-3xl hairline-light p-6 sm:p-7 min-h-[210px] flex flex-col justify-between overflow-hidden transition-all hover:-translate-y-1 hover:shadow-[0_20px_50px_-20px_rgba(26,26,34,0.35)] grain grain-soft`}
    >
      <div className="flex items-start justify-between relative">
        <span className="font-mono font-semibold text-3xl md:text-4xl tracking-tight">
          {c.cmd}
        </span>
        <span className="font-mono text-[10px] uppercase tracking-widest rounded-full px-2 py-0.5 bg-white/55 backdrop-blur-sm hairline">
          v1
        </span>
      </div>
      <div className="relative">
        <div className="font-mono text-xs text-ink/55 mb-1.5">
          no.{String(index + 1).padStart(2, "0")} · tap to explore
        </div>
        <p className="text-base leading-snug text-ink/85">{c.hint}.</p>
      </div>
    </button>
  );
}

function UseCaseCard({
  useCase,
  accent,
  index,
}: {
  useCase: { title: string; example: string; emoji: string };
  accent: Accent;
  index: number;
}) {
  const swatch = ACCENT_BG[accent];
  return (
    <div className="bg-cream rounded-3xl border-2 border-ink p-6 sm:p-7 relative flex flex-col gap-4 min-h-[210px] hover:-translate-y-0.5 hover:shadow-[4px_4px_0_0_var(--ink)] transition-transform">
      <div className="flex items-start justify-between gap-3">
        <div className={`w-12 h-12 rounded-2xl border-2 border-ink ${swatch} flex items-center justify-center text-2xl`}>
          {useCase.emoji}
        </div>
        <span className="font-mono text-[10px] uppercase tracking-widest text-ink/40">
          no.{String(index + 1).padStart(2, "0")}
        </span>
      </div>
      <div>
        <h3 className="font-sans font-black text-xl tracking-tight leading-tight mb-2">
          {useCase.title}
        </h3>
        <code className="block font-mono text-xs sm:text-sm bg-ink text-cream px-3 py-2 rounded-lg break-words">
          {useCase.example}
        </code>
      </div>
    </div>
  );
}

// ───────────────────────────────────────────────────── CHAT MOCK
export function DynamicMock() {
  const c = useActiveContent();
  const display: CommandContent = c ?? COMMANDS["/cap"];

  return (
    <section
      key={display.cmd}
      className="section-swap mx-auto max-w-[1400px] px-6 pb-20 sm:pb-24"
    >
      <div className="rounded-[2rem] border-2 border-ink bg-cream p-6 md:p-10 grain">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-10 items-center">
          <div>
            <div className="font-mono text-xs uppercase tracking-widest text-ink/60 mb-3">
              § /{display.cmd.slice(1)} · in the wild
            </div>
            <h3 className="font-sans font-black text-3xl md:text-5xl leading-[0.95] tracking-[-0.02em]">
              {display.mock.spotlightHeadlineLead}
              <span className="line-through decoration-pink decoration-[4px]">
                {display.mock.spotlightHeadlineHi}
              </span>
              {display.mock.spotlightHeadlineTail}
            </h3>
            <p className="mt-5 text-ink/80 max-w-md leading-relaxed">
              {display.mock.insight}
            </p>
            {!c && (
              <p className="mt-4 font-mono text-xs text-ink/50">
                showing /cap as a default — pick another command above to swap this scene.
              </p>
            )}
          </div>

          {/* mock device */}
          <div className="relative mx-auto w-full max-w-sm">
            <div className="rounded-[2.5rem] border-2 border-ink bg-cream p-3 shadow-[10px_10px_0_0_var(--ink)]">
              <div className="rounded-[2rem] border-2 border-ink bg-white overflow-hidden">
                {/* messages */}
                <div className="p-4 bg-[#e9e2d2] space-y-3 min-h-[320px] font-sans text-sm">
                  <div className="flex">
                    <div className="bg-white border border-ink/10 rounded-2xl rounded-bl-sm px-3 py-2 max-w-[75%]">
                      {display.mock.incoming}
                    </div>
                  </div>
                  <div className="flex justify-end">
                    <div className={`${ACCENT_BG[display.accent]} ${ACCENT_FG[display.accent]} border-2 border-ink rounded-2xl rounded-br-sm px-3 py-2 max-w-[80%] font-mono text-xs`}>
                      {display.mock.typedExample}
                      <span className="caret">▍</span>
                    </div>
                  </div>
                  <div className="flex justify-end">
                    <MockResultBubble content={display} />
                  </div>
                  <div className="flex">
                    <div className="bg-white border border-ink/10 rounded-2xl rounded-bl-sm px-3 py-2 max-w-[75%]">
                      {display.mock.reaction}
                    </div>
                  </div>
                </div>
                {/* mini keyboard */}
                <div className="bg-[#d6d0c2] p-2 border-t-2 border-ink">
                  <div className="flex gap-1.5 mb-1.5 overflow-hidden">
                    {pickMiniKeyboardChips(display.cmd).map((cmd) => {
                      const cc = COMMANDS[cmd];
                      const isActive = cmd === display.cmd;
                      return (
                        <div
                          key={cmd}
                          className={`flex-1 ${isActive ? `${ACCENT_BG[cc.accent]} ${ACCENT_FG[cc.accent]}` : "bg-cream text-ink/60"} border-2 border-ink rounded-md py-2 font-mono text-[10px] text-center`}
                        >
                          {cmd}
                        </div>
                      );
                    })}
                  </div>
                  <div className="grid grid-cols-10 gap-1">
                    {"qwertyuiopasdfghjkl_zxcvbnm__".split("").map((k, i) => (
                      <div
                        key={i}
                        className={`h-6 rounded bg-white border border-ink/30 flex items-center justify-center text-[10px] font-mono ${
                          k === "_" ? "opacity-0" : ""
                        }`}
                      >
                        {k}
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function pickMiniKeyboardChips(active: CommandId): CommandId[] {
  // group commands so the chip strip in the mock looks contextual
  const PRODUCTIVITY: CommandId[] = ["/split", "/notion", "/slack", "/cap"];
  const IMAGE: CommandId[] = ["/cap", "/sticker", "/edit", "/avatar"];
  if (active === "/split" || active === "/notion" || active === "/slack") {
    return PRODUCTIVITY;
  }
  return IMAGE;
}

function MockResultBubble({ content }: { content: CommandContent }) {
  if (content.kind === "image") {
    return (
      <div className="bg-pink text-cream border-2 border-ink rounded-2xl rounded-br-sm p-2 max-w-[70%]">
        <div
          className={`aspect-square w-44 rounded-xl bg-gradient-to-br ${content.mock.resultGradient} grain border border-ink/30 flex items-center justify-center text-3xl`}
        >
          {content.mock.resultEmoji}
        </div>
        <div className="font-mono text-[10px] mt-1 opacity-90">
          generated · flux schnell
        </div>
      </div>
    );
  }

  if (content.kind === "split") {
    const sample = content.splitSample ?? { total: "₹1,500", people: 3, each: "₹500" };
    return (
      <div className={`${ACCENT_BG[content.accent]} ${ACCENT_FG[content.accent]} border-2 border-ink rounded-2xl rounded-br-sm p-3 max-w-[80%] w-52`}>
        <div className="font-mono text-[10px] uppercase tracking-widest opacity-80 mb-1">
          split panel · gpay
        </div>
        <div className="flex items-baseline justify-between">
          <span className="font-mono text-[10px] uppercase opacity-70">total</span>
          <span className="font-sans font-black text-xl tracking-tight">{sample.total}</span>
        </div>
        <div className="mt-1 flex items-center gap-1">
          {Array.from({ length: sample.people }).map((_, i) => (
            <span
              key={i}
              className="w-5 h-5 rounded-full border-2 border-ink bg-cream/90 text-ink text-[10px] font-mono flex items-center justify-center"
            >
              {i + 1}
            </span>
          ))}
        </div>
        <div className="mt-2 pt-2 border-t-2 border-current/30 flex items-baseline justify-between">
          <span className="font-mono text-[10px] uppercase opacity-70">each</span>
          <span className="font-sans font-black text-2xl tracking-tight">{sample.each}</span>
        </div>
        <div className="font-mono text-[10px] mt-2 opacity-80">
          saved · your google sheet
        </div>
      </div>
    );
  }

  // send
  const target = content.sendTarget ?? "Notion";
  return (
    <div className={`${ACCENT_BG[content.accent]} ${ACCENT_FG[content.accent]} border-2 border-ink rounded-2xl rounded-br-sm p-3 max-w-[80%] w-52`}>
      <div className="flex items-center gap-2 mb-2">
        <span className="w-7 h-7 rounded-full border-2 border-current flex items-center justify-center text-sm font-bold">
          ✓
        </span>
        <div>
          <div className="font-sans font-black text-base tracking-tight leading-tight">
            sent to {target}
          </div>
          <div className="font-mono text-[9px] uppercase tracking-widest opacity-80">
            permalink ready
          </div>
        </div>
      </div>
      <div className="font-mono text-[10px] opacity-80 break-all bg-current/10 rounded px-2 py-1.5">
        {target.toLowerCase()}.{target === "Slack" ? "com/archives/C123/p169…" : "so/Reading-list-49a…"}
      </div>
      <div className="font-mono text-[10px] mt-1.5 opacity-70">
        tap notification → opens in {target}
      </div>
    </div>
  );
}
