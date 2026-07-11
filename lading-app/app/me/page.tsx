import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "your turtle journey — install to mind-blown",
  description:
    "From the install screen to slash-command AI to multiplayer puzzles with friends. See exactly how Turtle works, step by step.",
};

const steps = [
  { n: "01", label: "install" },
  { n: "02", label: "enable" },
  { n: "03", label: "type" },
  { n: "04", label: "slash" },
  { n: "05", label: "under the hood" },
  { n: "06", label: "games" },
  { n: "07", label: "your data" },
];

export default function MePage() {
  return (
    <main className="min-h-screen w-full text-foam overflow-x-clip relative">
      <Nav />
      <Hero />
      <StepInstall />
      <StepEnable />
      <StepType />
      <StepSlash />
      <StepUnderTheHood />
      <StepGames />
      <StepPrivacy />
      <Outro />
      <PageStyles />
    </main>
  );
}

function Nav() {
  return (
    <header className="sticky top-0 z-40 backdrop-blur-md">
      <div className="mx-auto max-w-[1400px] px-4 sm:px-6 py-3 sm:py-4 flex items-center justify-between gap-3">
        <Link
          href="/"
          className="flex items-center gap-2 font-sans font-semibold text-base sm:text-lg shrink-0 tracking-tight text-foam"
        >
          <span className="text-xl sm:text-2xl leading-none">🐢</span>
          turtle
        </Link>
        <nav className="hidden md:flex items-center gap-6 font-mono text-xs text-foam/55">
          {steps.map((s) => (
            <a
              key={s.n}
              href={`#step-${s.n}`}
              className="hover:text-foam transition-colors"
            >
              {s.n} {s.label}
            </a>
          ))}
        </nav>
        <Link
          href="/#waitlist"
          className="font-mono text-xs sm:text-sm font-semibold bg-foam text-ink px-3 sm:px-4 py-2 rounded-full hover:bg-cyan transition-colors whitespace-nowrap"
        >
          join →
        </Link>
      </div>
    </header>
  );
}

function Hero() {
  return (
    <section className="relative">
      <div className="reef-overlay" />
      <div className="mx-auto max-w-[1100px] px-5 sm:px-6 pt-20 sm:pt-28 pb-16 sm:pb-24 relative">
        <p className="font-mono text-xs sm:text-sm text-cyan/80 mb-5 tracking-wide">
          /me — your turtle journey
        </p>
        <h1 className="font-sans font-semibold tracking-[-0.04em] leading-[0.9] text-[clamp(2.6rem,7vw,5.5rem)] text-foam">
          install. type a slash.
          <br />
          <span className="text-cyan">be a wizard.</span>
        </h1>
        <p className="mt-7 max-w-2xl font-mono text-sm sm:text-base text-foam/65 leading-relaxed">
          seven beats from a stranger&apos;s home screen to multiplayer puzzles
          with a friend. no marketing fluff — the actual data path, drawn out.
        </p>
        <div className="mt-12 flex flex-wrap gap-2 font-mono text-[11px] text-foam/45">
          {steps.map((s, i) => (
            <a
              key={s.n}
              href={`#step-${s.n}`}
              className="flex items-center gap-2 px-3 py-1.5 rounded-full hairline hover:border-cyan/40 hover:text-foam transition-colors"
            >
              <span className="text-cyan/70">{s.n}</span>
              <span>{s.label}</span>
              {i < steps.length - 1 && (
                <span className="text-foam/20">·</span>
              )}
            </a>
          ))}
        </div>
      </div>
    </section>
  );
}

function StepShell({
  n,
  title,
  kicker,
  body,
  diagram,
  flip = false,
}: {
  n: string;
  title: React.ReactNode;
  kicker: string;
  body: React.ReactNode;
  diagram: React.ReactNode;
  flip?: boolean;
}) {
  return (
    <section id={`step-${n}`} className="relative scroll-mt-24">
      <div className="mx-auto max-w-[1200px] px-5 sm:px-6 py-20 sm:py-28">
        <div
          className={`grid md:grid-cols-2 gap-12 md:gap-16 items-center ${
            flip ? "md:[&>*:first-child]:order-2" : ""
          }`}
        >
          <div>
            <div className="flex items-center gap-3 mb-6 font-mono text-xs">
              <span className="px-2 py-1 rounded-full bg-cyan/10 text-cyan border border-cyan/30">
                step {n}
              </span>
              <span className="text-foam/45 tracking-wide">{kicker}</span>
            </div>
            <h2 className="font-sans font-semibold tracking-[-0.03em] leading-[1.0] text-[clamp(1.8rem,4vw,3rem)] text-foam">
              {title}
            </h2>
            <div className="mt-6 font-mono text-sm sm:text-[15px] text-foam/65 leading-relaxed space-y-4">
              {body}
            </div>
          </div>
          <div className="relative">
            <div className="glass rounded-2xl p-6 sm:p-8">{diagram}</div>
          </div>
        </div>
      </div>
    </section>
  );
}

