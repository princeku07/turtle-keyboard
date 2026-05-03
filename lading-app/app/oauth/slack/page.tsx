"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";

/**
 * OAuth redirect landing page for Slack. Mirrors /oauth/notion.
 * Android App Links should intercept this URL and hand the request to the
 * installed Turtle Keyboard app (see /.well-known/assetlinks.json).
 */
export default function SlackOAuthRedirect() {
  return (
    <Suspense fallback={<Shell title="Loading…" />}>
      <Body />
    </Suspense>
  );
}

function Body() {
  const params = useSearchParams();
  const code = params.get("code");
  const error = params.get("error");
  const [intent, setIntent] = useState<string | null>(null);

  useEffect(() => {
    if (!code) return;
    const fallback = `turtlekeyboard://slack-redirect?code=${encodeURIComponent(code)}`;
    setIntent(fallback);
    window.location.href = fallback;
  }, [code]);

  if (error) {
    return (
      <Shell title="Slack declined">
        <p className="text-ink/70">
          Slack returned an error: <code>{error}</code>
        </p>
      </Shell>
    );
  }

  if (!code) {
    return (
      <Shell title="Missing code">
        <p className="text-ink/70">
          This page is the redirect target for Turtle Keyboard&apos;s Slack
          OAuth flow. There&apos;s nothing to see here on its own.
        </p>
      </Shell>
    );
  }

  return (
    <Shell title="Opening Turtle Keyboard…">
      <p className="text-ink/70">
        If the app didn&apos;t open automatically, install Turtle Keyboard and
        retry — or tap the link below.
      </p>
      {intent && (
        <a
          href={intent}
          className="mt-6 inline-block border-2 border-ink bg-[#15803d] px-5 py-3 text-cream shadow-[4px_4px_0_0_var(--ink)]"
        >
          Open in Turtle Keyboard
        </a>
      )}
    </Shell>
  );
}

function Shell({
  title,
  children,
}: {
  title: string;
  children?: React.ReactNode;
}) {
  return (
    <main className="grain min-h-screen bg-cream px-6 py-16 text-ink">
      <div className="mx-auto max-w-xl">
        <h1 className="text-3xl font-bold">{title}</h1>
        <div className="mt-4">{children}</div>
      </div>
    </main>
  );
}
