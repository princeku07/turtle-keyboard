"use client";

import { Canvas, useFrame, ThreeEvent, useThree } from "@react-three/fiber";
import { Float, RoundedBox, Text, Environment, MeshDistortMaterial } from "@react-three/drei";
import { useRef, Suspense, useState, useMemo } from "react";
import * as THREE from "three";

type ShapeKind = "plasma" | "lattice" | "atom" | "spike" | "linked" | "swarm";

type Cmd = {
  label: string;
  color: string;
  pos: [number, number, number];
  rot: number;
  shape: ShapeKind;
};

const COMMANDS: Cmd[] = [
  { label: "/cap",   color: "#ff4fa3", pos: [-2.4,  1.2,  0.0], rot: -0.18, shape: "plasma" },
  { label: "/fix",   color: "#c8ff00", pos: [ 2.2,  1.6, -0.6], rot:  0.22, shape: "lattice" },
  { label: "/reply", color: "#5b6cff", pos: [-2.7, -1.4, -0.4], rot:  0.10, shape: "atom"   },
  { label: "/tone",  color: "#ff7a1a", pos: [ 2.7, -1.0,  0.2], rot: -0.14, shape: "spike"  },
  { label: "/tl",    color: "#f5f0e1", pos: [ 0.1,  2.4, -1.2], rot:  0.05, shape: "linked" },
  { label: "/meme",  color: "#0c0c0c", pos: [ 0.0, -2.4, -0.8], rot: -0.05, shape: "swarm"  },
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
        onClick={(e)       => { e.stopPropagation(); onClick(); }}
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

/* ---------- shape primitives ---------- */

// helpers
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

/* /cap — Plasma Orb: distort sphere + tiny orbiting satellites */
function PlasmaOrb({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const orb = useRef<THREE.Mesh>(null);
  const sat = useRef<THREE.Group>(null);
  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (orb.current) {
      orb.current.rotation.y = t * 0.4;
      colorLerp(orb.current.material, color, 0.08);
      const m = orb.current.material as THREE.MeshStandardMaterial;
      m.emissiveIntensity = lerp(m.emissiveIntensity, visible ? 0.3 : 0.05, 0.1);
    }
    if (sat.current) sat.current.rotation.y = t * 1.2;
  });
  return (
    <ShapeShell visible={visible} scale={1.1}>
      <mesh ref={orb} castShadow>
        <sphereGeometry args={[1.05, 96, 96]} />
        <MeshDistortMaterial color="#ff4fa3" roughness={0.25} metalness={0.25} distort={0.55} speed={3.2} />
      </mesh>
      <group ref={sat}>
        {[0, 1, 2].map((i) => {
          const a = (i / 3) * Math.PI * 2;
          return (
            <mesh key={i} position={[Math.cos(a) * 1.7, Math.sin(a) * 0.4, Math.sin(a) * 1.7]} castShadow>
              <sphereGeometry args={[0.13, 24, 24]} />
              <meshStandardMaterial color="#ff4fa3" emissive="#ff4fa3" emissiveIntensity={0.6} />
            </mesh>
          );
        })}
      </group>
    </ShapeShell>
  );
}

