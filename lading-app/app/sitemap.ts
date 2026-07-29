import type { MetadataRoute } from "next";
import { SITE_URL, posts } from "@/lib/blog";

export default function sitemap(): MetadataRoute.Sitemap {
  // newest post date stands in for "site last touched" — keeps the
  // sitemap deterministic across builds instead of stamping build time.
  const latest = new Date(`${posts[0].date}T00:00:00.000Z`);

  return [
    {
      url: SITE_URL,
      lastModified: latest,
      changeFrequency: "weekly",
      priority: 1,
    },
    {
      url: `${SITE_URL}/blog`,
      lastModified: latest,
      changeFrequency: "weekly",
      priority: 0.8,
    },
    {
      url: `${SITE_URL}/download`,
      lastModified: latest,
      changeFrequency: "monthly",
      priority: 0.9,
    },
    {
      url: `${SITE_URL}/open-source-ai-keyboard`,
      lastModified: latest,
      changeFrequency: "monthly",
      priority: 0.9,
    },
    {
      url: `${SITE_URL}/poll-maker`,
      lastModified: latest,
      changeFrequency: "monthly",
      priority: 0.9,
    },
    ...posts.map((post) => ({
      url: `${SITE_URL}/blog/${post.slug}`,
      lastModified: new Date(`${post.updated ?? post.date}T00:00:00.000Z`),
      changeFrequency: "monthly" as const,
      priority: 0.7,
    })),
  ];
}
