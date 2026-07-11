import Link from "next/link";
import { Fragment, type ReactNode } from "react";
import type { Block } from "@/lib/blog";

/**
 * Server-side renderer for the Logbook's content blocks (lib/blog.ts).
 * Inline micro-markdown: **bold** · `code` · [label](href).
 * Everything renders as server components — zero client JS per article.
 */

const INLINE = /(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`|\[[^\]]+\]\([^)]+\))/g;
const LINK = /^\[([^\]]+)\]\(([^)]+)\)$/;

export function Inline({ text }: { text: string }) {
  return (
    <>
      {text.split(INLINE).map((part, i) => {
        if (part.startsWith("**") && part.endsWith("**")) {
          return (
            <strong key={i} className="font-semibold text-navy">
              {part.slice(2, -2)}
            </strong>
          );
        }
        if (part.startsWith("*") && part.endsWith("*") && part.length > 2) {
          return <em key={i}>{part.slice(1, -1)}</em>;
        }
        if (part.startsWith("`") && part.endsWith("`")) {
          return (
            <code
              key={i}
              className="rounded-md bg-iris/[0.08] px-1.5 py-0.5 font-mono text-[0.86em] font-medium text-iris-deep"
            >
              {part.slice(1, -1)}
            </code>
          );
        }
        const m = part.match(LINK);
        if (m) {
          const [, label, href] = m;
          const cls =
            "font-medium text-iris underline decoration-iris/40 underline-offset-4 transition-colors duration-300 hover:text-iris-deep hover:decoration-iris";
          return href.startsWith("/") ? (
            <Link key={i} href={href} className={cls}>
              {label}
            </Link>
          ) : (
            <a key={i} href={href} target="_blank" rel="noreferrer" className={cls}>
              {label}
            </a>
          );
        }
        return <Fragment key={i}>{part}</Fragment>;
      })}
    </>
  );
}

function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function BlockView({ block }: { block: Block }) {
  switch (block.t) {
    case "h2":
      return (
        <h2
          id={slugify(block.text)}
          className="mt-14 scroll-mt-28 font-display text-[1.9rem] font-semibold leading-[1.15] tracking-[-0.01em] text-navy"
        >
          <Inline text={block.text} />
        </h2>
      );
    case "h3":
      return (
        <h3 className="mt-10 scroll-mt-28 font-display text-[1.4rem] font-semibold tracking-tight text-navy">
          <Inline text={block.text} />
        </h3>
      );
    case "p":
      return (
        <p className="mt-6 text-[17px] leading-[1.85] text-navy/85">
          <Inline text={block.text} />
        </p>
      );
    case "ul":
      return (
        <ul className="mt-6 space-y-3.5">
          {block.items.map((item, i) => (
            <li key={i} className="flex gap-3.5 text-[17px] leading-[1.75] text-navy/85">
              <span aria-hidden className="mt-[11px] h-1.5 w-1.5 shrink-0 rounded-full bg-iris" />
              <span>
                <Inline text={item} />
              </span>
            </li>
          ))}
        </ul>
      );
    case "quote":
      return (
        <blockquote className="mt-8 flex gap-4 rounded-2xl border-l-[3px] border-l-iris bg-iris/[0.06] px-5 py-4">
          <span aria-hidden className="text-lg leading-[1.7]">
            🐢
          </span>
          <p className="font-display text-[17px] italic leading-[1.6] text-navy/90">
            <Inline text={block.text} />
          </p>
        </blockquote>
      );
    case "code":
      return (
        <div className="sea-glass mt-8 overflow-hidden rounded-2xl">
          <div className="flex items-center gap-2 border-b border-navy/10 px-4 py-3">
            <span className="h-2.5 w-2.5 rounded-full bg-navy/15" />
            <span className="h-2.5 w-2.5 rounded-full bg-navy/15" />
            <span className="h-2.5 w-2.5 rounded-full bg-navy/15" />
            {block.file && (
              <span className="ml-2 font-mono text-xs text-slate">{block.file}</span>
            )}
            {block.label && (
              <span className="ml-auto font-mono text-[10px] uppercase tracking-widest text-iris/70">
                {block.label}
              </span>
            )}
          </div>
          <div className="overflow-x-auto p-4">
            <pre className="font-mono text-[12.5px] leading-[1.75] text-navy/90">{block.code}</pre>
          </div>
        </div>
      );
    case "table":
      return (
        <div className="mt-8 overflow-x-auto rounded-2xl border border-navy/10 bg-white-warm">
          <table className="w-full border-collapse text-left text-[14px] leading-relaxed">
            <thead>
              <tr className="bg-mist/60">
                {block.headers.map((h, i) => (
                  <th
                    key={i}
                    className="whitespace-nowrap border-b border-navy/10 px-4 py-3 font-mono text-[11px] uppercase tracking-[0.14em] text-navy/70"
                  >
                    <Inline text={h} />
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, r) => (
                <tr key={r} className={r % 2 ? "bg-sand/40" : ""}>
                  {row.map((cell, c) => (
                    <td key={c} className="border-b border-navy/5 px-4 py-3 align-top text-navy/85">
                      <Inline text={cell} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case "faq":
      return (
        <section className="mt-12">
          <h2
            id="faq"
            className="scroll-mt-28 font-display text-[1.9rem] font-semibold leading-[1.15] tracking-[-0.01em] text-navy"
          >
            Frequently asked questions
          </h2>
          <div className="mt-6 space-y-6">
            {block.items.map((item, i) => (
              <div key={i} className="sea-glass rounded-2xl px-5 py-4">
                <h3 className="text-[16px] font-semibold leading-snug tracking-tight">
                  <Inline text={item.q} />
                </h3>
                <p className="mt-2 text-[15px] leading-[1.75] text-navy/85">
                  <Inline text={item.a} />
                </p>
              </div>
            ))}
          </div>
        </section>
      );
  }
}

export function PostBody({ blocks }: { blocks: Block[] }): ReactNode {
  return (
    <div className="[&>*:first-child]:mt-0">
      {blocks.map((block, i) => (
        <BlockView key={i} block={block} />
      ))}
    </div>
  );
}
