"use client";

import dynamic from "next/dynamic";
import type { MutableRefObject } from "react";
import type { PressMap } from "./Scene";

const Scene = dynamic(() => import("./Scene"), {
  ssr: false,
  loading: () => (
    <div className="w-full h-full flex items-center justify-center font-mono text-ink/50 text-sm">
      loading 3d…
    </div>
  ),
});

export default function SceneWrapper({
  pressedRef,
  flat,
  onKeyTap,
}: {
  pressedRef: MutableRefObject<PressMap>;
  flat?: boolean;
  onKeyTap?: (id: string) => void;
}) {
  return <Scene pressedRef={pressedRef} flat={flat} onKeyTap={onKeyTap} />;
}
