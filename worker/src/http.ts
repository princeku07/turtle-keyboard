/**
 * Response helpers. JSON serialization, error shape, and permissive CORS that covers
 * both the Android app (which doesn't enforce CORS) and the eventual landing-app web
 * fallback page (which might call from a different origin).
 */

export function json(data: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

export function error(status: number, code: string): Response {
  return json({ error: code }, status);
}

export function withCors(res: Response): Response {
  res.headers.set('access-control-allow-origin', '*');
  res.headers.set('access-control-allow-methods', 'GET, POST, OPTIONS');
  res.headers.set('access-control-allow-headers', 'content-type, x-turtle-device');
  res.headers.set('access-control-max-age', '86400');
  return res;
}