function StepInstall() {
  return (
    <StepShell
      n="01"
      kicker="from any app store"
      title={<>find it. tap install. wait nine seconds.</>}
      body={
        <>
          <p>
            turtle ships on play store and (soon) app store. 12mb apk, zero
            third-party trackers, zero analytics sdks.
          </p>
          <p>
            the keyboard, the host app, the command engine — all bundled.
            nothing else gets pulled at install time.
          </p>
        </>
      }
      diagram={<PhoneInstallDiagram />}
    />
  );
}

function PhoneInstallDiagram() {
  return (
    <svg
      viewBox="0 0 320 360"
      className="w-full h-auto"
      role="img"
      aria-label="phone with turtle app icon installing"
    >
      <defs>
        <linearGradient id="phoneBezel" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="#0a1a2c" />
          <stop offset="1" stopColor="#040a14" />
        </linearGradient>
        <linearGradient id="screenBg" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0" stopColor="#1d5d72" stopOpacity="0.5" />
          <stop offset="1" stopColor="#0e2e44" stopOpacity="0.9" />
        </linearGradient>
      </defs>
      <rect
        x="80"
        y="20"
        width="160"
        height="320"
        rx="22"
        fill="url(#phoneBezel)"
        stroke="rgba(255,255,255,0.18)"
        strokeWidth="1.5"
      />
      <rect x="92" y="34" width="136" height="292" rx="14" fill="url(#screenBg)" />
      <circle cx="160" cy="30" r="3" fill="#040a14" />
      {Array.from({ length: 16 }).map((_, i) => {
        const col = i % 4;
        const row = Math.floor(i / 4);
        const isTurtle = i === 6;
        return (
          <g key={i} transform={`translate(${102 + col * 30}, ${56 + row * 50})`}>
            <rect
              width="22"
              height="22"
              rx="6"
              fill={isTurtle ? "#7ec5cc" : "rgba(255,255,255,0.08)"}
              stroke={isTurtle ? "#7ec5cc" : "rgba(255,255,255,0.1)"}
              className={isTurtle ? "me-icon-pop" : ""}
            />
            {isTurtle && (
              <text
                x="11"
                y="16"
                textAnchor="middle"
                fontSize="13"
                className="me-icon-pop"
              >
                🐢
              </text>
            )}
          </g>
        );
      })}
      <g transform="translate(110, 280)">
        <rect
          width="100"
          height="6"
          rx="3"
          fill="rgba(255,255,255,0.08)"
        />
        <rect
          width="100"
          height="6"
          rx="3"
          fill="#7ec5cc"
          className="me-install-bar"
          style={{ transformOrigin: "left center" }}
        />
        <text
          x="50"
          y="22"
          textAnchor="middle"
          fontSize="8"
          fill="#a89e8a"
          fontFamily="ui-monospace, monospace"
        >
          turtle keyboard
        </text>
      </g>
    </svg>
  );
}

function StepEnable() {
  return (
    <StepShell
      n="02"
      flip
      kicker="one-time setup"
      title={<>flip the switch in settings.</>}
      body={
        <>
          <p>
            android &amp; ios both gate input methods behind a system toggle —
            no app can sneak in. you tap once to enable, once more to switch.
          </p>
          <p>
            the host app deep-links you straight to the right settings screen,
            so you skip the menu hunt.
          </p>
        </>
      }
      diagram={<ToggleDiagram />}
    />
  );
}

