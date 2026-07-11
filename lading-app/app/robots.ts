import type { MetadataRoute } from "next";
import { SITE_URL } from "@/lib/blog";

// utility routes kept out of every crawler: oauth callbacks, the design mock,
// the profile stub. /poll stays crawlable so shared links keep their previews.
const DISALLOW = ["/oauth/", "/me", "/mock"];

// Answer-engine + AI-search crawlers we explicitly welcome. Being cited by
// these is the point of the whole blog, so we opt in by name rather than
// relying on the wildcard — some respect only their own user-agent block.
const AI_BOTS = [
  "GPTBot", // OpenAI training
  "OAI-SearchBot", // ChatGPT Search index
  "ChatGPT-User", // ChatGPT live browsing
  "PerplexityBot", // Perplexity index
  "Perplexity-User", // Perplexity live fetch
  "ClaudeBot", // Anthropic training
  "Claude-User", // Claude live browsing
  "Claude-SearchBot", // Claude search index
  "Google-Extended", // Gemini / AI Overviews training
  "Applebot-Extended", // Apple Intelligence
  "CCBot", // Common Crawl (feeds many LLMs)
  "Bingbot", // Copilot / Bing
];

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      { userAgent: "*", allow: "/", disallow: DISALLOW },
      ...AI_BOTS.map((userAgent) => ({ userAgent, allow: "/", disallow: DISALLOW })),
    ],
    sitemap: `${SITE_URL}/sitemap.xml`,
    host: SITE_URL,
  };
}
