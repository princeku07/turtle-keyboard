import Link from "next/link";
import { APP_STORE_URL, PLAY_STORE_URL } from "@/lib/store";

/**
 * App Store + Google Play badges, drawn as inline SVG (CSP-safe, no external
 * images, theme-consistent). A store with a null URL in lib/store.ts renders
 * as a muted "coming soon" pill that routes to the waitlist instead of a
 * dead link. When APP_STORE_URL is set, that badge becomes a real link.
 */

function AppleGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className="h-6 w-6 shrink-0" aria-hidden>
      <path d="M16.365 1.43c0 1.14-.417 2.2-1.25 3.05-.995 1.01-2.2 1.6-3.2 1.5-.13-1.14.44-2.29 1.2-3.05.86-.86 2.34-1.5 3.25-1.5zM20.6 17.28c-.55 1.27-.82 1.84-1.53 2.96-1 1.56-2.4 3.5-4.14 3.52-1.54.02-1.94-1-4.03-.99-2.09.01-2.53 1.01-4.07.99-1.74-.02-3.07-1.78-4.06-3.34-2.78-4.36-3.07-9.48-1.36-12.2 1.22-1.94 3.14-3.08 4.94-3.08 1.84 0 3 .99 4.52.99 1.48 0 2.38-.99 4.5-.99 1.61 0 3.31.88 4.53 2.4-3.98 2.18-3.33 7.86.7 9.29z" />
    </svg>
  );
}

function PlayGlyph() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6 shrink-0" aria-hidden>
      <path d="M3.6 2.4c-.28.3-.44.75-.44 1.34v16.52c0 .59.16 1.04.44 1.34l.08.08 9.26-9.26v-.22L3.68 2.32l-.08.08z" fill="#00d3e0" />
      <path d="M16.7 15.08l-3.08-3.08v-.22l3.08-3.08.07.04 3.65 2.08c1.04.59 1.04 1.56 0 2.16l-3.65 2.07-.07.05z" fill="#ffce00" />
      <path d="M16.77 15.03L13.62 12l-9.34 9.34c.34.36.9.4 1.54.05l10.95-6.36z" fill="#ff3d47" />
      <path d="M16.77 8.97L5.82 2.6c-.64-.36-1.2-.31-1.54.05L13.62 12l3.15-3.03z" fill="#00f076" />
    </svg>
  );
}

function Badge({
  href,
  glyph,
  top,
  bottom,
}: {
  href: string | null;
  glyph: React.ReactNode;
  top: string;
  bottom: string;
}) {
  const inner = (
    <>
      <span className="text-navy">{glyph}</span>
      <span className="text-left leading-tight">
        <span className="block font-mono text-[9px] uppercase tracking-[0.12em] text-slate">{top}</span>
        <span className="block text-[15px] font-semibold tracking-tight text-navy">{bottom}</span>
      </span>
    </>
  );

  const base =
    "inline-flex items-center gap-3 rounded-2xl border px-4 py-2.5 transition-all duration-300";

  if (href) {
    return (
      <a
        href={href}
        target="_blank"
        rel="noreferrer"
        className={`${base} border-navy/15 bg-white-warm hover:-translate-y-0.5 hover:border-iris/50`}
      >
        {inner}
      </a>
    );
  }
  return (
    <Link
      href="/#waitlist"
      className={`${base} border-dashed border-navy/20 bg-white/50 opacity-80 hover:opacity-100`}
      aria-label={`${bottom} — coming soon, join the waitlist`}
    >
      <span className="text-slate">{glyph}</span>
      <span className="text-left leading-tight">
        <span className="block font-mono text-[9px] uppercase tracking-[0.12em] text-slate">coming soon</span>
        <span className="block text-[15px] font-semibold tracking-tight text-slate">{bottom}</span>
      </span>
    </Link>
  );
}

export default function StoreBadges({ className = "" }: { className?: string }) {
  return (
    <div className={`flex flex-wrap items-center gap-3 ${className}`}>
      <Badge href={APP_STORE_URL} glyph={<AppleGlyph />} top="Download on the" bottom="App Store" />
      <Badge href={PLAY_STORE_URL} glyph={<PlayGlyph />} top="Get it on" bottom="Google Play" />
    </div>
  );
}
