"use client";

import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { Float, RoundedBox, Text, Environment, MeshDistortMaterial } from "@react-three/drei";
import { useRef, Suspense, useState, useMemo } from "react";
import * as THREE from "three";

type ShapeKind = "frame" | "sticker" | "canvas" | "bust" | "diorama" | "memecard";

type Cmd = {
  label: string;
  color: string;
  pos: [number, number, number];
  rot: number;
  shape: ShapeKind;
};

// All v1 commands generate images. Each pill maps to a 3D shape that mimics what it ships.
const COMMANDS: Cmd[] = [
  { label: "/cap",     color: "#ff4fa3", pos: [-2.4,  1.2,  0.0], rot: -0.18, shape: "frame"    },
  { label: "/sticker", color: "#c8ff00", pos: [ 2.2,  1.6, -0.6], rot:  0.22, shape: "sticker"  },
  { label: "/edit",    color: "#5b6cff", pos: [-2.7, -1.4, -0.4], rot:  0.10, shape: "canvas"   },
  { label: "/avatar",  color: "#ff7a1a", pos: [ 2.7, -1.0,  0.2], rot: -0.14, shape: "bust"     },
  { label: "/scene",   color: "#f5f0e1", pos: [ 0.1,  2.4, -1.2], rot:  0.05, shape: "diorama"  },
  { label: "/meme",    color: "#0c0c0c", pos: [ 0.0, -2.4, -0.8], rot: -0.05, shape: "memecard" },
];

function lerp(a: number, b: number, t: number) { return a + (b - a) * t; }
function useColor(hex: string) { return useMemo(() => new THREE.Color(hex), [hex]); }

/* ---------- Pill ---------- */

function CommandPill({
  cmd, isActive, onClick,
}: { cmd: Cmd; isActive: boolean; onClick: () => void }) {
  const group = useRef<THREE.Group>(null);
  const [hovered, setHovered] = useState(false);

  useFrame((state) => {
    if (!group.current) return;
    const t = state.clock.getElapsedTime();
    group.current.rotation.z = cmd.rot + Math.sin(t * 0.6 + cmd.pos[0]) * 0.04;
    const target = isActive ? 1.18 : hovered ? 1.08 : 1.0;
    group.current.scale.x = lerp(group.current.scale.x, target, 0.15);
    group.current.scale.y = lerp(group.current.scale.y, target, 0.15);
    group.current.scale.z = lerp(group.current.scale.z, target, 0.15);
  });

  const isLight = cmd.color === "#c8ff00" || cmd.color === "#f5f0e1";
  const textColor = isLight ? "#0c0c0c" : "#ffffff";

  return (
    <Float speed={1.4} rotationIntensity={0.25} floatIntensity={0.9} position={cmd.pos}>
      <group
        ref={group}
        rotation={[0, 0, cmd.rot]}
        onPointerOver={(e) => { e.stopPropagation(); setHovered(true); document.body.style.cursor = "pointer"; }}
        onPointerOut={(e)  => { e.stopPropagation(); setHovered(false); document.body.style.cursor = "auto"; }}
        onPointerDown={(e) => { e.stopPropagation(); onClick(); }}
      >
        {isActive && (
          <mesh position={[0, 0, -0.18]}>
            <torusGeometry args={[1.25, 0.06, 16, 64]} />
            <meshBasicMaterial color={cmd.color} transparent opacity={0.55} />
          </mesh>
        )}
        <RoundedBox args={[2.0, 0.78, 0.32]} radius={0.36} smoothness={6} castShadow receiveShadow>
          <meshStandardMaterial
            color={cmd.color}
            metalness={0.2}
            roughness={0.35}
            emissive={cmd.color}
            emissiveIntensity={isActive ? 0.45 : hovered ? 0.18 : 0}
          />
        </RoundedBox>
        <mesh position={[0, 0, 0.17]}>
          <Text fontSize={0.42} color={textColor} anchorX="center" anchorY="middle" letterSpacing={-0.02} fontWeight={700}>
            {cmd.label}
          </Text>
        </mesh>
      </group>
    </Float>
  );
}

/* ---------- helpers ---------- */