function ToggleDiagram() {
  return (
    <div className="font-mono text-sm space-y-3">
      {[
        { label: "system keyboard", on: true, locked: true },
        { label: "voice (samsung)", on: false },
        { label: "turtle 🐢", on: true, highlight: true },
        { label: "swiftkey", on: false },
      ].map((row) => (
        <div
          key={row.label}
          className={`flex items-center justify-between rounded-xl px-4 py-3 transition-colors ${
            row.highlight
              ? "bg-cyan/8 border border-cyan/40"
              : "hairline"
          }`}
        >
          <span
            className={
              row.highlight ? "text-foam" : "text-foam/55"
            }
          >
            {row.label}
          </span>
          <div className="relative">
            <div
              className={`w-10 h-6 rounded-full transition-colors ${
                row.on ? "bg-cyan" : "bg-white/10"
              } ${row.highlight ? "me-toggle-pulse" : ""}`}
            />
            <div
              className={`absolute top-0.5 ${
                row.on ? "left-[18px]" : "left-0.5"
              } w-5 h-5 bg-ink rounded-full transition-all ${
                row.highlight ? "me-toggle-knob" : ""
              }`}
            />
          </div>
        </div>
      ))}
      <p className="text-[11px] text-foam/40 pt-3 leading-relaxed">
        ↳ host app calls{" "}
        <span className="text-cyan/80">showInputMethodPicker()</span> on android,
        opens{" "}
        <span className="text-cyan/80">openSettingsURLString</span> on ios.
      </p>
    </div>
  );
}

function StepType() {
  return (
    <StepShell
      n="03"
      kicker="zero surveillance"
      title={<>just type. nothing leaves your phone.</>}
      body={
        <>
          <p>
            no keylogging. no &quot;anonymous usage analytics&quot;. the
            keyboard never opens a socket unless you type a slash.
          </p>
          <p>
            the open-source repo (
            <a
              href="https://github.com/princeku07/turtle-keyboard"
              className="text-cyan hover:underline"
              target="_blank"
              rel="noreferrer"
            >
              github
            </a>
            ) lets anyone audit this — and the ci pipeline blocks any pr that
            adds a network call to the typing path.
          </p>
        </>
      }
      diagram={<KeyboardTypingDiagram />}
    />
  );
}

function KeyboardTypingDiagram() {
  return (
    <div className="space-y-4">
      <div className="rounded-xl hairline bg-white/[0.04] px-4 py-3 font-mono text-sm text-foam/85 min-h-[60px]">
        <span>hey mom, i&apos;ll be home for </span>
        <span className="me-cursor">|</span>
      </div>
      <svg viewBox="0 0 360 140" className="w-full">
        <rect
          width="360"
          height="140"
          rx="14"
          fill="rgba(255,255,255,0.04)"
          stroke="rgba(255,255,255,0.1)"
        />
        {"qwertyuiop".split("").map((k, i) => (
          <Key key={k} x={10 + i * 34} y={14} char={k} />
        ))}
        {"asdfghjkl".split("").map((k, i) => (
          <Key key={k} x={26 + i * 34} y={54} char={k} />
        ))}
        {"zxcvbnm".split("").map((k, i) => (
          <Key key={k} x={60 + i * 34} y={94} char={k} />
        ))}
      </svg>
      <div className="flex items-center gap-2 font-mono text-[11px] text-foam/55">
        <span className="me-net-dot bg-foam/30 w-1.5 h-1.5 rounded-full inline-block" />
        <span>network: idle · 0 bytes sent · 0 logged</span>
      </div>
    </div>
  );
}

function Key({ x, y, char }: { x: number; y: number; char: string }) {
  return (
    <g transform={`translate(${x}, ${y})`}>
      <rect
        width="28"
        height="32"
        rx="6"
        fill="rgba(255,255,255,0.06)"
        stroke="rgba(255,255,255,0.1)"
      />
      <text
        x="14"
        y="21"
        textAnchor="middle"
        fontSize="13"
        fill="#ece6d4"
        fontFamily="ui-monospace, monospace"
      >
        {char}
      </text>
    </g>
  );
}

function StepSlash() {
  return (
    <StepShell
      n="04"
      flip
      kicker="the magic word: /"
      title={
        <>
          type a slash. <span className="text-cyan">summon ai.</span>
        </>
      }
      body={
        <>
          <p>
            <span className="text-cyan/90">/cap</span> generates an image,{" "}
            <span className="text-cyan/90">/sticker</span> a transparent png,{" "}
            <span className="text-cyan/90">/edit</span> reworks the last photo
            you sent, <span className="text-cyan/90">/puzzle</span> drops a
            multiplayer game right into the chat.
          </p>
          <p>
            no &quot;generating…&quot; spinner you stare at for 30 seconds.
            target is ≤ 2s for images, ≤ 1.5s for text.
          </p>
        </>
      }
      diagram={<SlashCommandDiagram />}
    />
  );
}

