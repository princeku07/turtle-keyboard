/** minimalist line-art turtle — shared brand mark (nav, footer, blog chrome) */
export default function TurtleMark({ className = "" }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 46 30"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      {/* shell dome */}
      <path d="M9 20a14 12 0 0 1 28 0" />
      {/* shell plates */}
      <path d="M16 20c0-5 2.6-8.2 7-8.2S30 15 30 20" opacity="0.55" />
      {/* belly */}
      <path d="M5 20h33" />
      {/* head + tail + legs */}
      <circle cx="41" cy="17.5" r="3" />
      <path d="M5 20l-2.5 3.5" />
      <path d="M13 20l-2.5 5M31 20l2.5 5" />
    </svg>
  );
}
