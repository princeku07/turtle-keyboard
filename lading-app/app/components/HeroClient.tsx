export default function HeroClient() {
  return (
    <section className="relative flex flex-col min-h-[calc(100dvh-64px)] md:min-h-[820px] overflow-hidden">
      {/* reef photo — blurred + feathered. sits behind everything. */}
      <div className="reef-overlay" />

      {/* CENTER — eyebrow, wordmark, tagline, CTA. all centered, generous air. */}
      <div className="relative flex-1 min-h-0 flex flex-col items-center justify-center px-5 sm:px-6 text-center">
        <div className="font-mono text-[10px] sm:text-xs uppercase tracking-[0.28em] text-foam/55 mb-8 sm:mb-10">
          beta · ios + android · open source
        </div>

        <h1 className="font-sans font-semibold tracking-[-0.055em] leading-[0.85] text-[clamp(5rem,18vw,15rem)] text-foam select-none drop-shadow-[0_4px_30px_rgba(0,0,0,0.45)]">
          turtle
        </h1>

        <p className="mt-10 sm:mt-12 font-sans text-[clamp(1.05rem,1.6vw,1.4rem)] font-light tracking-tight text-foam/80 max-w-xl">
          every model. one slash. any app.
        </p>

        <a
          href="#waitlist"
          className="mt-12 sm:mt-14 inline-flex items-center gap-2 font-mono text-sm font-semibold bg-foam text-ink px-5 py-3 rounded-full hover:bg-cyan transition-colors"
        >
          join the waitlist
          <span aria-hidden>→</span>
        </a>
      </div>

      {/* subtle scroll affordance — sits at the bottom of the hero,
          tells the eye there's more below without breaking the composition */}
      <div className="relative flex justify-center pb-8 sm:pb-10">
        <a
          href="#waitlist"
          className="inline-flex flex-col items-center gap-2 text-foam/40 hover:text-foam/70 transition-colors"
          aria-label="Scroll to waitlist"
        >
          <span className="font-mono text-[10px] uppercase tracking-[0.28em]">scroll</span>
          <span className="animate-bounce-soft text-xl leading-none">↓</span>
        </a>
      </div>
    </section>
  );
}