function SlashCommandDiagram() {
  return (
    <div className="space-y-5">
      <div className="rounded-xl hairline bg-white/[0.04] px-4 py-4 font-mono text-sm text-foam/85 min-h-[60px] overflow-hidden">
        <span className="text-cyan">/cap </span>
        <span className="me-typewriter">a samurai cat at sunrise</span>
        <span className="me-cursor">|</span>
      </div>
      <svg viewBox="0 0 360 180" className="w-full">
        <defs>
          <marker
            id="arrowhead"
            markerWidth="10"
            markerHeight="10"
            refX="9"
            refY="3"
            orient="auto"
          >
            <path d="M0,0 L0,6 L9,3 z" fill="#7ec5cc" />
          </marker>
        </defs>
        <FlowNode x={20} y={70} w={90} label="keyboard" sub="/cap …" />
        <path
          d="M 110 86 Q 145 86 180 86"
          stroke="#7ec5cc"
          strokeWidth="1.5"
          strokeDasharray="4 4"
          fill="none"
          markerEnd="url(#arrowhead)"
          className="me-dash-flow"
        />
        <FlowNode x={180} y={70} w={90} label="router" sub="model-neutral" />
        <path
          d="M 270 86 Q 305 86 340 86"
          stroke="#7ec5cc"
          strokeWidth="1.5"
          strokeDasharray="4 4"
          fill="none"
          className="me-dash-flow me-dash-flow-delay-1"
        />
        <g transform="translate(310, 70)">
          <rect
            width="40"
            height="32"
            rx="6"
            fill="rgba(126,197,204,0.12)"
            stroke="#7ec5cc"
          />
          <text
            x="20"
            y="20"
            textAnchor="middle"
            fontSize="14"
            fill="#7ec5cc"
          >
            ⚡
          </text>
        </g>
        <path
          d="M 320 110 Q 320 140 200 145 Q 80 150 60 130"
          stroke="#7ec5cc"
          strokeWidth="1.5"
          strokeDasharray="4 4"
          fill="none"
          markerEnd="url(#arrowhead)"
          className="me-dash-flow me-dash-flow-delay-2"
        />
        <text
          x="180"
          y="170"
          textAnchor="middle"
          fontSize="10"
          fill="#a89e8a"
          fontFamily="ui-monospace, monospace"
        >
          png bytes back, committed inline
        </text>
      </svg>
    </div>
  );
}

function FlowNode({
  x,
  y,
  w,
  label,
  sub,
}: {
  x: number;
  y: number;
  w: number;
  label: string;
  sub: string;
}) {
  return (
    <g transform={`translate(${x}, ${y})`}>
      <rect
        width={w}
        height="32"
        rx="6"
        fill="rgba(255,255,255,0.06)"
        stroke="rgba(126,197,204,0.4)"
      />
      <text
        x={w / 2}
        y="13"
        textAnchor="middle"
        fontSize="11"
        fill="#ece6d4"
        fontFamily="ui-monospace, monospace"
      >
        {label}
      </text>
      <text
        x={w / 2}
        y="25"
        textAnchor="middle"
        fontSize="8"
        fill="#a89e8a"
        fontFamily="ui-monospace, monospace"
      >
        {sub}
      </text>
    </g>
  );
}

function StepUnderTheHood() {
  return (
    <StepShell
      n="05"
      kicker="the actual stack"
      title={<>here&apos;s the path your slash actually takes.</>}
      body={
        <>
          <p>
            integrations own their own dispatch — there&apos;s no monolithic ai
            client routing every command. /cap, /sticker, /puzzle each load
            their own prompts and call their own model.
          </p>
          <p>
            this is why adding a new command is a single folder, not a fork of
            the keyboard.
          </p>
        </>
      }
      diagram={<ArchitectureDiagram />}
    />
  );
}

