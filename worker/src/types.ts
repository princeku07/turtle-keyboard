/**
 * Worker bindings + artifact shapes. Kept in one place so route handlers and the
 * dispatcher pull from the same definitions without circular imports.
 */

export interface Env {
  /** KV namespace declared in wrangler.toml. Stores all overlay-sheet artifacts. */
  TURTLE_KV: KVNamespace;
}

/** Stored under {@code poll:<id>} in KV. {@link Poll.voters} is internal-only and
 *  stripped from read responses. */
export interface Poll {
  id: string;
  createdAt: number;
  question: string;
  options: PollOption[];
  voters: string[];
}

export interface PollOption {
  label: string;
  votes: number;
}
