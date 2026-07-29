import type { Metadata } from "next";
import Link from "next/link";
import Reveal from "../components/Reveal";
import {
  CATEGORY,
  CAT_ORDER,
  CollageImage,
  Byline,
  GridIcon,
  SparkIcon,
  FlameIcon,
} from "./parts";
import { SITE_URL, posts, readingTime, type Post, type Tag } from "@/lib/blog";

export const metadata: Metadata = {
  title: "Blog — Turtle Keyboard | notes on AI keyboards, slash commands & on-device AI",
  description:
    "The Turtle logbook: field notes on AI keyboards, slash commands, on-device AI, keyboard privacy, and building an open interface for your digital life.",
  alternates: { canonical: "/blog" },
  openGraph: {
    type: "website",
    url: "/blog",
    title: "The Turtle Logbook",
    description:
      "Field notes on AI keyboards, slash commands, on-device AI, and keyboard privacy — from the open-source Turtle Keyboard crew.",
  },
  twitter: {
    card: "summary_large_image",
    title: "The Turtle Logbook",
    description:
      "Field notes on AI keyboards, slash commands, on-device AI, and keyboard privacy.",
  },
};

/* ────────────────────────────────────────────────────────────────
   structured data
   ──────────────────────────────────────────────────────────────── */
function blogJsonLd() {
  return {
    "@context": "https://schema.org",
    "@type": "Blog",
    name: "The Turtle Logbook",
    url: `${SITE_URL}/blog`,
    description:
      "Field notes on AI keyboards, slash commands, on-device AI, and keyboard privacy.",
    publisher: { "@type": "Organization", name: "Turtle Keyboard", url: SITE_URL },
    blogPost: posts.map((p) => ({
      "@type": "BlogPosting",
      headline: p.title,
      url: `${SITE_URL}/blog/${p.slug}`,
      datePublished: p.date,
      description: p.description,
    })),
  };
}

/* ── Spotlight (featured) ───────────────────────────────────────── */
function Spotlight({ post }: { post: Post }) {
  return (
    <Link href={`/blog/${post.slug}`} className="group block">
      <div className="mb-4 flex items-center gap-2 font-mono text-xs font-semibold uppercase tracking-[0.16em] text-iris">
        <SparkIcon className="h-4 w-4" /> Spotlight
      </div>
      <CollageImage post={post} className="aspect-[16/10]" rounded="rounded-3xl" />
      <h3 className="mt-5 font-display text-[1.7rem] font-semibold leading-[1.12] tracking-[-0.01em] text-navy">
        {post.title}
      </h3>
      <Byline post={post} className="mt-3" />
    </Link>
  );
}