function ArchitectureDiagram() {
  return (
    <svg viewBox="0 0 400 320" className="w-full">
      <defs>
        <marker
          id="arrowhead2"
          markerWidth="10"
          markerHeight="10"
          refX="9"
          refY="3"
          orient="auto"
        >
          <path d="M0,0 L0,6 L9,3 z" fill="#7ec5cc" />
        </marker>
      </defs>
      <ArchBox x={20} y={20} w={110} h={50} title="your text" sub="text field" />
      <ArchBox
        x={150}
        y={20}
        w={110}
        h={50}
        title="slash parser"
        sub="CommandComposer"
      />
      <ArchBox
        x={280}
        y={20}
        w={110}
        h={50}
        title="registry"
        sub="IntegrationRegistry"
      />

      <ArchBox
        x={150}
        y={110}
        w={110}
        h={50}
        title="integration"
        sub="/cap, /puzzle, …"
      />
      <ArchBox x={20} y={110} w={110} h={50} title="local prompts" sub="AssetPrompts" />
      <ArchBox
        x={280}
        y={110}
        w={110}
        h={50}
        title="ai client"
        sub="gemini · openai · local"
      />

      <ArchBox x={150} y={200} w={110} h={50} title="result" sub="bytes / text" />
      <ArchBox x={280} y={200} w={110} h={50} title="firebase" sub="games · history" />
      <ArchBox
        x={20}
        y={200}
        w={110}
        h={50}
        title="committed"
        sub="back to text field"
      />

      <Arrow d="M 130 45 L 150 45" />
      <Arrow d="M 260 45 L 280 45" />
      <Arrow d="M 335 70 L 335 110" />
      <Arrow d="M 280 135 L 260 135" />
      <Arrow d="M 130 135 L 150 135" />
      <Arrow d="M 205 70 L 205 110" />
      <Arrow d="M 335 160 L 335 200" />
      <Arrow d="M 205 160 L 205 200" />
      <Arrow d="M 150 225 L 130 225" />

      <text
        x="200"
        y="300"
        textAnchor="middle"
        fontSize="10"
        fill="#a89e8a"
        fontFamily="ui-monospace, monospace"
      >
        every box ships open-source on github
      </text>
    </svg>
  );
}

function ArchBox({
  x,
  y,
  w,
  h,
  title,
  sub,
}: {
  x: number;
  y: number;
  w: number;
  h: number;
  title: string;
  sub: string;
}) {
  return (
    <g transform={`translate(${x}, ${y})`}>
      <rect
        width={w}
        height={h}
        rx="8"
        fill="rgba(255,255,255,0.05)"
        stroke="rgba(126,197,204,0.35)"
        className="me-arch-box"
      />
      <text
        x={w / 2}
        y={h / 2 - 4}
        textAnchor="middle"
        fontSize="12"
        fill="#ece6d4"
        fontFamily="ui-sans-serif, system-ui"
        fontWeight="600"
      >
        {title}
      </text>
      <text
        x={w / 2}
        y={h / 2 + 12}
        textAnchor="middle"
        fontSize="9"
        fill="#a89e8a"
        fontFamily="ui-monospace, monospace"
      >
        {sub}
      </text>
    </g>
  );
}

function Arrow({ d }: { d: string }) {
  return (
    <path
      d={d}
      stroke="#7ec5cc"
      strokeWidth="1.2"
      strokeDasharray="3 3"
      fill="none"
      markerEnd="url(#arrowhead2)"
      opacity="0.7"
      className="me-arch-arrow"
    />
  );
}

function StepGames() {
  return (
    <StepShell
      n="06"
      flip
      kicker="multiplayer in a chat bubble"
      title={
        <>
          /puzzle. a friend taps the link.{" "}
          <span className="text-cyan">you&apos;re both playing.</span>
        </>
      }
      body={
        <>
          <p>
            you pick an image and a difficulty. it lands in{" "}
            <span className="text-cyan/80">your</span> google drive (15gb free,
            no central bucket), a tiny firestore doc tracks the game, and a
            shareable https link goes into the chat.
          </p>
          <p>
            your friend taps the link. a webview opens the puzzle —
            file-loaded from the app bundle, instant. tile drags sync over
            realtime db. no signup on their end.
          </p>
        </>
      }
      diagram={<PuzzleFlowDiagram />}
    />
  );
}

