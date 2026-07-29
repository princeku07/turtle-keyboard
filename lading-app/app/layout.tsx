import type { Metadata } from "next";
import { Geist, JetBrains_Mono, Fraunces } from "next/font/google";
import "./globals.css";

// Coastal Minimalism type pairing (design.txt §3):
// Geist for headings & copy, JetBrains Mono to anchor /commands.
const geist = Geist({
  variable: "--font-geist",
  subsets: ["latin"],
});

const jetbrains = JetBrains_Mono({
  variable: "--font-jetbrains",
  subsets: ["latin"],
});

// Fraunces — a warm, high-contrast serif used for the Logbook's editorial
// headings. Scoped to the blog so the product pages stay geometric-sans.
const fraunces = Fraunces({
  variable: "--font-fraunces",
  subsets: ["latin"],
  axes: ["opsz", "SOFT"],
});

export const metadata: Metadata = {
  // Resolves any relative URLs used by route-segment OG images (e.g. the
  // dynamically-rendered /poll/[id]/opengraph-image) to absolute URLs that
  // social previewers — WhatsApp, iMessage, Twitter, Slack — can fetch.
  metadataBase: new URL("https://www.turtlekeyboard.com"),
  title: "Turtle Keyboard — slash is the new hey siri.",
  description:
    "The open-source AI keyboard that turns any text field into a command line. Type a slash to bring polls, quizzes, AI, and your favorite tools into any chat.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geist.variable} ${jetbrains.variable} ${fraunces.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
