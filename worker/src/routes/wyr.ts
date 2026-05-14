/**
 * /wyr endpoints — create, read, answer. KV key prefix: {@code wyr:<id>}. 90-day TTL.
 *
 * <p>Same shape as {@code routes/poll.ts}. Each artifact has up to 10 dilemma questions
 * (Android caps at 5) and a {@code players} map keyed by opaque device id, value is the
 * player's answer array. Two unique devices is the natural game cap; a third+ POST
 * returns {@code game_full}.
 */

import { json, error } from '../http';
import { nanoid } from '../id';
import type { Env } from '../types';

const KV_PREFIX = 'wyr';
const TTL_SECONDS = 60 * 60 * 24 * 90;

const MAX_QUESTIONS = 10;
const MIN_QUESTIONS = 2;
const MAX_OPTION_LEN = 50;
const MAX_PLAYERS = 2;

const SHAREABLE_HOST = 'https://www.turtlekeyboard.com';

interface Question { a: string; b: string; }
interface Wyr {
  id: string;
  createdAt: number;
  questions: Question[];
  players: Record<string, string[]>;
}

export async function createWyr(req: Request, env: Env): Promise<Response> {
  let body: { questions?: unknown };
  try {
    body = (await req.json()) as { questions?: unknown };
  } catch {
    return error(400, 'invalid_json');
  }

  if (!Array.isArray(body.questions)) return error(400, 'invalid_questions');
  const questions: Question[] = [];
  for (const q of body.questions as unknown[]) {
    if (typeof q !== 'object' || q === null) continue;
    const a = typeof (q as { a?: unknown }).a === 'string'
      ? ((q as { a: string }).a).trim() : '';
    const b = typeof (q as { b?: unknown }).b === 'string'
      ? ((q as { b: string }).b).trim() : '';
    if (!a || !b) continue;
    questions.push({
      a: a.length > MAX_OPTION_LEN ? a.slice(0, MAX_OPTION_LEN) : a,
      b: b.length > MAX_OPTION_LEN ? b.slice(0, MAX_OPTION_LEN) : b,
    });
  }
  if (questions.length < MIN_QUESTIONS || questions.length > MAX_QUESTIONS) {
    return error(400, 'invalid_questions');
  }

  const id = nanoid();
  const wyr: Wyr = {
    id,
    createdAt: Date.now(),
    questions,
    players: {},
  };
  await env.TURTLE_KV.put(`${KV_PREFIX}:${id}`, JSON.stringify(wyr), {
    expirationTtl: TTL_SECONDS,
  });
  return json({ id, url: `${SHAREABLE_HOST}/wyr/${id}` });
}

export async function readWyr(env: Env, id: string): Promise<Response> {
  const wyr = await loadWyr(env, id);
  if (!wyr) return error(404, 'wyr_not_found');
  return json(wyr);
}

export async function answerWyr(req: Request, env: Env, id: string): Promise<Response> {
  const deviceId = req.headers.get('x-turtle-device')?.trim() ?? '';
  if (!deviceId) return error(400, 'missing_device_id');

  let body: { answers?: unknown };
  try {
    body = (await req.json()) as { answers?: unknown };
  } catch {
    return error(400, 'invalid_json');
  }
  if (!Array.isArray(body.answers)) return error(400, 'invalid_answers');

  const wyr = await loadWyr(env, id);
  if (!wyr) return error(404, 'wyr_not_found');

  if (body.answers.length !== wyr.questions.length) return error(400, 'invalid_answers');
  const answers: string[] = [];
  for (const a of body.answers as unknown[]) {
    if (a !== 'A' && a !== 'B') return error(400, 'invalid_answers');
    answers.push(a);
  }

  if (wyr.players[deviceId]) return error(409, 'already_voted');
  if (Object.keys(wyr.players).length >= MAX_PLAYERS) return error(409, 'game_full');

  wyr.players[deviceId] = answers;
  await env.TURTLE_KV.put(`${KV_PREFIX}:${id}`, JSON.stringify(wyr), {
    expirationTtl: TTL_SECONDS,
  });
  return json(wyr);
}

async function loadWyr(env: Env, id: string): Promise<Wyr | null> {
  const raw = await env.TURTLE_KV.get(`${KV_PREFIX}:${id}`);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Wyr;
  } catch {
    return null;
  }
}
