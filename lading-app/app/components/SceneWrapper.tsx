"use client";

import dynamic from "next/dynamic";

const Scene = dynamic(() => import("./Scene"), {
  ssr: false,
  loading: () => (
    <div className="w-full h-full flex items-center justify-center font-mono text-ink/50 text-sm">
      loading 3d…
    </div>
  ),
});

export default function SceneWrapper() {
  return <Scene />;
}