/* ── Trending (compact list) ────────────────────────────────────── */
function Trending({ items }: { items: Post[] }) {
  return (
    <div>
      <div className="mb-4 flex items-center gap-2 font-mono text-xs font-semibold uppercase tracking-[0.16em] text-violet">
        <FlameIcon className="h-4 w-4" /> Trending
      </div>
      <div className="flex flex-col divide-y divide-navy/8">
        {items.map((post) => (
          <Link
            key={post.slug}
            href={`/blog/${post.slug}`}
            className="group flex items-start gap-4 py-4 first:pt-0"
          >
            <CollageImage post={post} className="h-[62px] w-[62px] shrink-0" rounded="rounded-xl" badge={false} />
            <div className="min-w-0">
              <h4 className="font-display text-[15px] font-semibold leading-snug text-navy transition-colors duration-300 group-hover:text-iris">
                {post.title}
              </h4>
              <div className="mt-1.5 font-mono text-[11px] text-slate">
                {CATEGORY[post.tag].name} · {readingTime(post)} min
              </div>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}

/* ── article card ───────────────────────────────────────────────── */
function ArticleCard({ post }: { post: Post }) {
  return (
    <Link href={`/blog/${post.slug}`} className="group block">
      <CollageImage post={post} className="aspect-[16/10]" />
      <h3 className="mt-4 font-display text-[1.3rem] font-semibold leading-[1.15] tracking-[-0.01em] text-navy transition-colors duration-300 group-hover:text-iris">
        {post.title}
      </h3>
      <p className="mt-2 line-clamp-2 text-[14px] leading-relaxed text-slate">
        {post.description}
      </p>
      <Byline post={post} className="mt-3" />
    </Link>
  );
}

/* ── category section ───────────────────────────────────────────── */
function CategorySection({ tag, items }: { tag: Tag; items: Post[] }) {
  const cat = CATEGORY[tag];
  if (items.length === 0) return null;
  return (
    <section id={tag} className="scroll-mt-28 border-t border-navy/8 pt-12">
      <div className="mb-8 flex items-end justify-between gap-4">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-iris/12 text-iris">
            <cat.Icon className="h-5 w-5" />
          </span>
          <div>
            <h2 className="font-display text-2xl font-semibold tracking-tight text-navy">{cat.name}</h2>
            <p className="mt-0.5 hidden text-sm text-slate sm:block">{cat.blurb}</p>
          </div>
        </div>
      </div>
      <div className="grid gap-x-6 gap-y-10 sm:grid-cols-2">
        {items.map((post) => (
          <ArticleCard key={post.slug} post={post} />
        ))}
      </div>
    </section>
  );
}

/* ── sidebar ────────────────────────────────────────────────────── */
function Sidebar() {
  const links: Array<{ href: string; label: string }> = [
    { href: "#top", label: "All Articles" },
    ...CAT_ORDER.map((t) => ({ href: `#${t}`, label: CATEGORY[t].name })),
  ];
  return (
    <aside className="lg:sticky lg:top-28 lg:self-start">
      <div className="mb-4 hidden items-center gap-2 font-mono text-[11px] font-semibold uppercase tracking-[0.16em] text-slate lg:flex">
        <GridIcon className="h-4 w-4" /> Categories
      </div>
      <nav className="no-scrollbar -mx-6 flex gap-2 overflow-x-auto px-6 pb-1 lg:mx-0 lg:flex-col lg:gap-1.5 lg:overflow-visible lg:px-0">
        {links.map((l, i) => (
          <a
            key={l.href}
            href={l.href}
            className={`shrink-0 rounded-full px-4 py-2 text-sm font-medium transition-colors duration-300 lg:rounded-xl ${
              i === 0
                ? "bg-navy text-sand"
                : "bg-white/60 text-slate hover:bg-white/90 hover:text-navy"
            }`}
          >
            {l.label}
          </a>
        ))}
      </nav>
    </aside>
  );
}

/* ────────────────────────────────────────────────────────────────
   page
   ──────────────────────────────────────────────────────────────── */
export default function BlogIndex() {
  const spotlight = posts[0];
  const trending = posts.slice(1, 4);
  const featuredSlugs = new Set([spotlight, ...trending].map((p) => p.slug));
  const byTag = (tag: Tag) => posts.filter((p) => p.tag === tag && !featuredSlugs.has(p.slug));

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(blogJsonLd()).replace(/</g, "\\u003c"),
        }}
      />

      <div id="top" className="mx-auto max-w-6xl px-6 pt-32 pb-24 sm:pt-40 sm:pb-32">
        <Reveal>
          <div className="flex items-center gap-3 font-mono text-[11px] uppercase tracking-[0.24em] text-slate">
            <span className="h-px w-8 bg-iris/50" /> the turtle logbook
          </div>
          <h1 className="mt-4 font-display text-[clamp(2.8rem,6vw,4.6rem)] font-semibold leading-[1.0] tracking-[-0.015em] text-navy">
            Field notes
          </h1>
          <p className="mt-4 max-w-xl text-lg leading-relaxed text-slate">
            On AI keyboards, slash commands, on-device AI, and building an open
            interface for your digital life.
          </p>
        </Reveal>

        <div className="mt-14 grid gap-x-12 gap-y-10 lg:grid-cols-[190px_1fr]">
          <Sidebar />

          <div className="min-w-0">
            {/* Spotlight + Trending */}
            <Reveal>
              <div className="grid gap-x-12 gap-y-12 border-b border-navy/8 pb-14 lg:grid-cols-[1.35fr_1fr]">
                {spotlight && <Spotlight post={spotlight} />}
                {trending.length > 0 && <Trending items={trending} />}
              </div>
            </Reveal>

            {/* Category sections */}
            <div className="mt-14 flex flex-col gap-14">
              {CAT_ORDER.map((tag) => (
                <Reveal key={tag}>
                  <CategorySection tag={tag} items={byTag(tag)} />
                </Reveal>
              ))}
            </div>

            <Reveal>
              <p className="mt-16 border-t border-navy/8 pt-10 text-center font-mono text-[12px] uppercase tracking-[0.2em] text-slate/70">
                new notes surface slowly · steadily ·{" "}
                <Link href="/#waitlist" className="text-iris transition-colors duration-300 hover:text-iris-deep">
                  join the waitlist ↗
                </Link>
              </p>
            </Reveal>
          </div>
        </div>
      </div>
    </>
  );
}
