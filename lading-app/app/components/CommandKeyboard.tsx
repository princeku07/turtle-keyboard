/**
 * A phone keyboard with Turtle's slash-command palette open above the keys —
 * the way commands actually appear when you type "/". Used to show command
 * usage on the download / marketing pages. Server-rendered; only the composer
 * caret blinks (`.caret` in globals.css).
 */

const KEY_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];

export type PaletteCommand = { cmd: string; desc: string };

const DEFAULT_COMMANDS: PaletteCommand[] = [
  { cmd: "/poll", desc: "live poll in any chat" },
  { cmd: "/quiz", desc: "trivia + live scoreboard" },
  { cmd: "/cap", desc: "image from a prompt" },
  { cmd: "/summarize", desc: "tl;dr a long thread" },
  { cmd: "/github", desc: "check a PR in place" },
  { cmd: "/notion", desc: "pull from your workspace" },
];

export default function CommandKeyboard({
  commands = DEFAULT_COMMANDS,
  typed = "poll",
}: {
  commands?: PaletteCommand[];
  typed?: string;
}) {
  return (
    <div className="mx-auto w-full max-w-[360px]">
      {/* the keyboard tray — a slab that reads as the bottom of a phone */}
      <div className="overflow-hidden rounded-[2rem] bg-[#cdd3ca] p-3 shadow-[0_30px_60px_-30px_rgba(24,32,58,0.5)] ring-1 ring-black/10">
        {/* composer — the slash being typed */}
        <div className="mb-2.5 flex items-center gap-2 rounded-full bg-white px-3 py-2">
          <span className="font-mono text-[13px] font-semibold leading-none text-iris">/{typed}</span>
          <span className="caret font-mono text-[12px] leading-none text-iris">▍</span>
          <span className="ml-auto grid h-6 w-6 place-items-center rounded-full bg-iris text-[11px] text-white">↑</span>
        </div>

        {/* the slash palette, docked above the keys */}
        <div className="sea-glass rounded-2xl p-1.5">
          <div className="flex items-center justify-between px-2 pb-1 pt-0.5">
            <span className="font-mono text-[9px] font-semibold uppercase tracking-[0.18em] text-slate">
              🐢 turtle · slash commands
            </span>
            <span className="text-[10px] text-slate">↗</span>
          </div>
          <ul>
            {commands.map((c, i) => (
              <li
                key={c.cmd}
                className={`flex items-baseline gap-2 rounded-xl px-2 py-[6px] ${
                  i === 0 ? "border border-iris/30 bg-iris/10" : ""
                }`}
              >
                <span
                  className={`font-mono text-[12px] font-semibold ${
                    i === 0 ? "text-iris-deep" : "text-navy/80"
                  }`}
                >
                  {c.cmd}
                </span>
                <span className="truncate text-[10px] text-slate">{c.desc}</span>
              </li>
            ))}
          </ul>
        </div>

        {/* QWERTY keys — the phone keyboard layout */}
        <div className="mt-2.5 space-y-[5px]">
          {KEY_ROWS.map((row, r) => (
            <div key={row} className={`flex justify-center gap-[5px] ${r === 1 ? "px-3.5" : ""}`}>
              {r === 2 && <KeyCap label="⇧" wide />}
              {row.split("").map((k) => (
                <KeyCap key={k} label={k.toUpperCase()} />
              ))}
              {r === 2 && <KeyCap label="⌫" wide />}
            </div>
          ))}
          <div className="flex gap-[5px]">
            <KeyCap label="123" wide dark />
            <KeyCap label="/" accent />
            <KeyCap label="space" space dark />
            <KeyCap label="return" wide dark />
          </div>
        </div>
      </div>

      <p className="mt-4 text-center font-mono text-[11px] text-slate/80">
        type <span className="font-semibold text-iris">/</span> in any app — the palette opens above your keys
      </p>
    </div>
  );
}

function KeyCap({
  label,
  wide = false,
  space = false,
  dark = false,
  accent = false,
}: {
  label: string;
  wide?: boolean;
  space?: boolean;
  dark?: boolean;
  accent?: boolean;
}) {
  return (
    <span
      className={`grid h-8 place-items-center rounded-[6px] text-[11px] font-medium shadow-[0_1px_0_rgba(24,32,58,0.28)] ${
        space ? "flex-[4]" : wide ? "flex-[1.5]" : "flex-1"
      } ${
        accent
          ? "bg-iris font-mono font-semibold text-white"
          : dark
            ? "bg-[#a9b0a6] text-navy"
            : "bg-white text-navy"
      }`}
    >
      {label}
    </span>
  );
}
