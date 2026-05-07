"use client";

import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { RoundedBox, Text } from "@react-three/drei";
import { Suspense, useMemo, useRef } from "react";
import * as THREE from "three";

const KEY_SIZE = 0.46;
const KEY_HEIGHT = 0.30;
const KEY_GAP = 0.14;
const STEP = KEY_SIZE + KEY_GAP;

const CAP = "#fbf6ea";
const CAP_SKIRT = "#1a1815";
const MOD_CAP = "#2b2724";
const MOD_SKIRT = "#0d0c0b";
const INK = "#0c0c0c";
const INK_LIGHT = "#f4efe4";

function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t;
}

export type Press = { at: number; accent: string };
export type PressMap = Map<string, Press>;

type KeyDef = {
  id: string;
  display: string;
  x: number;
  y: number;
  w: number;
  small?: boolean;
};

export type KbdLayout = "qwerty" | "numpad";

function buildKeys(layout: KbdLayout): KeyDef[] {
  const keys: KeyDef[] = [];
  const rows =
    layout === "qwerty"
      ? ["qwertyuiop", "asdfghjkl", "zxcvbnm"].map((s) => s.split(""))
      : [
          "1234567890".split(""),
          "-:;()$&@*\"".split(""),
          ".,?!'".split(""),
        ];

  rows.forEach((row, ri) => {
    const y = STEP * (1 - ri);
    const stagger =
      layout === "qwerty"
        ? ri === 1
          ? STEP * 0.25
          : ri === 2
          ? STEP * 0.6
          : 0
        : ri === 2
        ? STEP * 2.5
        : 0;
    const rowW = row.length * STEP - KEY_GAP;
    const x0 = -rowW / 2 + KEY_SIZE / 2 + stagger;
    row.forEach((c, i) => {
      keys.push({
        id: layout === "qwerty" ? c : "n_" + c,
        display: layout === "qwerty" ? c.toUpperCase() : c,
        x: x0 + i * STEP,
        y,
        w: KEY_SIZE,
      });
    });
  });

  // bottom row: numpadToggle · shift · space · / · ⌫
  const by = -2 * STEP;
  const toggleW = KEY_SIZE * 1.05;
  const sideW = KEY_SIZE * 1.05;
  const spaceW = KEY_SIZE * 3.4 + KEY_GAP * 3;
  const slashW = KEY_SIZE * 0.9;
  const total = toggleW + sideW + spaceW + slashW + sideW + KEY_GAP * 4;
  let cx = -total / 2 + toggleW / 2;
  keys.push({
    id: "numpad",
    display: layout === "qwerty" ? "123" : "ABC",
    x: cx,
    y: by,
    w: toggleW,
    small: true,
  });
  cx += toggleW / 2 + KEY_GAP + sideW / 2;
  keys.push({
    id: "shift",
    display: layout === "qwerty" ? "shift" : "#+=",
    x: cx,
    y: by,
    w: sideW,
    small: true,
  });
  cx += sideW / 2 + KEY_GAP + spaceW / 2;
  keys.push({ id: "space", display: "space", x: cx, y: by, w: spaceW, small: true });
  cx += spaceW / 2 + KEY_GAP + slashW / 2;
  keys.push({ id: "slash", display: "/", x: cx, y: by, w: slashW });
  cx += slashW / 2 + KEY_GAP + sideW / 2;
  keys.push({ id: "backspace", display: "⌫", x: cx, y: by, w: sideW, small: true });
  return keys;
}