/* /fix — Lattice Cube: 4x4x4 grid of small cubes, structured + crisp */
function LatticeCube({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const N = 4;
  const step = 0.45;
  const offset = ((N - 1) * step) / 2;
  const positions = useMemo(() => {
    const arr: [number, number, number][] = [];
    for (let x = 0; x < N; x++)
      for (let y = 0; y < N; y++)
        for (let z = 0; z < N; z++)
          if (x === 0 || x === N - 1 || y === 0 || y === N - 1 || z === 0 || z === N - 1) {
            arr.push([x * step - offset, y * step - offset, z * step - offset]);
          }
    return arr;
  }, []);

  useFrame((state) => {
    if (!group.current) return;
    const t = state.clock.getElapsedTime();
    group.current.rotation.x = t * 0.25;
    group.current.rotation.y = t * 0.35;
    group.current.children.forEach((mesh, i) => {
      const m = (mesh as THREE.Mesh).material as THREE.MeshStandardMaterial;
      colorLerp(m, color, 0.08);
      m.emissiveIntensity = lerp(m.emissiveIntensity, visible ? 0.3 + Math.sin(t * 2 + i * 0.3) * 0.15 : 0.05, 0.1);
      const s = visible ? 1 + Math.sin(t * 2 + i * 0.4) * 0.08 : 1;
      mesh.scale.setScalar(s);
    });
  });

  return (
    <ShapeShell visible={visible} scale={1.1}>
      <group ref={group}>
        {positions.map((p, i) => (
          <mesh key={i} position={p} castShadow>
            <boxGeometry args={[0.28, 0.28, 0.28]} />
            <meshStandardMaterial color="#c8ff00" emissive="#c8ff00" emissiveIntensity={0.3} roughness={0.4} metalness={0.2} />
          </mesh>
        ))}
      </group>
    </ShapeShell>
  );
}

/* /reply — Atom: three orthogonal torus rings + 3 orbiting balls (3 reply options) */
function Atom({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const balls = useRef<THREE.Group>(null);
  useFrame((state) => {
    if (!group.current) return;
    const t = state.clock.getElapsedTime();
    group.current.rotation.y = t * 0.4;
    group.current.rotation.x = Math.sin(t * 0.3) * 0.3;
    group.current.children.forEach((c) => {
      const mesh = c as THREE.Mesh;
      if (mesh.material) colorLerp(mesh.material, color, 0.08);
    });
    if (balls.current) {
      balls.current.rotation.z = t * 1.3;
      balls.current.children.forEach((c) => {
        const mesh = c as THREE.Mesh;
        if (mesh.material) colorLerp(mesh.material, color, 0.08);
      });
    }
  });
  const ringMat = <meshStandardMaterial color="#5b6cff" emissive="#5b6cff" emissiveIntensity={0.4} roughness={0.3} metalness={0.3} />;
  return (
    <ShapeShell visible={visible} scale={1.05}>
      <group ref={group}>
        <mesh castShadow>
          <torusGeometry args={[1.15, 0.06, 20, 80]} />
          {ringMat}
        </mesh>
        <mesh castShadow rotation={[Math.PI / 2, 0, 0]}>
          <torusGeometry args={[1.15, 0.06, 20, 80]} />
          {ringMat}
        </mesh>
        <mesh castShadow rotation={[0, 0, Math.PI / 2]}>
          <torusGeometry args={[1.15, 0.06, 20, 80]} />
          {ringMat}
        </mesh>
        <mesh>
          <sphereGeometry args={[0.4, 32, 32]} />
          <meshStandardMaterial color="#5b6cff" emissive="#5b6cff" emissiveIntensity={0.7} roughness={0.2} />
        </mesh>
      </group>
      <group ref={balls}>
        {[0, 1, 2].map((i) => {
          const a = (i / 3) * Math.PI * 2;
          return (
            <mesh key={i} position={[Math.cos(a) * 1.15, Math.sin(a) * 1.15, 0]}>
              <sphereGeometry args={[0.14, 24, 24]} />
              <meshStandardMaterial color="#5b6cff" emissive="#5b6cff" emissiveIntensity={0.9} />
            </mesh>
          );
        })}
      </group>
    </ShapeShell>
  );
}

/* /tone — Spike Star: dodeca with cones radiating along fibonacci sphere directions */
function SpikeStar({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const directions = useMemo(() => {
    const N = 32;
    const arr: { pos: [number, number, number]; rot: [number, number, number] }[] = [];
    const phi = Math.PI * (Math.sqrt(5) - 1);
    for (let i = 0; i < N; i++) {
      const y = 1 - (i / (N - 1)) * 2;
      const r = Math.sqrt(1 - y * y);
      const theta = phi * i;
      const x = Math.cos(theta) * r;
      const z = Math.sin(theta) * r;
      const dir = new THREE.Vector3(x, y, z);
      const q = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir);
      const e = new THREE.Euler().setFromQuaternion(q);
      arr.push({ pos: [x * 1.05, y * 1.05, z * 1.05], rot: [e.x, e.y, e.z] });
    }
    return arr;
  }, []);
  useFrame((state) => {
    if (!group.current) return;
    const t = state.clock.getElapsedTime();
    group.current.rotation.y = t * 0.4;
    group.current.rotation.x = t * 0.18;
    const breath = 1 + Math.sin(t * 1.5) * (visible ? 0.06 : 0.0);
    group.current.scale.setScalar(breath);
    group.current.children.forEach((c) => {
      const m = (c as THREE.Mesh).material as THREE.MeshStandardMaterial;
      if (m) colorLerp(m, color, 0.08);
    });
  });
  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        <mesh castShadow>
          <dodecahedronGeometry args={[0.85, 0]} />
          <meshStandardMaterial color="#ff7a1a" emissive="#ff7a1a" emissiveIntensity={0.25} roughness={0.4} metalness={0.15} flatShading />
        </mesh>
        {directions.map((d, i) => (
          <mesh key={i} position={d.pos} rotation={d.rot} castShadow>
            <coneGeometry args={[0.12, 0.55, 12]} />
            <meshStandardMaterial color="#ff7a1a" emissive="#ff7a1a" emissiveIntensity={0.4} roughness={0.35} metalness={0.2} />
          </mesh>
        ))}
      </group>
    </ShapeShell>
  );
}

/* /tl — Linked Rings: two interlocked tori at perpendicular tilts (bridge between languages) */
function LinkedRings({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const a = useRef<THREE.Mesh>(null);
  const b = useRef<THREE.Mesh>(null);
  const core = useRef<THREE.Mesh>(null);
  useFrame((state) => {
    const t = state.clock.getElapsedTime();
    if (group.current) group.current.rotation.y = t * 0.3;
    if (a.current) {
      a.current.rotation.y = t * 0.9;
      colorLerp(a.current.material, color, 0.08);
    }
    if (b.current) {
      b.current.rotation.x = t * 0.9;
      colorLerp(b.current.material, color, 0.08);
    }
    if (core.current) {
      const s = 1 + Math.sin(t * 2) * (visible ? 0.1 : 0);
      core.current.scale.setScalar(s);
      colorLerp(core.current.material, color, 0.08);
    }
  });
  return (
    <ShapeShell visible={visible} scale={1.05}>
      <group ref={group}>
        <mesh ref={a} castShadow rotation={[0, 0, 0]} position={[-0.35, 0, 0]}>
          <torusGeometry args={[0.95, 0.12, 32, 96]} />
          <meshStandardMaterial color="#f5f0e1" emissive="#f5f0e1" emissiveIntensity={0.3} roughness={0.25} metalness={0.5} />
        </mesh>
        <mesh ref={b} castShadow rotation={[Math.PI / 2, 0, 0]} position={[0.35, 0, 0]}>
          <torusGeometry args={[0.95, 0.12, 32, 96]} />
          <meshStandardMaterial color="#f5f0e1" emissive="#f5f0e1" emissiveIntensity={0.3} roughness={0.25} metalness={0.5} />
        </mesh>
        <mesh ref={core}>
          <icosahedronGeometry args={[0.32, 1]} />
          <meshStandardMaterial color="#f5f0e1" emissive="#f5f0e1" emissiveIntensity={0.7} roughness={0.2} />
        </mesh>
      </group>
    </ShapeShell>
  );
}

/* /meme — Swarm: cluster of distorted icosahedra in chaotic orbit */
function Swarm({ color, visible }: { color: THREE.Color; visible: boolean }) {
  const group = useRef<THREE.Group>(null);
  const N = 22;
  const seeds = useMemo(() => {
    return Array.from({ length: N }, (_, i) => {
      const r = 0.6 + Math.random() * 0.9;
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos(2 * Math.random() - 1);
      return {
        baseR: r,
        theta,
        phi,
        speed: 0.4 + Math.random() * 1.2,
        size: 0.16 + Math.random() * 0.22,
        offset: Math.random() * Math.PI * 2,
        i,
      };
    });
  }, []);
  useFrame((state) => {
    if (!group.current) return;
    const t = state.clock.getElapsedTime();
    group.current.rotation.y = t * 0.25;
    group.current.children.forEach((c, idx) => {
      const seed = seeds[idx];
      if (!seed) return;
      const r = seed.baseR + Math.sin(t * seed.speed + seed.offset) * 0.3;
      const theta = seed.theta + t * seed.speed * 0.4;
      const phi = seed.phi + Math.sin(t * 0.7 + seed.offset) * 0.4;
      c.position.x = r * Math.sin(phi) * Math.cos(theta);
      c.position.y = r * Math.cos(phi);
      c.position.z = r * Math.sin(phi) * Math.sin(theta);
      c.rotation.x = t * seed.speed;
      c.rotation.y = t * seed.speed * 0.7;
      const m = (c as THREE.Mesh).material as THREE.MeshStandardMaterial;
      if (m) {
        colorLerp(m, color, 0.08);
        m.emissiveIntensity = lerp(m.emissiveIntensity, visible ? 0.4 : 0.05, 0.1);
      }
    });
  });
  return (
    <ShapeShell visible={visible} scale={1.0}>
      <group ref={group}>
        {seeds.map((s) => (
          <mesh key={s.i} castShadow>
            <icosahedronGeometry args={[s.size, 0]} />
            <meshStandardMaterial color="#0c0c0c" emissive="#0c0c0c" emissiveIntensity={0.4} roughness={0.5} metalness={0.2} flatShading />
          </mesh>
        ))}
      </group>
    </ShapeShell>
  );
}

/* ---------- Core (idle + active dispatcher) ---------- */

function MorphingCore({ active }: { active: Cmd | null }) {
  const ref = useRef<THREE.Group>(null);
  const haloColor = useColor(active?.color ?? "#c8ff00");
  const haloMatRef = useRef<THREE.MeshBasicMaterial>(null);

  useFrame((state) => {
    if (!ref.current) return;
    const t = state.clock.getElapsedTime();
    ref.current.rotation.y = t * 0.2;
    if (haloMatRef.current) {
      haloMatRef.current.color.lerp(haloColor, 0.08);
      haloMatRef.current.opacity = lerp(haloMatRef.current.opacity, active ? 0.55 : 0.28, 0.08);
    }
  });

  const idleColor = useColor("#0c0c0c");
  const cap   = useColor("#ff4fa3");
  const fix   = useColor("#c8ff00");
  const reply = useColor("#5b6cff");
  const tone  = useColor("#ff7a1a");
  const tl    = useColor("#f5f0e1");
  const meme  = useColor("#0c0c0c");

  return (
    <group ref={ref}>
      {/* idle shape — only visible when nothing is active */}
      <IdleCore visible={!active} color={idleColor} />

      <PlasmaOrb   color={cap}   visible={active?.shape === "plasma"} />
      <LatticeCube color={fix}   visible={active?.shape === "lattice"} />
      <Atom        color={reply} visible={active?.shape === "atom"} />
      <SpikeStar   color={tone}  visible={active?.shape === "spike"} />
      <LinkedRings color={tl}    visible={active?.shape === "linked"} />
      <Swarm       color={meme}  visible={active?.shape === "swarm"} />

      {/* halo wireframe */}
      <mesh scale={1.45}>
        <icosahedronGeometry args={[1.06, 1]} />
        <meshBasicMaterial ref={haloMatRef} color="#c8ff00" wireframe transparent opacity={0.28} />
      </mesh>
    </group>
  );
}

function IdleCore({ visible, color }: { visible: boolean; color: THREE.Color }) {
  const ref = useRef<THREE.Mesh>(null);
  useFrame((state) => {
    if (!ref.current) return;
    const t = state.clock.getElapsedTime();
    ref.current.rotation.y = t * 0.35;
    ref.current.rotation.x = Math.sin(t * 0.5) * 0.15;
    const target = visible ? 1.0 : 0.0001;
    ref.current.scale.x = lerp(ref.current.scale.x, target, 0.14);
    ref.current.scale.y = lerp(ref.current.scale.y, target, 0.14);
    ref.current.scale.z = lerp(ref.current.scale.z, target, 0.14);
    colorLerp(ref.current.material, color, 0.08);
  });
  return (
    <mesh ref={ref} castShadow scale={1}>
      <icosahedronGeometry args={[1.05, 1]} />
      <meshStandardMaterial color="#0c0c0c" flatShading roughness={0.6} metalness={0.1} />
    </mesh>
  );
}

/* ---------- Responsive fit ---------- */

// Pills span x ±2.7, y ±2.4 in world units, plus ~1.0 pill half-width padding.
const CONTENT_HALF_W = 2.7 + 1.1;
const CONTENT_HALF_H = 2.4 + 0.5;

function FitToViewport({ children }: { children: React.ReactNode }) {
  const group = useRef<THREE.Group>(null);
  const { viewport } = useThree(); // viewport at z=0 in world units; updates on resize
  useFrame(() => {
    if (!group.current) return;
    const sx = viewport.width  / (CONTENT_HALF_W * 2);
    const sy = viewport.height / (CONTENT_HALF_H * 2);
    const target = Math.min(sx, sy, 1); // never upscale past designed size
    const eased = lerp(group.current.scale.x, target, 0.2);
    group.current.scale.setScalar(eased);
  });
  return <group ref={group}>{children}</group>;
}

/* ---------- Scene ---------- */

export default function Scene() {
  const [activeLabel, setActiveLabel] = useState<string | null>(null);
  const active = COMMANDS.find((c) => c.label === activeLabel) ?? null;

  return (
    <Canvas
      shadows
      dpr={[1, 2]}
      camera={{ position: [0, 0, 6.2], fov: 45 }}
      gl={{ antialias: true, alpha: true }}
      onPointerMissed={() => setActiveLabel(null)}
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
              isActive={active?.label === c.label}
              onClick={() => setActiveLabel((prev) => (prev === c.label ? null : c.label))}
            />
          ))}
        </FitToViewport>
        <Environment preset="city" />
      </Suspense>
    </Canvas>
  );
}
