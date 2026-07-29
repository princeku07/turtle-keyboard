import Link from "next/link";
import TurtleMark from "../components/TurtleMark";

const GITHUB_URL = "https://github.com/princeku07/turtle-keyboard";

/**
 * Shared chrome for the Logbook (/blog and /blog/[slug]) — the same
 * floating sea-glass pill as the landing page, in the image-derived palette.
 */
export default function BlogLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen w-full overflow-x-clip text-navy">
      <header className="fixed inset-x-0 top-0 z-50 px-4 pt-4 sm:px-6">
        <div className="sea-glass mx-auto flex max-w-5xl items-center justify-between rounded-full py-2.5 pl-5 pr-5 sm:pl-6">
          <Link href="/" className="flex items-center gap-2.5 text-navy">
            <TurtleMark className="w-8 text-navy" />
            <span className="text-[17px] font-semibold tracking-tight">Turtle Keyboard</span>
            <span className="hidden rounded-full border border-iris/40 px-2 py-0.5 font-mono text-[10px] text-iris sm:inline">
              logbook
            </span>
          </Link>
          <nav className="hidden items-center gap-8 text-[14px] font-medium text-slate md:flex">
            <Link href="/blog" className="text-navy">Blog</Link>
            <Link href="/#current" className="transition-colors duration-300 hover:text-navy">How it works</Link>
            <Link href="/#deep" className="transition-colors duration-300 hover:text-navy">Privacy</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              GitHub
            </a>
          </nav>
          <Link
            href="/#waitlist"
            className="btn-grad rounded-full px-4 py-2 text-[13px] font-semibold"
          >
            Grab My Spot →
          </Link>
        </div>
      </header>

      <main>{children}</main>

      <footer className="relative border-t border-navy/10">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-7 px-6 py-14 text-center">
          <Link href="/" aria-label="turtle — home">
            <TurtleMark className="w-10 text-iris" />
          </Link>
          <nav className="flex flex-wrap items-center justify-center gap-x-7 gap-y-3 font-mono text-sm text-slate">
            <Link href="/" className="transition-colors duration-300 hover:text-navy">home</Link>
            <Link href="/blog" className="transition-colors duration-300 hover:text-navy">blog</Link>
            <a href={GITHUB_URL} target="_blank" rel="noreferrer" className="transition-colors duration-300 hover:text-navy">
              github ↗
            </a>
            <Link href="/#waitlist" className="transition-colors duration-300 hover:text-navy">waitlist</Link>
          </nav>
          <p className="font-mono text-xs text-slate/75">🐢 turtle © 2026 · MIT</p>
        </div>
      </footer>
    </div>
  );
}
