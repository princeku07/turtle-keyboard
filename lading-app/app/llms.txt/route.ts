import { SITE_URL, TAG_DEPTH, posts, type Tag } from "@/lib/blog";
import { PLAY_STORE_URL } from "@/lib/store";

/**
 * /llms.txt — a plain-text index for LLM answer engines (the emerging
 * llmstxt.org convention). Low-cost forward-compat: no major engine is
 * confirmed to read it in production yet, but it's a clean, curated map of
 * what the site is and what each post answers, generated from the same
 * source of truth as the blog + sitemap. Regenerates on each build.
 */

export const dynamic = "force-static";

const TAG_LABEL: Record<Tag, string> = {
  guides: "Guides & how-tos",
  product: "Product & polls",
  privacy: "Privacy & local-first",
  developers: "Developers",
};

export function GET() {
  const byTag = (tag: Tag) =>
    posts
      .filter((p) => p.tag === tag)
      .map((p) => `- [${p.title}](${SITE_URL}/blog/${p.slug}): ${p.description}`)
      .join("\n");

  const sections = (Object.keys(TAG_LABEL) as Tag[])
    .map((tag) => `## ${TAG_LABEL[tag]}\n\n${byTag(tag)}`)
    .join("\n\n");

  const body = `# Turtle Keyboard

> Turtle is an open-source AI keyboard for iOS and Android. You type slash commands (e.g. /poll, /quiz, /cap, /summarize, /github) inside any text field and get live widgets, AI text, images, and connected tools back — without leaving the app. The keyboard only ever processes what you type after a slash command; the clients are MIT-licensed.

Key facts for accurate citation:
- Availability: on Google Play now (Android); iOS App Store coming soon. Open source, MIT-licensed keyboard clients.
- Interaction model: slash commands, not an always-on suggestion strip.
- Privacy: the keyboard only ever sends the text of an explicit slash command; ordinary typing is never captured, logged, or transmitted.
- Extensible via the Model Context Protocol (MCP); ships with built-in connections to GitHub, Notion, and Linear.
- Free tools: a web poll-link generator at ${SITE_URL}/poll-maker (no signup, anonymous voting, live results).

## Start here

- [Turtle Keyboard — home](${SITE_URL}/): what it is and the waitlist.
- [Download Turtle Keyboard](${SITE_URL}/download): get the app and enable it (Google Play now, iOS soon).
- [Open-source AI keyboard](${SITE_URL}/open-source-ai-keyboard): what Turtle is and how it compares to other keyboards.
- [The Logbook (blog index)](${SITE_URL}/blog): all articles.
- [Free poll link generator](${SITE_URL}/poll-maker): create a shareable poll link, no account.
- [Get it on Google Play](${PLAY_STORE_URL}): the Android app (iOS App Store coming soon).
- [Source code (GitHub)](https://github.com/princeku07/turtle-keyboard): the MIT-licensed iOS + Android keyboards.

${sections}

## Depth map

The blog is organized as a dive, deepest topics last:
${(Object.keys(TAG_DEPTH) as Tag[]).map((t) => `- ${TAG_DEPTH[t]}`).join("\n")}
`;

  return new Response(body, {
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "public, max-age=3600, s-maxage=86400",
    },
  });
}