function PuzzleFlowDiagram() {
  return (
    <svg viewBox="0 0 400 320" className="w-full" role="img" aria-label="puzzle data flow">
      <defs>
        <marker
          id="arrowhead3"
          markerWidth="10"
          markerHeight="10"
          refX="9"
          refY="3"
          orient="auto"
        >
          <path d="M0,0 L0,6 L9,3 z" fill="#7ec5cc" />
        </marker>
      </defs>

      <g transform="translate(10, 10)">
        <rect width="90" height="140" rx="10" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.15)" />
        <text x="45" y="20" textAnchor="middle" fontSize="9" fill="#a89e8a" fontFamily="ui-monospace, monospace">you</text>
        <rect x="15" y="32" width="60" height="60" rx="6" fill="rgba(126,197,204,0.15)" stroke="#7ec5cc" />
        <text x="45" y="68" textAnchor="middle" fontSize="20">🖼️</text>
        <rect x="15" y="100" width="60" height="20" rx="4" fill="#7ec5cc" />
        <text x="45" y="114" textAnchor="middle" fontSize="9" fill="#08182a" fontFamily="ui-monospace, monospace" fontWeight="600">/puzzle</text>
      </g>

      <g transform="translate(160, 25)">
        <circle cx="40" cy="20" r="18" fill="rgba(126,197,204,0.12)" stroke="#7ec5cc" />
        <text x="40" y="25" textAnchor="middle" fontSize="14">📦</text>
        <text x="40" y="55" textAnchor="middle" fontSize="9" fill="#ece6d4" fontFamily="ui-monospace, monospace">your drive</text>
        <text x="40" y="67" textAnchor="middle" fontSize="8" fill="#a89e8a" fontFamily="ui-monospace, monospace">image upload</text>
      </g>

      <g transform="translate(160, 110)">
        <circle cx="40" cy="20" r="18" fill="rgba(126,197,204,0.12)" stroke="#7ec5cc" />
        <text x="40" y="25" textAnchor="middle" fontSize="14">🔥</text>
        <text x="40" y="55" textAnchor="middle" fontSize="9" fill="#ece6d4" fontFamily="ui-monospace, monospace">firestore</text>
        <text x="40" y="67" textAnchor="middle" fontSize="8" fill="#a89e8a" fontFamily="ui-monospace, monospace">games/&lt;id&gt;</text>
      </g>

      <g transform="translate(160, 195)">
        <circle cx="40" cy="20" r="18" fill="rgba(126,197,204,0.12)" stroke="#7ec5cc" />
        <text x="40" y="25" textAnchor="middle" fontSize="14">⚡</text>
        <text x="40" y="55" textAnchor="middle" fontSize="9" fill="#ece6d4" fontFamily="ui-monospace, monospace">rtdb</text>
        <text x="40" y="67" textAnchor="middle" fontSize="8" fill="#a89e8a" fontFamily="ui-monospace, monospace">tile sync</text>
      </g>

      <g transform="translate(300, 90)">
        <rect width="90" height="140" rx="10" fill="rgba(255,255,255,0.05)" stroke="rgba(255,255,255,0.15)" />
        <text x="45" y="20" textAnchor="middle" fontSize="9" fill="#a89e8a" fontFamily="ui-monospace, monospace">friend</text>
        <rect x="10" y="32" width="70" height="70" rx="6" fill="rgba(126,197,204,0.08)" stroke="rgba(126,197,204,0.4)" />
        <g transform="translate(20, 42)">
          {[0,1,2,3,4,5,6,7,8].map((i) => (
            <rect key={i} x={(i%3)*18} y={Math.floor(i/3)*18} width="16" height="16" rx="2"
              fill={i === 4 ? "transparent" : "rgba(126,197,204,0.25)"}
              stroke="rgba(126,197,204,0.5)" strokeWidth="0.5"
              className={`me-puzzle-tile me-puzzle-tile-${i % 4}`} />
          ))}
        </g>
        <text x="45" y="118" textAnchor="middle" fontSize="9" fill="#ece6d4" fontFamily="ui-monospace, monospace">webview</text>
      </g>

      <path d="M 100 60 Q 130 50 158 45" stroke="#7ec5cc" strokeWidth="1.2" strokeDasharray="3 3" fill="none" markerEnd="url(#arrowhead3)" className="me-dash-flow" />
      <path d="M 200 65 Q 200 90 200 108" stroke="#7ec5cc" strokeWidth="1.2" strokeDasharray="3 3" fill="none" markerEnd="url(#arrowhead3)" className="me-dash-flow me-dash-flow-delay-1" />
      <path d="M 240 130 Q 280 130 300 130" stroke="#7ec5cc" strokeWidth="1.2" strokeDasharray="3 3" fill="none" markerEnd="url(#arrowhead3)" className="me-dash-flow me-dash-flow-delay-2" />
      <path d="M 240 215 Q 280 215 305 200" stroke="#7ec5cc" strokeWidth="1.2" strokeDasharray="3 3" fill="none" markerEnd="url(#arrowhead3)" className="me-dash-flow me-dash-flow-delay-3" />
      <path d="M 305 195 Q 280 230 240 230" stroke="rgba(126,197,204,0.4)" strokeWidth="1.2" strokeDasharray="3 3" fill="none" markerEnd="url(#arrowhead3)" className="me-dash-flow me-dash-flow-delay-3" />

      <text x="200" y="300" textAnchor="middle" fontSize="10" fill="#a89e8a" fontFamily="ui-monospace, monospace">
        no server proxy. clients hit firebase directly.
      </text>
    </svg>
  );
}

