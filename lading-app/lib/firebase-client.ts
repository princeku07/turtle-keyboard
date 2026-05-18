/**
 * Firebase Web SDK singleton for the landing-app's client-side poll voting.
 * SSR reads go through `lib/realtimedb.ts` (REST, anonymous, no auth needed).
 * This module is only imported from client components — never run server-side
 * (the SDK keeps in-memory auth state that doesn't transfer across requests).
 *
 * Anonymous Auth flow:
 *   - User lands on /poll/<id> → `ensureAnonAuth()` runs from PollOptions's
 *     useEffect → if no currentUser, signInAnonymously() mints a uid scoped to
 *     this browser install (cleared on cache clear / incognito).
 *   - That uid is the dedup key for voting — matches the in-app pattern that
 *     uses Firebase Auth uid. Trade-off vs in-app: anon uid is per-browser
 *     rather than per-person, so the same human can vote twice from a phone
 *     browser + a desktop browser. Acceptable for the casual social-poll UX.
 */
import { initializeApp, getApps, getApp, type FirebaseApp } from "firebase/app";
import { getAuth, signInAnonymously, type Auth } from "firebase/auth";
import { getDatabase, type Database } from "firebase/database";

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID
    ? `${process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID}.firebaseapp.com`
    : undefined,
  databaseURL: process.env.NEXT_PUBLIC_FIREBASE_DATABASE_URL,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
};

const app: FirebaseApp = getApps().length ? getApp() : initializeApp(firebaseConfig);

export const auth: Auth = getAuth(app);
export const db: Database = getDatabase(app);

let pendingSignIn: Promise<string> | null = null;

/**
 * Returns the current Firebase Auth uid (anonymous or otherwise), signing in
 * anonymously if there's no session. Idempotent — concurrent calls share the
 * same in-flight signInAnonymously() promise so we never start two sign-ins.
 */
export async function ensureAnonAuth(): Promise<string> {
  if (auth.currentUser) return auth.currentUser.uid;
  if (pendingSignIn) return pendingSignIn;
  pendingSignIn = signInAnonymously(auth)
    .then(cred => cred.user.uid)
    .finally(() => {
      pendingSignIn = null;
    });
  return pendingSignIn;
}