function colorLerp(material: THREE.Material | THREE.Material[], target: THREE.Color, k = 0.08) {
  const apply = (m: THREE.Material) => {
    const sm = m as THREE.MeshStandardMaterial;
    if (sm.color)    sm.color.lerp(target, k);
    if (sm.emissive) sm.emissive.lerp(target, k);
  };
  if (Array.isArray(material)) material.forEach(apply); else apply(material);
}

function ShapeShell({
  visible, scale, children,
}: { visible: boolean; scale: number; children: React.ReactNode }) {
  const ref = useRef<THREE.Group>(null);
  useFrame(() => {
    if (!ref.current) return;
    const target = visible ? scale : 0.0001;
    ref.current.scale.x = lerp(ref.current.scale.x, target, 0.14);
    ref.current.scale.y = lerp(ref.current.scale.y, target, 0.14);
    ref.current.scale.z = lerp(ref.current.scale.z, target, 0.14);
  });
  return <group ref={ref} scale={0.0001}>{children}</group>;
}

/* ---------- /cap — Picture Frame: a frame with a generating image inside ---------- */

function PictureFrame({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const inner = useRef<THREE.Mesh>(null);
  const shutter = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (group.current) {
      group.current.rotation.y = Math.sin(t * 0.5) * 0.5;
      group.current.rotation.x = Math.cos(t * 0.4) * 0.18;
    }
    if (inner.current) {
      colorLerp(inner.current.material, color, 0.08);
    }
    if (shutter.current) {
      shutter.current.rotation.z = t * 1.2;
    }
  });

  const frameMat = (
    <meshStandardMaterial color="#0c0c0c" roughness={0.5} metalness={0.3} />
  );

  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        {/* outer frame — 4 bars */}
        <mesh position={[0,  1.05, 0]} castShadow><boxGeometry args={[2.30, 0.18, 0.20]} />{frameMat}</mesh>
        <mesh position={[0, -1.05, 0]} castShadow><boxGeometry args={[2.30, 0.18, 0.20]} />{frameMat}</mesh>
        <mesh position={[-1.06, 0, 0]} castShadow><boxGeometry args={[0.18, 2.10, 0.20]} />{frameMat}</mesh>
        <mesh position={[ 1.06, 0, 0]} castShadow><boxGeometry args={[0.18, 2.10, 0.20]} />{frameMat}</mesh>

        {/* the "image" — distort material to feel like it's still rendering */}
        <mesh ref={inner} position={[0, 0, 0.04]}>
          <planeGeometry args={[1.95, 1.95]} />
          <MeshDistortMaterial color="#ff4fa3" roughness={0.3} metalness={0.2} distort={0.5} speed={3} />
        </mesh>

        {/* shutter aperture in the corner */}
        <mesh ref={shutter} position={[0.78, 0.78, 0.13]}>
          <torusGeometry args={[0.14, 0.04, 12, 24, Math.PI * 1.5]} />
          <meshStandardMaterial color="#ff4fa3" emissive="#ff4fa3" emissiveIntensity={0.7} />
        </mesh>
      </group>
    </ShapeShell>
  );
}

/* ---------- /sticker — single die-cut sticker with paper backing, glyph + curling peel ---------- */

