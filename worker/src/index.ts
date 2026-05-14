/**
 * Turtle Keyboard overlay Worker. Routes are dispatched by URL path; each artifact
 * type owns a file in {@code src/routes/}. KV is the only state.
 *
 * Adding a new overlay artifact (e.g. {@code split-confirm}):
 *   1. Create {@code src/routes/split-confirm.ts} with {@code create/read/mutate} handlers
 *      and its own KV key prefix.
 *   2. Add the dispatch block in this file (mirrors the /poll block).
 *   3. Wire the Android {@code SheetView} on the client side.
 */

import { json, error, withCors } from './http';
import { createPoll, readPoll, votePoll } from './routes/poll';
import { createWyr, readWyr, answerWyr } from './routes/wyr';
import type { Env } from './types';

export default {
  async fetch(req: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    // CORS preflight — the eventual web fallback page may XHR from a different origin.
    if (req.method === 'OPTIONS') {
      return withCors(new Response(null, { status: 204 }));
    }

    const url = new URL(req.url);
    const path = url.pathname;
    const method = req.method;

    try {
      // Health check.
      if (method === 'GET' && path === '/') {
        return withCors(json({ ok: true, service: 'turtle-worker' }));
      }

      // -- /poll --------------------------------------------------------------
      // Order matters within a route family: the more specific path (/vote) is
      // checked before the bare /poll/:id read.
      const voteMatch = path.match(/^\/poll\/([a-z0-9]+)\/vote$/i);
      if (method === 'POST' && voteMatch) {
        return withCors(await votePoll(req, env, voteMatch[1]));
      }
      const readPollMatch = path.match(/^\/poll\/([a-z0-9]+)$/i);
      if (method === 'GET' && readPollMatch) {
        return withCors(await readPoll(env, readPollMatch[1]));
      }
      if (method === 'POST' && path === '/poll') {
        return withCors(await createPoll(req, env));
      }
      // -- end /poll ----------------------------------------------------------

      // -- /wyr ---------------------------------------------------------------
      const answerMatch = path.match(/^\/wyr\/([a-z0-9]+)\/answer$/i);
      if (method === 'POST' && answerMatch) {
        return withCors(await answerWyr(req, env, answerMatch[1]));
      }
      const readWyrMatch = path.match(/^\/wyr\/([a-z0-9]+)$/i);
      if (method === 'GET' && readWyrMatch) {
        return withCors(await readWyr(env, readWyrMatch[1]));
      }
      if (method === 'POST' && path === '/wyr') {
        return withCors(await createWyr(req, env));
      }
      // -- end /wyr -----------------------------------------------------------

      return withCors(error(404, 'not_found'));
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : String(e);
      console.error('Worker error:', message);
      return withCors(error(500, 'internal_error'));
    }
  },
};
