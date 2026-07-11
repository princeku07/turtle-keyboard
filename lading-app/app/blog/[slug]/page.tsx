import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import Reveal from "../../components/Reveal";
import TurtleMark from "../../components/TurtleMark";
import WaitlistForm from "../../components/WaitlistForm";
import { PostBody } from "../render";
import { CATEGORY, CollageImage } from "../parts";
import {
  SITE_URL,
  faqItems,
  formatDate,
  getPost,
  mdToPlain,
  posts,
  readingTime,
  type Post,
} from "@/lib/blog";

export async function generateStaticParams() {
  return posts.map((post) => ({ slug: post.slug }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const post = getPost(slug);
  if (!post) return {};

  return {
    title: `${post.title} — Turtle Keyboard`,
    description: post.description,
    keywords: post.keywords,
    alternates: { canonical: `/blog/${post.slug}` },
    openGraph: {
      type: "article",
      url: `/blog/${post.slug}`,
      title: post.title,
      description: post.description,
      publishedTime: `${post.date}T00:00:00.000Z`,
      modifiedTime: `${post.updated ?? post.date}T00:00:00.000Z`,
      authors: ["Turtle Keyboard"],
      tags: post.keywords,
    },
    twitter: {
      card: "summary_large_image",
      title: post.title,
      description: post.description,
    },
  };
}

function articleJsonLd(post: Post) {
  const faqs = faqItems(post);
  return [
    ...(faqs.length
      ? [
          {
            "@context": "https://schema.org",
            "@type": "FAQPage",
            mainEntity: faqs.map((f) => ({
              "@type": "Question",
              name: mdToPlain(f.q),
              acceptedAnswer: { "@type": "Answer", text: mdToPlain(f.a) },
            })),
          },
        ]
      : []),
    {
      "@context": "https://schema.org",
      "@type": "BlogPosting",
      headline: post.title,
      description: post.description,
      url: `${SITE_URL}/blog/${post.slug}`,
      mainEntityOfPage: `${SITE_URL}/blog/${post.slug}`,
      datePublished: post.date,
      dateModified: post.updated ?? post.date,
      keywords: post.keywords.join(", "),
      author: {
        "@type": "Organization",
        name: "Turtle Keyboard",
        url: SITE_URL,
      },
      publisher: {
        "@type": "Organization",
        name: "Turtle Keyboard",
        url: SITE_URL,
      },
    },
    {
      "@context": "https://schema.org",
      "@type": "BreadcrumbList",
      itemListElement: [
        { "@type": "ListItem", position: 1, name: "turtle", item: SITE_URL },
        { "@type": "ListItem", position: 2, name: "blog", item: `${SITE_URL}/blog` },
        { "@type": "ListItem", position: 3, name: post.title },
      ],
    },
  ];
}

function AdjacentCard({
  post,
  dir,
}: {
  post: Post;
  dir: "newer" | "older";
}) {
  return (
    <Link href={`/blog/${post.slug}`} className="group block h-full">
      <article
        className={`sea-glass flex h-full items-center gap-4 rounded-2xl p-4 transition-transform duration-700 ease-out group-hover:-translate-y-1.5 ${
          dir === "older" ? "flex-row-reverse text-right" : ""
        }`}
      >
        <CollageImage post={post} className="h-14 w-14 shrink-0" rounded="rounded-xl" badge={false} />
        <div className="min-w-0">
          <span className="font-mono text-[10px] uppercase tracking-[0.22em] text-iris">
            {dir === "newer" ? "← newer" : "older →"}
          </span>
          <span className="mt-1 block font-display text-[15px] font-semibold leading-snug text-navy transition-colors duration-300 group-hover:text-iris">
            {post.title}
          </span>
        </div>
      </article>
    </Link>
  );
}

export default async function BlogPost({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const post = getPost(slug);
  if (!post) notFound();

  const idx = posts.findIndex((p) => p.slug === post.slug);
  const newer = idx > 0 ? posts[idx - 1] : undefined;
  const older = idx < posts.length - 1 ? posts[idx + 1] : undefined;
  const Cat = CATEGORY[post.tag];

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(articleJsonLd(post)).replace(/</g, "\\u003c"),
        }}
      />

      {/* header — clean editorial masthead */}
      <section className="relative mx-auto max-w-3xl px-6 pt-32 sm:pt-40">
        <Reveal>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-[13px]">
            <Link
              href="/blog"
              className="font-medium text-slate transition-colors duration-300 hover:text-navy"
            >
              ← The Logbook
            </Link>
            <span aria-hidden className="text-slate/40">/</span>
            <Link
              href={`/blog#${post.tag}`}
              className="inline-flex items-center gap-1.5 font-medium text-iris transition-colors duration-300 hover:text-iris-deep"
            >
              <Cat.Icon className="h-3.5 w-3.5" /> {Cat.name}
            </Link>
          </div>
        </Reveal>

        <Reveal delay={100}>
          <h1 className="mt-6 font-display text-[clamp(2.1rem,5vw,3.4rem)] font-semibold leading-[1.08] tracking-[-0.015em] text-navy">
            {post.title}
          </h1>
        </Reveal>

        <Reveal delay={200}>
          <p className="mt-5 text-lg leading-relaxed text-slate">{post.description}</p>
        </Reveal>

        <Reveal delay={280}>
          <div className="mt-7 flex items-center gap-3 border-y border-navy/8 py-4">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-iris/12">
              <TurtleMark className="w-5 text-iris" />
            </span>
            <div className="min-w-0 leading-tight">
              <div className="text-sm font-medium text-navy">The Turtle crew</div>
              <div className="font-mono text-[11px] text-slate">
                <time dateTime={post.date}>{formatDate(post.date)}</time> · {readingTime(post)} min read
              </div>
            </div>
          </div>
        </Reveal>
      </section>

      {/* cover collage */}
      <Reveal delay={120}>
        <div className="group mx-auto mt-8 max-w-3xl px-6">
          <CollageImage post={post} className="aspect-[2/1]" rounded="rounded-3xl" />
        </div>
      </Reveal>

      {/* body */}
      <article className="relative mx-auto mt-10 max-w-3xl px-6 pb-10">
        <PostBody blocks={post.blocks} />
      </article>

      {/* adjacent notes */}
      {(newer || older) && (
        <nav aria-label="more notes" className="mx-auto max-w-3xl px-6 pb-14">
          <div className="grid gap-4 sm:grid-cols-2">
            <div>{newer && <AdjacentCard post={newer} dir="newer" />}</div>
            <div>{older && <AdjacentCard post={older} dir="older" />}</div>
          </div>
        </nav>
      )}

      {/* the catch — waitlist CTA */}
      <section className="mx-auto max-w-3xl px-6 pb-24 sm:pb-28">
        <Reveal>
          <div className="sea-glass relative overflow-hidden rounded-[28px] px-7 py-11 text-center sm:px-12">
            <div
              aria-hidden
              className="pointer-events-none absolute inset-0 bg-[radial-gradient(70%_60%_at_50%_0%,rgba(28,107,96,0.12),transparent_70%)]"
            />
            <div className="relative">
              <TurtleMark className="mx-auto w-11 text-iris" />
              <h2 className="mt-6 font-display text-[clamp(1.7rem,3.6vw,2.3rem)] font-semibold leading-tight tracking-[-0.02em] text-navy">
                Put the <span className="slash-glow font-mono font-medium">/</span> in your pocket.
              </h2>
              <p className="mx-auto mt-4 max-w-md text-[15px] leading-relaxed text-slate">
                Turtle is in beta on iOS and Android — open source, on-device,
                and slow-and-steady by design.
              </p>
              <div className="mt-8 flex justify-center">
                <WaitlistForm center />
              </div>
            </div>
          </div>
        </Reveal>
      </section>
    </>
  );
}