function StickerStack({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const peel = useRef<THREE.Mesh>(null);
  const gloss = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (group.current) {
      group.current.rotation.y = Math.sin(t * 0.55) * 0.6;
      group.current.rotation.x = Math.cos(t * 0.4) * 0.18;
    }
    if (peel.current) {
      peel.current.rotation.x = -0.85 + Math.sin(t * 1.6) * 0.2;
    }
    if (gloss.current) {
      // gloss highlight slides across the sticker face
      gloss.current.position.x = Math.sin(t * 0.8) * 0.45;
    }
  });

  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        {/* die-cut paper backing — slightly larger, cream, recessed */}
        <RoundedBox args={[2.05, 2.05, 0.06]} radius={0.55} smoothness={5} position={[0, 0, -0.08]} castShadow receiveShadow>
          <meshStandardMaterial color="#f5f0e1" roughness={0.85} metalness={0.0} />
        </RoundedBox>

        {/* main sticker face — chunky rounded blob */}
        <RoundedBox args={[1.7, 1.7, 0.18]} radius={0.5} smoothness={5} castShadow>
          <meshStandardMaterial color="#c8ff00" roughness={0.28} metalness={0.2} emissive="#c8ff00" emissiveIntensity={0.18} />
        </RoundedBox>

        {/* gloss streak — thin transparent quad sliding across */}
        <mesh ref={gloss} position={[0, 0.2, 0.1]} rotation={[0, 0, -0.4]}>
          <planeGeometry args={[0.35, 1.6]} />
          <meshBasicMaterial color="#ffffff" transparent opacity={0.18} />
        </mesh>

        {/* glyph on the face */}
        <Text
          position={[0, 0, 0.11]}
          fontSize={1.05}
          color="#0c0c0c"
          anchorX="center"
          anchorY="middle"
          letterSpacing={-0.02}
          fontWeight={900}
        >
          ✶
        </Text>

        {/* curling peeled corner — backside is paper-cream */}
        <mesh ref={peel} position={[-0.78, 0.78, 0.1]} rotation={[-0.85, 0, 0.78]} castShadow>
          <planeGeometry args={[0.6, 0.6]} />
          <meshStandardMaterial color="#f5f0e1" side={THREE.DoubleSide} roughness={0.7} />
        </mesh>
      </group>
    </ShapeShell>
  );
}

/* ---------- /edit — Canvas + brush + selection marquee ---------- */

function EditCanvas({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const brush = useRef<THREE.Group>(null);
  const marquee = useRef<THREE.Mesh>(null);
  const canvasInner = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (group.current) {
      group.current.rotation.y = Math.sin(t * 0.45) * 0.5;
      group.current.rotation.x = -0.1 + Math.cos(t * 0.4) * 0.12;
    }
    if (brush.current) {
      // brush hovers in a small loop above the marquee
      brush.current.position.x = 0.25 + Math.cos(t * 1.4) * 0.45;
      brush.current.position.y = 0.15 + Math.sin(t * 1.4) * 0.32;
      brush.current.rotation.z = 0.4 + Math.sin(t * 1.4) * 0.1;
      brush.current.traverse((n) => {
        const m = (n as THREE.Mesh).material;
        if (m && (m as THREE.MeshStandardMaterial).color) colorLerp(m, color, 0.08);
      });
    }
    if (marquee.current) {
      const pulse = 0.5 + Math.sin(t * 3) * 0.4;
      (marquee.current.material as THREE.MeshBasicMaterial).opacity = pulse;
      colorLerp(marquee.current.material, color, 0.08);
    }
    if (canvasInner.current) colorLerp(canvasInner.current.material, color, 0.04);
  });

  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        {/* canvas backing */}
        <RoundedBox args={[2.2, 1.7, 0.12]} radius={0.06} smoothness={4} castShadow>
          <meshStandardMaterial color="#f5f0e1" roughness={0.6} metalness={0.05} />
        </RoundedBox>
        {/* painted region — distort */}
        <mesh ref={canvasInner} position={[0, 0, 0.07]}>
          <planeGeometry args={[2.05, 1.55]} />
          <MeshDistortMaterial color="#5b6cff" roughness={0.4} metalness={0.15} distort={0.25} speed={1.6} />
        </mesh>

        {/* selection marquee — outlined rectangle, pulsing */}
        <mesh ref={marquee} position={[0.1, 0.05, 0.09]}>
          <torusGeometry args={[0.55, 0.018, 8, 4]} />
          <meshBasicMaterial color="#5b6cff" transparent opacity={0.7} />
        </mesh>

        {/* brush — handle (cylinder) + ferrule (small) + tip (cone) */}
        <group ref={brush} position={[0.25, 0.15, 0.55]} rotation={[0, 0, 0.4]}>
          <mesh castShadow>
            <cylinderGeometry args={[0.07, 0.07, 0.85, 16]} />
            <meshStandardMaterial color="#5b6cff" roughness={0.4} metalness={0.4} />
          </mesh>
          <mesh position={[0, -0.45, 0]} castShadow>
            <cylinderGeometry args={[0.085, 0.085, 0.08, 16]} />
            <meshStandardMaterial color="#0c0c0c" metalness={0.7} roughness={0.3} />
          </mesh>
          <mesh position={[0, -0.6, 0]} castShadow>
            <coneGeometry args={[0.085, 0.22, 16]} />
            <meshStandardMaterial color="#5b6cff" emissive="#5b6cff" emissiveIntensity={0.5} />
          </mesh>
        </group>
      </group>
    </ShapeShell>
  );
}

