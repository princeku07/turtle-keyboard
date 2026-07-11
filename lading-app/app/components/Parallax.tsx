"use client";

import { useEffect, useRef, type ReactNode } from "react";

/**
 * Layered parallax for the hero (the depth implied by the Gemini reference:
 * background vista → phone → floating cards, each on its own plane).
 *
 * One shared scroll + pointer listener drives every layer through a single
 * rAF loop — pointer motion is eased (lerp) so it glides rather than snaps.
 * Honours prefers-reduced-motion by never moving.
 *
 *   yScroll  translateY per pixel scrolled (negative = drifts up faster than
 *            the page → reads as "closer"; positive = lags → "further").
 *   depth    px the layer shifts with the cursor (desktop only). Bigger =
 *            nearer the viewer.
 */

type Cb = (scrollY: number, px: number, py: number) => void;

const subs = new Set<Cb>();
let scrollY = 0;
let tpx = 0, tpy = 0; // pointer target (-1..1)
let px = 0, py = 0;   // pointer eased
let running = false;
let started = false;

function loop() {
  px += (tpx - px) * 0.08;
  py += (tpy - py) * 0.08;
  subs.forEach((cb) => cb(scrollY, px, py));
  if (Math.abs(tpx - px) > 0.0005 || Math.abs(tpy - py) > 0.0005) {
    requestAnimationFrame(loop);
  } else {
    running = false;
  }
}

function kick() {
  if (running) return;
  running = true;
  requestAnimationFrame(loop);
}

function ensureStarted() {
  if (started || typeof window === "undefined") return;
  started = true;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  scrollY = window.scrollY;
  window.addEventListener(
    "scroll",
    () => {
      scrollY = window.scrollY;
      kick();
    },
    { passive: true }
  );

  if (window.matchMedia("(pointer: fine)").matches) {
    window.addEventListener(
      "mousemove",
      (e) => {
        tpx = (e.clientX / window.innerWidth - 0.5) * 2;
        tpy = (e.clientY / window.innerHeight - 0.5) * 2;
        kick();
      },
      { passive: true }
    );
  }
}

export default function Parallax({
  children,
  className = "",
  yScroll = 0,
  depth = 0,
}: {
  children: ReactNode;
  className?: string;
  yScroll?: number;
  depth?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ensureStarted();
    const el = ref.current;
    if (!el) return;
    const cb: Cb = (sy, cx, cy) => {
      const tx = cx * depth;
      const ty = sy * yScroll + cy * depth;
      el.style.transform = `translate3d(${tx.toFixed(2)}px, ${ty.toFixed(2)}px, 0)`;
    };
    subs.add(cb);
    cb(scrollY, px, py);
    return () => {
      subs.delete(cb);
    };
  }, [yScroll, depth]);

  return (
    <div ref={ref} className={className} style={{ willChange: "transform" }}>
      {children}
    </div>
  );
}