function StepPrivacy() {
  return (
    <StepShell
      n="07"
      kicker="model-neutral, owner-neutral"
      title={
        <>
          your text. your drive. <span className="text-cyan">your call.</span>
        </>
      }
      body={
        <>
          <p>
            we don&apos;t lock you to gemini, openai, or any single provider.
            bring your own api key, point at a self-hosted model, or use the
            default — your choice, swappable from the host app.
          </p>
          <p>
            soon: bring your own mcp servers, bind any tool to any slash name.
            generic, not curated.
          </p>
        </>
      }
      diagram={<ProviderPaletteDiagram />}
    />
  );
}

function ProviderPaletteDiagram() {
  const providers = [
    { name: "gemini", note: "default" },
    { name: "openai", note: "byok" },
    { name: "anthropic", note: "byok" },
    { name: "lmstudio", note: "local" },
    { name: "ollama", note: "local" },
    { name: "any mcp", note: "soon" },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 font-mono text-xs">
      {providers.map((p, i) => (
        <div
          key={p.name}
          className="rounded-xl hairline px-4 py-3 flex items-center justify-between me-provider-fade"
          style={{ animationDelay: `${i * 0.12}s` }}
        >
          <span className="text-foam/85">{p.name}</span>
          <span className="text-cyan/70 text-[10px]">{p.note}</span>
        </div>
      ))}
      <div className="col-span-2 rounded-xl bg-cyan/8 border border-cyan/40 px-4 py-3 flex items-center justify-between mt-1">
        <span className="text-foam">your keyboard, your routing</span>
        <span className="text-cyan text-[10px]">↗</span>
      </div>
    </div>
  );
}

function Outro() {
  return (
    <section className="relative">
      <div className="mx-auto max-w-[900px] px-5 sm:px-6 py-28 sm:py-36 text-center">
        <h2 className="font-sans font-semibold tracking-[-0.04em] leading-[0.95] text-[clamp(2rem,5vw,3.5rem)] text-foam">
          that&apos;s the whole loop.
          <br />
          <span className="text-cyan">install · type · summon · share.</span>
        </h2>
        <p className="mt-6 font-mono text-sm text-foam/55 max-w-xl mx-auto leading-relaxed">
          no dashboards. no settings to babysit. nothing to learn after the
          first slash.
        </p>
        <div className="mt-10 flex flex-wrap justify-center gap-3">
          <Link
            href="/#waitlist"
            className="font-mono text-sm font-semibold bg-foam text-ink px-6 py-3 rounded-full hover:bg-cyan transition-colors"
          >
            join the waitlist →
          </Link>
          <a
            href="https://github.com/princeku07/turtle-keyboard"
            target="_blank"
            rel="noreferrer"
            className="font-mono text-sm hairline px-6 py-3 rounded-full hover:border-cyan/60 hover:text-foam transition-colors text-foam/75"
          >
            read the source ↗
          </a>
        </div>
      </div>
    </section>
  );
}