/* ---------- /avatar — Profile bust inside a round frame, with multi-style chips orbiting ---------- */

const AVATAR_STYLES = ["#ff4fa3", "#5b6cff", "#c8ff00", "#ff7a1a"]; // distinct chips imply restyles

function AvatarBust({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const chips = useRef<THREE.Group>(null);
  const head = useRef<THREE.Mesh>(null);
  const shoulders = useRef<THREE.Mesh>(null);
  const frame = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (group.current) {
      group.current.rotation.y = Math.sin(t * 0.55) * 0.55;
      group.current.position.y = Math.sin(t * 1.1) * 0.04;
    }
    if (chips.current) chips.current.rotation.z = t * 0.9;
    if (head.current) colorLerp(head.current.material, color, 0.08);
    if (shoulders.current) colorLerp(shoulders.current.material, color, 0.08);
    if (frame.current) colorLerp(frame.current.material, color, 0.08);
  });

  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        {/* round profile-pic frame disc, sits behind the bust */}
        <mesh position={[0, 0.2, -0.4]} rotation={[Math.PI / 2, 0, 0]} castShadow receiveShadow>
          <cylinderGeometry args={[1.15, 1.15, 0.12, 64]} />
          <meshStandardMaterial color="#f5f0e1" roughness={0.7} metalness={0.05} />
        </mesh>
        {/* frame ring around the disc */}
        <mesh ref={frame} position={[0, 0.2, -0.32]} rotation={[Math.PI / 2, 0, 0]}>
          <torusGeometry args={[1.18, 0.06, 16, 80]} />
          <meshStandardMaterial color="#ff7a1a" emissive="#ff7a1a" emissiveIntensity={0.5} roughness={0.3} metalness={0.4} />
        </mesh>

        {/* shoulders — cleaner trapezoidal silhouette */}
        <mesh ref={shoulders} position={[0, -0.7, 0]} castShadow>
          <cylinderGeometry args={[0.95, 0.55, 0.55, 32]} />
          <meshStandardMaterial color="#ff7a1a" roughness={0.45} metalness={0.2} />
        </mesh>
        {/* collar bevel */}
        <mesh position={[0, -0.42, 0]} castShadow>
          <cylinderGeometry args={[0.55, 0.55, 0.08, 32]} />
          <meshStandardMaterial color="#ff7a1a" roughness={0.5} metalness={0.15} />
        </mesh>
        {/* neck */}
        <mesh position={[0, -0.25, 0]} castShadow>
          <cylinderGeometry args={[0.2, 0.22, 0.25, 24]} />
          <meshStandardMaterial color="#ff7a1a" roughness={0.5} metalness={0.15} />
        </mesh>
        {/* head — slightly egg-shaped via scale */}
        <mesh ref={head} position={[0, 0.42, 0]} scale={[0.95, 1.08, 0.95]} castShadow>
          <sphereGeometry args={[0.55, 48, 48]} />
          <meshStandardMaterial color="#ff7a1a" roughness={0.4} metalness={0.25} emissive="#ff7a1a" emissiveIntensity={0.2} />
        </mesh>

        {/* orbiting style chips — distinct colors, each a different "restyle" */}
        <group ref={chips} position={[0, 0.2, 0]}>
          {AVATAR_STYLES.map((c, i) => {
            const a = (i / AVATAR_STYLES.length) * Math.PI * 2;
            const r = 1.45;
            return (
              <group key={c} position={[Math.cos(a) * r, Math.sin(a) * r, 0]}>
                <mesh castShadow>
                  <sphereGeometry args={[0.13, 24, 24]} />
                  <meshStandardMaterial color={c} emissive={c} emissiveIntensity={0.8} roughness={0.3} metalness={0.3} />
                </mesh>
                {/* tiny ring around each chip — like a selected swatch */}
                <mesh rotation={[Math.PI / 2, 0, 0]}>
                  <torusGeometry args={[0.18, 0.012, 8, 24]} />
                  <meshBasicMaterial color={c} transparent opacity={0.7} />
                </mesh>
              </group>
            );
          })}
        </group>
      </group>
    </ShapeShell>
  );
}

