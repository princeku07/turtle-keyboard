/**
 * Single source of truth for app-store presence. When the iOS App Store
 * listing goes live, set APP_STORE_URL and every badge, link, and schema
 * `downloadUrl` across the site updates at once — no hunting for literals.
 *
 * Android ships on Google Play today; iOS is in review ("coming soon"), so
 * APP_STORE_URL is null until the listing exists. Components render a
 * coming-soon state for any store whose URL is null rather than linking
 * nowhere.
 */

export const GITHUB_URL = "https://github.com/princeku07/turtle-keyboard";

export const ANDROID_PACKAGE = "com.prince.turtlekeyboard";
export const IOS_BUNDLE_ID = "com.prince.turtlekeyboard";

export const PLAY_STORE_URL: string | null =
  `https://play.google.com/store/apps/details?id=${ANDROID_PACKAGE}`;

/** null until the App Store listing is published. */
export const APP_STORE_URL: string | null = null;

export type StoreKey = "play" | "app";

export function storeUrl(key: StoreKey): string | null {
  return key === "play" ? PLAY_STORE_URL : APP_STORE_URL;
}

/** downloadUrl values for SoftwareApplication JSON-LD (only the live ones). */
export function downloadUrls(): string[] {
  return [PLAY_STORE_URL, APP_STORE_URL].filter((u): u is string => !!u);
}

/** "iOS, Android" once both are live; "Android" while iOS is pending. */
export function liveOperatingSystems(): string {
  const os: string[] = [];
  if (APP_STORE_URL) os.push("iOS");
  if (PLAY_STORE_URL) os.push("Android");
  return os.join(", ") || "iOS, Android";
}

/**
 * Shared MobileApplication JSON-LD so every page that describes the app emits
 * one identical entity (consistent name/description/links reads as a single
 * app to Google and AI answer engines). Caller passes the page's absolute URL.
 */
export function appJsonLd(pageUrl: string) {
  const dl = downloadUrls();
  return {
    "@context": "https://schema.org",
    "@type": "MobileApplication",
    name: "Turtle Keyboard",
    applicationCategory: "UtilitiesApplication",
    operatingSystem: "iOS, Android",
    url: pageUrl,
    sameAs: [GITHUB_URL, ...dl],
    ...(dl.length ? { downloadUrl: dl, installUrl: dl } : {}),
    isAccessibleForFree: true,
    license: "https://opensource.org/license/mit",
    offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
    description:
      "Open-source (MIT) AI keyboard for iOS and Android: slash commands that bring polls, quizzes, AI, and connected tools like GitHub and Notion into any chat.",
  };
}
