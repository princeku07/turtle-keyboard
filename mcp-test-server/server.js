// Local MCP test server for verifying McpErrorMessages mapping in the
// Android keyboard. Single Node file, zero deps. Each tool exercises one
// failure mode that McpClient.java surfaces to the user.
//
// Run:
//   node mcp-test-server/server.js
//   # → http://localhost:7077
//
// Expose for the Android binding screen (which enforces https://):
//   ngrok http 7077
//   # → paste the https://… forwarding URL as the MCP endpoint.
//
// Tools (call name → expected banner from McpErrorMessages):
//   echo          → happy path, no banner; the tool's "text" content is committed.
//   slow          → "/<cmd> timed out — try again"          (SocketTimeoutException @ 60s)
//   unauthorized  → "/<cmd> rejected — re-auth the server"  (http_401)
//   forbidden     → "/<cmd> rejected — re-auth the server"  (http_403)
//   not_found     → "/<cmd> server not found — check the URL" (http_404)
//   rate_limited  → "/<cmd> rate limited — wait a moment"   (http_429)
//   bad_request   → "/<cmd> rejected — check your binding"  (http_400)
//   server_error  → "/<cmd> server error — try again"       (http_500)
//   tool_error    → "/<cmd> tool reported an error — check your args" (mcp_tool_error)
//   no_result     → "/<cmd> got no result — check your binding" (mcp_no_result)
//   rpc_error     → "/<cmd> failed: method not allowed"     (mcp_<message>)
//
// Bearer token is accepted but ignored. To test the "no token" path,
// leave the binding's token field empty and bind to `unauthorized` — the
// server always 401s on that tool.

'use strict';

const http = require('node:http');

const PORT = Number(process.env.PORT) || 7077;

const TOOLS = [
  { name: 'echo',         description: 'Happy path. Echoes the arguments back as text.' },
  { name: 'slow',         description: 'Sleeps 90s. Beats the 60s read timeout in McpClient.' },
  { name: 'unauthorized', description: 'Returns HTTP 401.' },
  { name: 'forbidden',    description: 'Returns HTTP 403.' },
  { name: 'not_found',    description: 'Returns HTTP 404.' },
  { name: 'rate_limited', description: 'Returns HTTP 429 with Retry-After.' },
  { name: 'bad_request',  description: 'Returns HTTP 400.' },
  { name: 'server_error', description: 'Returns HTTP 500.' },
  { name: 'tool_error',   description: 'Returns result.isError = true.' },
  { name: 'no_result',    description: 'Returns an envelope without `result`.' },
  { name: 'rpc_error',    description: 'Returns a JSON-RPC `error` object.' },
];

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Buffer.concat(chunks).toString('utf8');
}

function ok(id, result) {
  return JSON.stringify({ jsonrpc: '2.0', id, result });
}
function rpcErr(id, code, message) {
  return JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } });
}
function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body);
}
function sendStatus(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'text/plain' });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET') {
    sendStatus(res, 200,
      'MCP test server. POST JSON-RPC 2.0 envelopes to this URL.\n' +
      'See server.js for the catalog of error-mode tools.\n');
    return;
  }
  if (req.method !== 'POST') {
    sendStatus(res, 405, 'POST only');
    return;
  }

  let env;
  try {
    env = JSON.parse(await readBody(req));
  } catch {
    sendJson(res, 400, rpcErr(null, -32700, 'parse error'));
    return;
  }
  const id = env.id ?? null;

  if (env.method === 'tools/list') {
    sendJson(res, 200, ok(id, { tools: TOOLS }));
    return;
  }
  if (env.method !== 'tools/call') {
    sendJson(res, 200, rpcErr(id, -32601, 'method not found'));
    return;
  }

  const name = env.params && env.params.name;
  const args = (env.params && env.params.arguments) || {};

  switch (name) {
    case 'echo':
      sendJson(res, 200, ok(id, {
        content: [{ type: 'text', text: JSON.stringify(args) }],
      }));
      return;

    case 'slow':
      // Longer than McpClient.READ_TIMEOUT_MS (60s) so the client raises
      // SocketTimeoutException and McpErrorMessages produces the "timed out"
      // banner.
      await new Promise(r => setTimeout(r, 90_000));
      sendJson(res, 200, ok(id, {
        content: [{ type: 'text', text: 'finally responded' }],
      }));
      return;

    case 'unauthorized':  sendStatus(res, 401, 'unauthorized');         return;
    case 'forbidden':     sendStatus(res, 403, 'forbidden');            return;
    case 'not_found':     sendStatus(res, 404, 'not found');            return;
    case 'rate_limited':
      res.writeHead(429, { 'Content-Type': 'text/plain', 'Retry-After': '30' });
      res.end('slow down');
      return;
    case 'bad_request':   sendStatus(res, 400, 'bad request');          return;
    case 'server_error':  sendStatus(res, 500, 'internal server error'); return;

    case 'tool_error':
      sendJson(res, 200, ok(id, {
        isError: true,
        content: [{ type: 'text', text: 'simulated tool failure' }],
      }));
      return;

    case 'no_result':
      sendJson(res, 200, JSON.stringify({ jsonrpc: '2.0', id }));
      return;

    case 'rpc_error':
      sendJson(res, 200, rpcErr(id, -32601, 'method not allowed'));
      return;

    default:
      sendJson(res, 200, rpcErr(id, -32602, `unknown tool: ${name}`));
      return;
  }
});

server.listen(PORT, () => {
  console.log(`MCP test server listening on http://localhost:${PORT}`);
  console.log(`Tools: ${TOOLS.map(t => t.name).join(', ')}`);
  console.log(`Expose with: ngrok http ${PORT}`);
});