/* ---------- /scene — Diorama: layered picture planes with parallax ---------- */

function SceneDiorama({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const back = useRef<THREE.Mesh>(null);
  const mid = useRef<THREE.Mesh>(null);
  const front = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (group.current) {
      // gentle parallax sway around Y so layers separate visually
      group.current.rotation.y = Math.sin(t * 0.6) * 0.65;
      group.current.rotation.x = Math.cos(t * 0.4) * 0.12;
    }
    // each layer drifts at a different rate
    if (back.current)  back.current.position.x  = Math.sin(t * 0.4) *  0.08;
    if (mid.current)   mid.current.position.x   = Math.sin(t * 0.7) * -0.18;
    if (front.current) front.current.position.x = Math.sin(t * 1.0) *  0.30;

    [back.current, mid.current, front.current].forEach((m) => {
      if (m) colorLerp(m.material, color, 0.06);
    });
  });

  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        {/* sky / back */}
        <mesh ref={back} position={[0, 0, -0.6]} castShadow receiveShadow>
          <planeGeometry args={[2.6, 1.8]} />
          <meshStandardMaterial color="#f5f0e1" roughness={0.7} metalness={0.05} />
        </mesh>
        {/* mid layer — hills */}
        <mesh ref={mid} position={[-0.2, -0.15, 0.0]} castShadow receiveShadow>
          <planeGeometry args={[1.9, 1.0]} />
          <meshStandardMaterial color="#f5f0e1" roughness={0.55} metalness={0.1} emissive="#f5f0e1" emissiveIntensity={0.05} />
        </mesh>
        {/* foreground subject */}
        <mesh ref={front} position={[0.45, -0.4, 0.55]} castShadow receiveShadow>
          <planeGeometry args={[0.9, 0.7]} />
          <meshStandardMaterial color="#f5f0e1" roughness={0.35} metalness={0.25} emissive="#f5f0e1" emissiveIntensity={0.2} />
        </mesh>
        {/* small frame outline around the whole thing */}
        <mesh position={[0, 0, -0.7]}>
          <torusGeometry args={[1.4, 0.02, 8, 4]} />
          <meshBasicMaterial color="#0c0c0c" transparent opacity={0.4} />
        </mesh>
      </group>
    </ShapeShell>
  );
}

/* ---------- /meme — Meme card with top/bottom text bars ---------- */

function MemeCard({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const image = useRef<THREE.Mesh>(null);
  const topBar = useRef<THREE.Mesh>(null);
  const bottomBar = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (group.current) {
      group.current.rotation.y = Math.sin(t * 0.55) * 0.55;
      group.current.rotation.z = Math.sin(t * 0.35) * 0.06;
    }
    if (image.current) colorLerp(image.current.material, color, 0.06);
    // text bars wiggle slightly to suggest a typed caption
    if (topBar.current)    topBar.current.scale.x    = 1 + Math.sin(t * 2.2) * 0.03;
    if (bottomBar.current) bottomBar.current.scale.x = 1 + Math.cos(t * 2.5) * 0.04;
  });

  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        {/* card backing */}
        <RoundedBox args={[1.95, 2.1, 0.14]} radius={0.05} smoothness={4} castShadow>
          <meshStandardMaterial color="#0c0c0c" roughness={0.6} metalness={0.15} />
        </RoundedBox>

        {/* image strip in the middle */}
        <mesh ref={image} position={[0, 0, 0.08]}>
          <planeGeometry args={[1.7, 1.2]} />
          <MeshDistortMaterial color="#0c0c0c" roughness={0.4} metalness={0.2} distort={0.35} speed={2.4} />
        </mesh>

        {/* top text bar (impact-meme style) */}
        <mesh ref={topBar} position={[0, 0.78, 0.09]}>
          <boxGeometry args={[1.55, 0.22, 0.02]} />
          <meshStandardMaterial color="#f5f0e1" emissive="#f5f0e1" emissiveIntensity={0.4} />
        </mesh>
        {/* bottom text bar */}
        <mesh ref={bottomBar} position={[0, -0.78, 0.09]}>
          <boxGeometry args={[1.55, 0.22, 0.02]} />
          <meshStandardMaterial color="#f5f0e1" emissive="#f5f0e1" emissiveIntensity={0.4} />
        </mesh>

        {/* tiny "TOP TEXT / BOTTOM TEXT" simulated as inset bars */}
        <mesh position={[-0.35, 0.78, 0.105]}>
          <boxGeometry args={[0.7, 0.06, 0.005]} />
          <meshBasicMaterial color="#0c0c0c" />
        </mesh>
        <mesh position={[0.25, -0.78, 0.105]}>
          <boxGeometry args={[0.9, 0.06, 0.005]} />
          <meshBasicMaterial color="#0c0c0c" />
        </mesh>
      </group>
    </ShapeShell>
  );
}

