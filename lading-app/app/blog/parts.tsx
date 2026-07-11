import TurtleMark from "../components/TurtleMark";
import { readingTime, type Post, type Tag } from "@/lib/blog";

/* ────────────────────────────────────────────────────────────────
   shared blog furniture — category system, collage art, byline.
   used by the index (/blog) and every article (/blog/[slug]).
   ──────────────────────────────────────────────────────────────── */

export type Cat = {
  name: string;
  blurb: string;
  /** collage placeholder gradient (shown until the .jpg is dropped in) */
  grad: string;
  Icon: (p: { className?: string }) => React.ReactElement;
};

export const CATEGORY: Record<Tag, Cat> = {
  guides: {
    name: "Guides",
    blurb: "Get-it-done walkthroughs for polls, quizzes, and the keyboard itself.",
    grad: "from-[#cbd0f1] to-[#cfe6ee]",
    Icon: CompassIcon,
  },
  product: {
    name: "Product",
    blurb: "The thinking behind the slash — history, design, and how it works.",
    grad: "from-[#ddccec] to-[#f3dbe6]",
    Icon: SparkIcon,
  },
  privacy: {
    name: "Privacy",
    blurb: "Where your keystrokes go, and why ours never leave the shell.",
    grad: "from-[#c4c9ee] to-[#e4e1f4]",
    Icon: ShieldIcon,
  },
  developers: {
    name: "For Developers",
    blurb: "Building the keyboard: MCP, on-device LLMs, and native trade-offs.",
    grad: "from-[#c8e2ea] to-[#ced3f1]",
    Icon: CodeIcon,
  },
};

/** section render order for the index */
export const CAT_ORDER: Tag[] = ["guides", "product", "developers", "privacy"];

/** collage art lives at /blog/<slug>.jpg — a bg-image so a missing file
 *  degrades to the gradient placeholder instead of a broken-image icon. */
export function imageUrl(post: Post): string {
  return `/blog/${post.slug}.jpg`;
}

export function CollageImage({
  post,
  className = "",
  rounded = "rounded-2xl",
  badge = true,
}: {
  post: Post;
  className?: string;
  rounded?: string;
  badge?: boolean;
}) {
  const cat = CATEGORY[post.tag];
  return (
    <div className={`relative overflow-hidden ${rounded} bg-gradient-to-br ${cat.grad} ${className}`}>
      <TurtleMark className="absolute left-1/2 top-1/2 w-1/4 -translate-x-1/2 -translate-y-1/2 text-white/45" />
      <div
        className="relative h-full w-full bg-cover bg-center transition-transform duration-[900ms] ease-out group-hover:scale-[1.04]"
        style={{ backgroundImage: `url(${imageUrl(post)})` }}
      />
      {badge && (
        <span className="absolute bottom-2.5 right-2.5 rounded-full bg-white/85 px-2.5 py-1 font-mono text-[10px] font-semibold uppercase tracking-wider text-navy backdrop-blur-sm">
          {cat.name}
        </span>
      )}
    </div>
  );
}

export function Byline({ post, className = "" }: { post: Post; className?: string }) {
  return (
    <div className={`flex items-center gap-2.5 text-[13px] ${className}`}>
      <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-iris/12">
        <TurtleMark className="w-4 text-iris" />
      </span>
      <span className="font-medium text-navy">The Turtle crew</span>
      <span className="text-slate/40">·</span>
      <span className="font-mono text-xs text-slate">{readingTime(post)} min</span>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────
   line-art icons (inherit currentColor)
   ──────────────────────────────────────────────────────────────── */
export function GridIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinejoin="round" aria-hidden>
      <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
    </svg>
  );
}
export function SparkIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="currentColor" aria-hidden>
      <path d="M12 2l1.7 5.6L19 9.3l-4.5 3 1.6 5.7L12 14.6 7.9 18l1.6-5.7L5 9.3l5.3-1.7L12 2z" />
    </svg>
  );
}
export function FlameIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="currentColor" aria-hidden>
      <path d="M13 2s.7 3-1.5 5.5S8 11 8 14a4 4 0 0 0 8 0c0-1.5-.6-2.7-1.2-3.6.3 1.5-.5 2.6-1.3 2.6.6-1.6.2-3.9-1-5.6C11.4 5.7 13 4 13 2z" />
    </svg>
  );
}
export function CompassIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="M15.5 8.5l-2 5-5 2 2-5 5-2z" fill="currentColor" stroke="none" />
    </svg>
  );
}
export function ShieldIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 3l7 3v5c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6l7-3Z" />
      <path d="m9 12 2 2 4-4" />
    </svg>
  );
}
export function CodeIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M8.5 8.5 5 12l3.5 3.5M15.5 8.5 19 12l-3.5 3.5M13 6l-2 12" />
    </svg>
  );
}