function Key({
  def,
  pressedRef,
  onTap,
}: {
  def: KeyDef;
  pressedRef: React.MutableRefObject<PressMap>;
  onTap?: (id: string) => void;
}) {
  const groupRef = useRef<THREE.Group>(null);
  const capMatRef = useRef<THREE.MeshStandardMaterial>(null);

  const isMod =
    def.id === "shift" ||
    def.id === "space" ||
    def.id === "backspace" ||
    def.id === "numpad";
  const baseCapHex = isMod ? MOD_CAP : CAP;
  const skirtHex = isMod ? MOD_SKIRT : CAP_SKIRT;
  const legendColor = isMod ? INK_LIGHT : INK;

  const baseColor = useMemo(() => new THREE.Color(baseCapHex), [baseCapHex]);
  const black = useMemo(() => new THREE.Color("#000000"), []);
  const accentColor = useMemo(() => new THREE.Color(baseCapHex), [baseCapHex]);

  useFrame(() => {
    const press = pressedRef.current.get(def.id);
    const now = performance.now();
    const since = press ? now - press.at : Infinity;
    const intensity = Math.max(0, 1 - since / 380);

    if (press) accentColor.set(press.accent);

    if (groupRef.current) {
      const targetY = def.y - intensity * 0.09;
      groupRef.current.position.y = lerp(groupRef.current.position.y, targetY, 0.4);
    }
    if (capMatRef.current) {
      const lit = intensity > 0.02;
      capMatRef.current.color.lerp(lit ? accentColor : baseColor, 0.25);
      capMatRef.current.emissive.lerp(lit ? accentColor : black, 0.25);
      capMatRef.current.emissiveIntensity = lerp(
        capMatRef.current.emissiveIntensity,
        intensity * 0.6,
        0.3,
      );
    }
  });

  // Mechanical keycap = lower skirt + upper cap (sharper edges than rounded blob)
  const skirtH = KEY_HEIGHT * 0.55;
  const capH = KEY_HEIGHT * 0.55;
  const capInset = 0.04;

  const handleTap = (e: { stopPropagation: () => void }) => {
    e.stopPropagation();
    onTap?.(def.id);
  };

  return (
    <group
      ref={groupRef}
      position={[def.x, def.y, 0]}
      onPointerDown={handleTap}
    >
      {/* skirt / lower housing */}
      <RoundedBox
        args={[def.w, KEY_SIZE, skirtH]}
        radius={0.018}
        smoothness={3}
        position={[0, 0, -KEY_HEIGHT / 2 + skirtH / 2]}
        castShadow
        receiveShadow
      >
        <meshStandardMaterial color={skirtHex} roughness={0.7} metalness={0.05} />
      </RoundedBox>
      {/* upper cap */}
      <RoundedBox
        args={[def.w - capInset, KEY_SIZE - capInset, capH]}
        radius={0.022}
        smoothness={3}
        position={[0, 0, -KEY_HEIGHT / 2 + skirtH + capH / 2 - 0.01]}
        castShadow
        receiveShadow
      >
        <meshStandardMaterial
          ref={capMatRef}
          color={baseCapHex}
          roughness={0.42}
          metalness={0.12}
        />
      </RoundedBox>
      <Text
        position={[0, 0, -KEY_HEIGHT / 2 + skirtH + capH + 0.008]}
        fontSize={def.small ? 0.13 : 0.22}
        color={legendColor}
        anchorX="center"
        anchorY="middle"
        letterSpacing={-0.02}
        fontWeight={700}
      >
        {def.display}
      </Text>
    </group>
  );
}

// chassis intentionally removed — keys float on transparent canvas

const CONTENT_HALF_W = 3.05;
// CONTENT_HALF_H is intentionally larger than the raw key span so the perspective
// tilt on desktop (rotation = -0.22 rad on x) can't push the bottom row past the
// canvas edge. Y_RECENTER lifts the keys upward inside that reserved box, leaving
// breathing room below.
const CONTENT_HALF_H = 1.4;
const EDGE_MARGIN = 0.96;
// raw KEY_DEFS span y ∈ [-1.33, 0.78] — recenter so we reserve ~0.5 units of pad below
const Y_RECENTER = 0.55;

function FitToViewport({ children }: { children: React.ReactNode }) {
  const group = useRef<THREE.Group>(null);
  const { viewport } = useThree();
  useFrame(() => {
    if (!group.current) return;
    const sx = viewport.width / (CONTENT_HALF_W * 2);
    const sy = viewport.height / (CONTENT_HALF_H * 2);
    const target = Math.min(sx, sy) * EDGE_MARGIN;
    const eased = lerp(group.current.scale.x, target, 0.2);
    group.current.scale.setScalar(eased);
  });
  return <group ref={group}>{children}</group>;
}

export default function Scene({
  pressedRef,
  flat = false,
  onKeyTap,
  layout = "qwerty",
}: {
  pressedRef: React.MutableRefObject<PressMap>;
  flat?: boolean;
  onKeyTap?: (id: string) => void;
  layout?: KbdLayout;
}) {
  const keyDefs = useMemo(() => buildKeys(layout), [layout]);
  return (
    <Canvas
      shadows
      dpr={[1, 2]}
      camera={{ position: [0, 0, 6.4], fov: 42 }}
      gl={{ antialias: true, alpha: true }}
      style={{ touchAction: "pan-y" }}
    >
      <Suspense fallback={null}>
        <ambientLight intensity={0.85} />
        <directionalLight position={[3, 6, 5]} intensity={0.95} castShadow />
        <directionalLight position={[-4, 3, 4]} intensity={0.45} color="#ff4fa3" />
        <directionalLight position={[0, -3, 3]} intensity={0.25} color="#5b6cff" />
        <FitToViewport>
          <group
            position={[0, Y_RECENTER, 0]}
            rotation={[flat ? -0.02 : -0.22, 0, 0]}
          >
            {keyDefs.map((def) => (
              <Key
                key={def.id}
                def={def}
                pressedRef={pressedRef}
                onTap={onKeyTap}
              />
            ))}
          </group>
        </FitToViewport>
      </Suspense>
    </Canvas>
  );
}