/* ---------- Core (idle + active dispatcher) ---------- */

function MorphingCore({ active }: { active: Cmd | null }) {
  const ref = useRef<THREE.Group>(null);

  useFrame((state) => {
    if (!ref.current) return;
    const t = state.clock.getElapsedTime();
    ref.current.rotation.y = t * 0.05;
  });

  const cap     = useColor("#ff4fa3");
  const sticker = useColor("#c8ff00");
  const edit    = useColor("#5b6cff");
  const avatar  = useColor("#ff7a1a");
  const scene   = useColor("#f5f0e1");
  const meme    = useColor("#0c0c0c");

  return (
    <group ref={ref}>
      <PictureFrame  color={cap}     visible={active?.shape === "frame"} />
      <StickerStack  color={sticker} visible={active?.shape === "sticker"} />
      <EditCanvas    color={edit}    visible={active?.shape === "canvas"} />
      <AvatarBust    color={avatar}  visible={active?.shape === "bust"} />
      <SceneDiorama  color={scene}   visible={active?.shape === "diorama"} />
      <MemeCard      color={meme}    visible={active?.shape === "memecard"} />
    </group>
  );
}

/* ---------- Responsive fit ---------- */

const CONTENT_HALF_W = 2.7 + 1.5;
const CONTENT_HALF_H = 2.4 + 1.4;

// Float adds ~0.9 units of drift in each axis; pad the fit box so pills never clip.
const FLOAT_PAD = 0.9;
// Leave a small visual margin from the canvas edges.
const EDGE_MARGIN = 0.92;

function FitToViewport({ children }: { children: React.ReactNode }) {
  const group = useRef<THREE.Group>(null);
  const { viewport } = useThree();
  useFrame(() => {
    if (!group.current) return;
    const halfW = CONTENT_HALF_W + FLOAT_PAD;
    const halfH = CONTENT_HALF_H + FLOAT_PAD;
    const sx = viewport.width  / (halfW * 2);
    const sy = viewport.height / (halfH * 2);
    const target = Math.min(sx, sy, 1) * EDGE_MARGIN;
    const eased = lerp(group.current.scale.x, target, 0.2);
    group.current.scale.setScalar(eased);
  });
  return <group ref={group}>{children}</group>;
}

/* ---------- Scene ---------- */

const DEFAULT_LABEL = "/cap";

export default function Scene() {
  const [activeLabel, setActiveLabel] = useState<string>(DEFAULT_LABEL);
  const active = COMMANDS.find((c) => c.label === activeLabel) ?? COMMANDS[0];

  return (
    <Canvas
      shadows
      dpr={[1, 2]}
      camera={{ position: [0, 0, 6.2], fov: 45 }}
      gl={{ antialias: true, alpha: true }}
      style={{ touchAction: "none" }}
    >
      <Suspense fallback={null}>
        <ambientLight intensity={0.7} />
        <directionalLight position={[4, 6, 5]} intensity={1.1} castShadow />
        <directionalLight position={[-5, -3, 4]} intensity={0.5} color="#ff4fa3" />
        <FitToViewport>
          <MorphingCore active={active} />
          {COMMANDS.map((c) => (
            <CommandPill
              key={c.label}
              cmd={c}
              isActive={active.label === c.label}
              onClick={() => setActiveLabel(c.label)}
            />
          ))}
        </FitToViewport>
        <Environment preset="city" />
      </Suspense>
    </Canvas>
  );
}
