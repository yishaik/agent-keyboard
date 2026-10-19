// Local smoke test: runs the Worker with `wrangler dev` (local workerd, nothing remote) and checks
// the guard rails. DAILY_REQUEST_LIMIT=0 makes every allowed call stop at 429 before any upstream
// fetch, so no provider is contacted and no real key is needed.
import { spawn } from "node:child_process";
import assert from "node:assert/strict";

const PORT = 8799;
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = "test-app-token";
const dev = spawn("npx", [
  "wrangler", "dev", "--local", "--port", String(PORT), "--ip", "127.0.0.1",
  "--var", `APP_TOKEN:${TOKEN}`, "--var", "XAI_API_KEY:not-a-real-key", "--var", "OPENAI_API_KEY:not-a-real-key",
  "--var", "DAILY_REQUEST_LIMIT:0", "--var", "MAX_BODY_BYTES:1024",
], { stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, WRANGLER_SEND_METRICS: "false" } });
let log = "";
dev.stdout.on("data", (d) => (log += d));
dev.stderr.on("data", (d) => (log += d));

async function waitUp() {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(BASE + "/health")).ok) return; } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("wrangler dev did not start:\n" + log);
}

const post = (path, body, token = TOKEN, raw = false) =>
  fetch(BASE + path, {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: raw ? body : JSON.stringify(body),
  });

async function expect(res, status, error) {
  const j = await res.json();
  assert.equal(res.status, status, JSON.stringify(j));
  if (error) assert.equal(j.error, error);
  assert.equal(typeof j.message === "string" || status === 200, true);
  return j;
}

let failed = false;
try {
  await waitUp();
  const cases = [
    ["no token", () => post("/chat/grok", { model: "grok-4.7" }, null), 401, "unauthorized"],
    ["wrong token", () => post("/chat/grok", { model: "grok-4.7" }, "nope"), 401, "unauthorized"],
    ["wrong token same length", () => post("/chat/grok", { model: "grok-4.7" }, "test-app-tokeX"), 401, "unauthorized"],
    ["chat model not allowed", () => post("/chat/grok", { model: "grok-4-heavy" }), 403, "model_not_allowed"],
    ["chat model of other provider", () => post("/chat/openai", { model: "grok-4.7" }), 403, "model_not_allowed"],
    ["chat model missing", () => post("/chat/openai", { messages: [] }), 403, "model_not_allowed"],
    ["voice model not allowed", () => post("/token", { provider: "openai", model: "gpt-4o-realtime-preview" }), 403, "model_not_allowed"],
    ["unknown provider", () => post("/token", { provider: "anthropic" }), 400, "provider"],
    ["bad json", () => post("/chat/grok", "{not json", TOKEN, true), 400, "bad_json"],
    ["too large", () => post("/chat/grok", { model: "grok-4.7", pad: "x".repeat(2048) }), 413, "too_large"],
    ["allowed chat hits daily limit", () => post("/chat/grok", { model: "grok-4.7", messages: [] }), 429, "daily_limit"],
    ["allowed voice hits daily limit", () => post("/token", { provider: "grok", model: "grok-voice-3.0" }), 429, "daily_limit"],
    ["get is rejected", () => fetch(BASE + "/chat/grok"), 405, "method"],
  ];
  for (const [name, run, status, err] of cases) {
    try { await expect(await run(), status, err); console.log("ok  ", name); }
    catch (e) { failed = true; console.log("FAIL", name, e.message); }
  }
  const r429 = await post("/chat/openai", { model: "gpt-5" });
  assert.ok(Number(r429.headers.get("retry-after")) > 0, "retry-after header");
  console.log("ok   retry-after header");
  const usage = await expect(await post("/usage", {}), 200);
  assert.equal(usage.usage.requests, 0);
  console.log("ok   usage endpoint", JSON.stringify(usage));
} catch (e) {
  failed = true;
  console.error(e);
} finally {
  dev.kill("SIGTERM");
}
process.exit(failed ? 1 : 0);
