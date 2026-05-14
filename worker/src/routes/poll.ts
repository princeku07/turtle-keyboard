/**
 * /poll endpoints — create, read, vote. KV key prefix: {@code poll:<id>}. 90-day TTL.
 *
 * <p>Worker is pure CRUD. AI poll generation (terse prompt → question + options) lives
 * on the Android side in the existing {@code AiLlm} / {@code LmStudioAiClient.callGemini}
 * path, mirroring how /cap, /us, /org generate their content. The keyboard does the
 * AI call locally and POSTs the explicit {@code question} + {@code options} here.
 */

import { json, error } from '../http';
import { nanoid } from '../id';
import type { Env, Poll } from '../types';

const KV_PREFIX = 'poll';
const TTL_SECONDS = 60 * 60 * 24 * 90;

const MAX_QUESTION_LEN = 200;
const MAX_OPTION_LEN = 50;
const MIN_OPTIONS = 2;
const MAX_OPTIONS = 6;

/** Shareable URL the Android app's BottomSheetActivity catches via verified HTTPS
 *  App Link. Lives on the landing host — the Worker itself runs on a different host. */
const SHAREABLE_HOST = 'https://www.turtlekeyboard.com';

export async function createPoll(req: Request, env: Env): Promise<Response> {
  let body: { question?: unknown; options?: unknown };
  try {
    body = (await req.json()) as { question?: unknown; options?: unknown };
  } catch {
    return error(400, 'invalid_json');
  }

  const question = typeof body.question === 'string' ? body.question.trim() : '';
  if (!question) return error(400, 'invalid_question');
  if (question.length > MAX_QUESTION_LEN) return error(400, 'question_too_long');

  if (!Array.isArray(body.options)) return error(400, 'invalid_options');
  const optionLabels = (body.options as unknown[])
    .filter((o): o is string => typeof o === 'string')
    .map(o => o.trim())
    .filter(Boolean);
  if (optionLabels.length < MIN_OPTIONS || optionLabels.length > MAX_OPTIONS) {
    return error(400, 'invalid_options');
  }
  if (optionLabels.some(l => l.length > MAX_OPTION_LEN)) {
    return error(400, 'option_too_long');
  }

  const id = nanoid();
  const poll: Poll = {
    id,
    createdAt: Date.now(),
    question,
    options: optionLabels.map(label => ({ label, votes: 0 })),
    voters: [],
  };
  await env.TURTLE_KV.put(`${KV_PREFIX}:${id}`, JSON.stringify(poll), {
    expirationTtl: TTL_SECONDS,
  });
  return json({ id, url: `${SHAREABLE_HOST}/poll/${id}` });
}

export async function readPoll(env: Env, id: string): Promise<Response> {
  const poll = await loadPoll(env, id);
  if (!poll) return error(404, 'poll_not_found');
  const { voters: _voters, ...publicShape } = poll;
  return json(publicShape);
}

export async function votePoll(req: Request, env: Env, id: string): Promise<Response> {
  const deviceId = req.headers.get('x-turtle-device')?.trim() ?? '';
  if (!deviceId) return error(400, 'missing_device_id');

  let body: { optionIndex?: unknown };
  try {
    body = (await req.json()) as { optionIndex?: unknown };
  } catch {
    return error(400, 'invalid_json');
  }
  const optionIndex = typeof body.optionIndex === 'number' ? body.optionIndex : -1;

  const poll = await loadPoll(env, id);
  if (!poll) return error(404, 'poll_not_found');
  if (optionIndex < 0 || optionIndex >= poll.options.length) {
    return error(400, 'invalid_option');
  }
  if (poll.voters.includes(deviceId)) return error(409, 'already_voted');

  poll.options[optionIndex].votes += 1;
  poll.voters.push(deviceId);
  await env.TURTLE_KV.put(`${KV_PREFIX}:${id}`, JSON.stringify(poll), {
    expirationTtl: TTL_SECONDS,
  });
  return json({ ok: true });
}

async function loadPoll(env: Env, id: string): Promise<Poll | null> {
  const raw = await env.TURTLE_KV.get(`${KV_PREFIX}:${id}`);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Poll;
  } catch {
    return null;
  }
}
