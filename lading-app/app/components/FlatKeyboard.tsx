"use client";

import { useCallback, useRef } from "react";
import type { KbdLayout } from "./Scene";

const QWERTY_ROWS: string[][] = [
  "qwertyuiop".split(""),
  "asdfghjkl".split(""),
  "zxcvbnm".split(""),
];
const NUMPAD_ROWS: string[][] = [
  "1234567890".split(""),
  "-:;()$&@*\"".split(""),
  ".,?!'".split(""),
];

type Props = {
  layout: KbdLayout;
  onKeyTap: (id: string) => void;
  onClearLine?: () => void;
};

// iOS / Android style flat keyboard. The 3D R3F Scene is desktop-only — this
// component takes its place on phones, where 3D keys are too small and fiddly.
export default function FlatKeyboard({ layout, onKeyTap, onClearLine }: Props) {
  const rows = layout === "qwerty" ? QWERTY_ROWS : NUMPAD_ROWS;

  const handle = useCallback(
    (id: string) => () => onKeyTap(id),
    [onKeyTap],
  );

  // The middle and last letter rows of qwerty get padded so they look
  // staggered like a real keyboard. Numpad rows are even-width.
  const rowPadding = (ri: number): string => {
    if (layout !== "qwerty") return ri === 2 ? "px-[18%]" : "";
    if (ri === 1) return "px-[5%]"; // asdfghjkl
    if (ri === 2) return "px-[12%]"; // zxcvbnm with extra side padding
    return "";
  };

  return (
    <div className="w-full select-none touch-manipulation flex flex-col gap-1.5 sm:gap-2 px-2 sm:px-3 py-3 bg-[#d6d0c2] rounded-2xl border-2 border-ink shadow-[6px_6px_0_0_var(--ink)]">
      {rows.map((row, ri) => (
        <div key={ri} className={`flex gap-1.5 ${rowPadding(ri)}`}>
          {row.map((c) => {
            const id = layout === "qwerty" ? c : "n_" + c;
            const display = layout === "qwerty" ? c.toUpperCase() : c;
            return <FlatKey key={id} onPress={handle(id)} label={display} flex={1} />;
          })}
          {/* row 2 in qwerty gets a backspace shortcut on the right edge to
              mimic real keyboards. Optional — leaving for now, falls under
              the bottom row. */}
        </div>
      ))}

      {/* bottom row: 123/ABC · shift / #+= · space · / · ⌫ */}
      <div className="flex gap-1.5">
        <FlatKey
          onPress={handle("numpad")}
          label={layout === "qwerty" ? "123" : "ABC"}
          flex={1.4}
          mod
        />
        <FlatKey
          onPress={handle("shift")}
          label={layout === "qwerty" ? "⇧" : "#+="}
          flex={1.2}
          mod
        />
        <FlatKey onPress={handle("space")} label="space" flex={4} mod />
        <FlatKey onPress={handle("slash")} label="/" flex={1.0} accent />
        <FlatKey
          onPress={handle("backspace")}
          onLongPress={onClearLine}
          label="⌫"
          flex={1.4}
          mod
        />
      </div>
    </div>
  );
}

function FlatKey({
  onPress,
  onLongPress,
  label,
  flex,
  mod = false,
  accent = false,
}: {
  onPress: () => void;
  onLongPress?: () => void;
  label: string;
  flex: number;
  mod?: boolean;
  accent?: boolean;
}) {
  const cls = accent
    ? "bg-lime text-cream"
    : mod
    ? "bg-[#a9a392] text-cream"
    : "bg-white text-ink";

  const longPressTimer = useRef<number | null>(null);
  const longPressFired = useRef<boolean>(false);

  // Two-layer press feedback so it works for short taps AND held presses:
  //   1. `:active` Tailwind utilities → instant lime while finger is down
  //   2. `.flat-key-flash` class → applied on pointerdown via direct DOM mutation,
  //      removed 220ms after pointerup. Bypasses React state, lands on the same
  //      paint frame as the tap.
  const flashOn = (el: HTMLButtonElement) => {
    el.classList.add("flat-key-flash");
  };
  const flashOffSoon = (el: HTMLButtonElement) => {
    window.setTimeout(() => el.classList.remove("flat-key-flash"), 220);
  };

  const startLongPress = (el: HTMLButtonElement) => {
    if (!onLongPress) return;
    longPressFired.current = false;
    if (longPressTimer.current !== null) {
      window.clearTimeout(longPressTimer.current);
    }
    longPressTimer.current = window.setTimeout(() => {
      longPressFired.current = true;
      longPressTimer.current = null;
      el.classList.add("flat-key-flash-strong");
      window.setTimeout(() => el.classList.remove("flat-key-flash-strong"), 320);
      onLongPress();
    }, 500);
  };
  const cancelLongPress = () => {
    if (longPressTimer.current !== null) {
      window.clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  };

  return (
    <button
      type="button"
      onPointerDown={(e) => {
        flashOn(e.currentTarget);
        startLongPress(e.currentTarget);
      }}
      onPointerUp={(e) => {
        flashOffSoon(e.currentTarget);
        cancelLongPress();
      }}
      onPointerCancel={(e) => {
        flashOffSoon(e.currentTarget);
        cancelLongPress();
      }}
      onPointerLeave={(e) => {
        flashOffSoon(e.currentTarget);
        cancelLongPress();
      }}
      onClick={() => {
        // long-press already fired — swallow the click so we don't also delete a single char
        if (longPressFired.current) {
          longPressFired.current = false;
          return;
        }
        onPress();
      }}
      className={`flat-key ${cls} font-mono text-base sm:text-lg font-semibold rounded-lg border border-ink/20 shadow-[0_2px_0_0_rgba(12,12,11,0.55)] active:bg-lime active:text-cream active:translate-y-[2px] active:scale-[0.96] active:shadow-none py-3 sm:py-3.5 px-2`}
      style={{ flexGrow: flex, flexBasis: 0 }}
    >
      {label}
    </button>
  );
}
