import HeroClient from "./components/HeroClient";

export default function Home() {
  return (
    <main className="min-h-screen w-full text-foam overflow-x-clip">
      <Nav />
      <HeroClient />
      <Waitlist />
      <Footer />
    </main>
  );
}

function Nav() {
  return (
    <header className="sticky top-0 z-40 backdrop-blur-md">
      <div className="mx-auto max-w-[1400px] px-4 sm:px-6 py-3 sm:py-4 flex items-center justify-between gap-3">
        <a href="#" className="flex items-center gap-2 font-sans font-semibold text-base sm:text-lg shrink-0 tracking-tight text-foam">
          <span className="text-xl sm:text-2xl leading-none">🐢</span>
          turtle
        </a>
        <nav className="hidden md:flex items-center gap-7 font-mono text-sm text-foam/65">
          <a href="#waitlist" className="hover:text-foam transition-colors">waitlist</a>
          <a
            href="https://github.com/princeku07/turtle-keyboard"
            target="_blank"
            rel="noreferrer"
            className="hover:text-foam transition-colors"
          >
            github ↗
          </a>
        </nav>
        <a
          href="#waitlist"
          className="font-mono text-xs sm:text-sm font-semibold bg-foam text-ink px-3 sm:px-4 py-2 rounded-full hover:bg-cyan transition-colors whitespace-nowrap"
        >
          join →
        </a>
      </div>
    </header>
  );
}

function Waitlist() {
  return (
    <section id="waitlist" className="relative">
      <div className="mx-auto max-w-[900px] px-5 sm:px-6 py-28 sm:py-36 text-center">
        <h2 className="font-sans font-semibold tracking-[-0.04em] leading-[0.9] text-[clamp(2.4rem,6vw,4.5rem)] text-foam">
          slow and steady.
          <br />
          <span className="text-cyan">wins the race.</span>
        </h2>
        <form className="mt-12 flex flex-col sm:flex-row gap-3 max-w-lg mx-auto">
          <input
            type="email"
            placeholder="you@somewhere.cool"
            className="flex-1 bg-white/[0.08] backdrop-blur-md text-foam placeholder:text-foam/40 hairline rounded-full px-5 py-4 font-mono text-base focus:outline-none focus:bg-white/[0.14] focus:border-cyan/60 transition-colors"
          />
          <button
            type="submit"
            className="bg-cyan text-ink font-mono font-semibold px-6 py-4 rounded-full hover:bg-foam transition-colors"
          >
            grab my spot →
          </button>
        </form>
        <p className="mt-6 font-mono text-xs text-foam/45">
          android alpha rolling out monthly · ios next
        </p>
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer className="border-t border-white/10">
      <div className="mx-auto max-w-[1400px] px-6 py-10 flex flex-col md:flex-row items-center justify-between gap-4 font-mono text-sm text-foam/55">
        <div className="flex items-center gap-3">
          <span className="text-xl leading-none">🐢</span>
          <span className="font-semibold text-foam">turtle</span>
          <span>© 2026 · MIT</span>
        </div>
        <div className="flex items-center gap-6">
          <a href="#" className="hover:text-foam transition-colors">github</a>
          <a href="#" className="hover:text-foam transition-colors">twitter</a>
          <a href="#" className="hover:text-foam transition-colors">privacy</a>
        </div>
      </div>
    </footer>
  );
}