function PageStyles() {
  return (
    <style>{`
      @keyframes me-icon-pop {
        0%   { transform: scale(0); opacity: 0; }
        60%  { transform: scale(1.15); opacity: 1; }
        100% { transform: scale(1); opacity: 1; }
      }
      .me-icon-pop {
        transform-origin: center;
        transform-box: fill-box;
        animation: me-icon-pop 2.4s ease-in-out infinite;
      }

      @keyframes me-install-bar {
        0%, 100% { transform: scaleX(0); }
        80%      { transform: scaleX(1); }
      }
      .me-install-bar { animation: me-install-bar 2.4s ease-out infinite; }

      @keyframes me-toggle-pulse {
        0%, 100% { box-shadow: 0 0 0 0 rgba(126,197,204,0.0); }
        50%      { box-shadow: 0 0 0 6px rgba(126,197,204,0.18); }
      }
      .me-toggle-pulse { animation: me-toggle-pulse 2.2s ease-in-out infinite; }

      @keyframes me-toggle-knob {
        0%, 100% { transform: translateY(0); }
        50%      { transform: translateY(-1px); }
      }
      .me-toggle-knob { animation: me-toggle-knob 2.2s ease-in-out infinite; }

      @keyframes me-cursor {
        0%, 50%   { opacity: 1; }
        50.01%, 100% { opacity: 0; }
      }
      .me-cursor {
        display: inline-block;
        animation: me-cursor 1s steps(1) infinite;
        color: var(--cyan);
        margin-left: 1px;
      }

      @keyframes me-typewriter {
        0%   { width: 0; }
        50%  { width: 100%; }
        90%  { width: 100%; }
        100% { width: 0; }
      }
      .me-typewriter {
        display: inline-block;
        overflow: hidden;
        white-space: nowrap;
        vertical-align: bottom;
        animation: me-typewriter 5.5s steps(24) infinite;
      }

      @keyframes me-net-dot {
        0%, 100% { background: rgba(236,230,212,0.3); }
        50%      { background: rgba(126,197,204,0.6); }
      }
      .me-net-dot { animation: me-net-dot 3s ease-in-out infinite; }

      @keyframes me-dash-flow {
        0%   { stroke-dashoffset: 0; }
        100% { stroke-dashoffset: -32; }
      }
      .me-dash-flow { animation: me-dash-flow 1.4s linear infinite; }
      .me-dash-flow-delay-1 { animation-delay: 0.35s; }
      .me-dash-flow-delay-2 { animation-delay: 0.7s; }
      .me-dash-flow-delay-3 { animation-delay: 1.05s; }

      @keyframes me-arch-box {
        0%, 100% { stroke-opacity: 0.35; }
        50%      { stroke-opacity: 0.7; }
      }
      .me-arch-box { animation: me-arch-box 3.8s ease-in-out infinite; }
      .me-arch-arrow { animation: me-dash-flow 2s linear infinite; }

      @keyframes me-puzzle-tile {
        0%, 100% { transform: translate(0, 0); }
        50%      { transform: translate(2px, -1px); }
      }
      .me-puzzle-tile { transform-box: fill-box; transform-origin: center; }
      .me-puzzle-tile-0 { animation: me-puzzle-tile 3s ease-in-out infinite; }
      .me-puzzle-tile-1 { animation: me-puzzle-tile 3.4s ease-in-out 0.2s infinite; }
      .me-puzzle-tile-2 { animation: me-puzzle-tile 3.8s ease-in-out 0.4s infinite; }
      .me-puzzle-tile-3 { animation: me-puzzle-tile 4.2s ease-in-out 0.6s infinite; }

      @keyframes me-provider-fade {
        0%   { opacity: 0; transform: translateY(6px); }
        100% { opacity: 1; transform: translateY(0); }
      }
      .me-provider-fade {
        opacity: 0;
        animation: me-provider-fade 0.6s ease-out forwards;
      }

      @media (prefers-reduced-motion: reduce) {
        .me-icon-pop, .me-install-bar, .me-toggle-pulse, .me-toggle-knob,
        .me-cursor, .me-typewriter, .me-net-dot, .me-dash-flow,
        .me-arch-box, .me-arch-arrow, .me-puzzle-tile,
        .me-puzzle-tile-0, .me-puzzle-tile-1, .me-puzzle-tile-2, .me-puzzle-tile-3,
        .me-provider-fade {
          animation: none !important;
        }
        .me-provider-fade { opacity: 1; }
        .me-typewriter { width: 100%; }
      }
    `}</style>
  );
}
