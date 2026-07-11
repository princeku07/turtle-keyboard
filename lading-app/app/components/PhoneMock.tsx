/**
 * The hero device — a realistic iPhone (Dynamic Island) showing a live
 * WhatsApp thread with Turtle's slash palette open over the keyboard.
 * Static + server-rendered for a clean LCP; only the composer caret blinks.
 */

const PRED = ["I", "The", "I'm"];
const KEY_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];

export default function PhoneMock() {
  return (
    <div className="relative w-[290px] sm:w-[318px]">
      {/* soft grounding shadow on the sand */}
      <div
        aria-hidden
        className="absolute -bottom-6 left-1/2 h-10 w-[78%] -translate-x-1/2 rounded-[50%] bg-navy/20 blur-2xl"
      />

      {/* titanium frame */}
      <div className="relative rounded-[3rem] bg-[#1d1f24] p-[11px] shadow-[0_40px_80px_-32px_rgba(24,32,58,0.55)] ring-1 ring-black/20">
        <div className="relative overflow-hidden rounded-[2.4rem] bg-[#e9efe6]">
          {/* status bar + dynamic island */}
          <div className="relative flex items-center justify-between bg-[#f3f6ef] px-6 pb-1.5 pt-2.5 text-[11px] font-semibold text-navy">
            <span>9:41</span>
            <span className="absolute left-1/2 top-2 h-[22px] w-[74px] -translate-x-1/2 rounded-full bg-black" />
            <span className="flex items-center gap-1 text-[9px]">
              <span>▂▄▆█</span>
              <span>􀙇</span>
              <span className="tracking-tight">100%</span>
            </span>
          </div>

          {/* WhatsApp header */}
          <div className="flex items-center gap-2.5 bg-[#f3f6ef] px-3 pb-2 pt-1">
            <span className="text-[18px] leading-none text-[#2b7fff]">‹</span>
            <span className="grid h-8 w-8 place-items-center rounded-full bg-[#25D366] text-white">
              <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor" aria-hidden>
                <path d="M12 3a9 9 0 0 0-7.7 13.6L3 21l4.6-1.2A9 9 0 1 0 12 3Zm5 12.3c-.2.6-1.2 1.1-1.7 1.2-.5.1-1 .1-1.6-.1-.4-.1-.9-.3-1.5-.5-2.6-1.1-4.3-3.8-4.4-4-.1-.2-1-1.4-1-2.6s.6-1.8.9-2.1c.2-.2.4-.3.6-.3h.5c.2 0 .4 0 .6.5l.8 2c.1.1 0 .3-.1.4l-.4.5c-.1.1-.3.3-.1.6.1.3.7 1.1 1.4 1.8.9.8 1.7 1 2 1.2.2.1.4.1.6-.1l.8-1c.2-.2.3-.2.6-.1l1.8.9c.3.1.4.2.5.3.1.2.1.7-.1 1.3Z" />
              </svg>
            </span>
            <div className="leading-tight">
              <div className="text-[13px] font-semibold text-navy">WhatsApp</div>
              <div className="text-[9px] text-slate">weekend crew · 5 online</div>
            </div>
            <div className="ml-auto flex items-center gap-3 text-[#2b7fff]">
              <span>􀍉</span>
              <span>􀌾</span>
            </div>
          </div>

          {/* chat wallpaper */}
          <div className="relative min-h-[196px] bg-[#dce4d5] bg-[radial-gradient(rgba(255,255,255,0.5)_1px,transparent_1px)] [background-size:14px_14px] px-3 py-3">
            <div className="mx-auto mb-2 w-fit rounded-full bg-white/80 px-2.5 py-0.5 text-[9px] font-medium text-slate shadow-sm">
              Today
            </div>
            <div className="w-fit max-w-[86%] rounded-2xl rounded-tl-sm bg-white px-3 py-2 text-[12px] leading-snug text-navy shadow-[0_1px_1px_rgba(24,32,58,0.12)]">
              Need to decide: <span className="font-mono font-semibold text-iris">/poll</span>
              <br />
              Movies: Sci-fi or Comedy?
              <span className="ml-1.5 align-bottom text-[8px] text-slate">9:41 ✓✓</span>
            </div>

            {/* the slash palette, floating over the keyboard line */}
            <div className="absolute inset-x-4 bottom-1">
              <div className="sea-glass rounded-2xl p-2">
                <div className="flex items-center justify-between px-1 pb-1.5">
                  <span className="flex items-center gap-1.5 text-[10px] font-semibold text-navy">
                    <span className="text-slate">☰</span> Custom
                  </span>
                  <span className="text-[10px] text-slate">↗</span>
                </div>
                <div className="flex items-center gap-2 rounded-xl bg-white/80 px-2 py-1.5">
                  <span className="text-[11px]">⚡</span>
                  <span className="h-3.5 w-px bg-iris" />
                  <span className="font-mono text-[10px] text-slate">poll Sci-fi or Comedy?</span>
                </div>
                <div className="mt-1 flex items-center gap-2 rounded-xl px-2 py-1">
                  <span className="text-[11px]">👆</span>
                  <span className="h-4 w-8 rounded-full bg-[#25D366]/90" />
                  <span className="text-[9px] text-slate">tap to send the widget</span>
                </div>
              </div>
            </div>
          </div>

          {/* composer */}
          <div className="flex items-center gap-2 bg-[#f3f6ef] px-3 py-1.5">
            <span className="grid h-6 w-6 place-items-center rounded-full text-[15px] text-slate">+</span>
            <div className="flex flex-1 items-center rounded-full bg-white px-3 py-1.5 text-[11px] text-slate">
              <span className="font-mono font-semibold text-iris">/</span>
              <span className="caret ml-0.5 text-iris">▍</span>
            </div>
            <span className="text-[15px] text-slate">🎤</span>
          </div>

          {/* iOS keyboard */}
          <div className="space-y-[5px] bg-[#cdd3ca] px-1.5 pb-4 pt-2">
            <div className="flex justify-around px-1 pb-0.5 text-[11px] font-medium text-navy">
              {PRED.map((p, i) => (
                <span key={p} className={`flex-1 text-center ${i === 1 ? "border-x border-navy/15" : ""}`}>
                  {p}
                </span>
              ))}
            </div>
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
              <KeyCap label="🌐" dark />
              <KeyCap label="space" space dark />
              <KeyCap label="return" wide dark />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function KeyCap({
  label,
  wide = false,
  space = false,
  dark = false,
}: {
  label: string;
  wide?: boolean;
  space?: boolean;
  dark?: boolean;
}) {
  return (
    <span
      className={`grid h-8 place-items-center rounded-[6px] text-[11px] font-medium shadow-[0_1px_0_rgba(24,32,58,0.28)] ${
        space ? "flex-[4]" : wide ? "flex-[1.5]" : "flex-1"
      } ${dark ? "bg-[#a9b0a6] text-navy" : "bg-white text-navy"}`}
    >
      {label}
    </span>
  );
}
