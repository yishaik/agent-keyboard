/**
 * Token broker for Agent Keyboard. Runs on Cloudflare Workers.
 * Provider API keys live only here (wrangler secrets). The phone authenticates
 * with APP_TOKEN and gets either a short-lived realtime token or a proxied chat call.
 *
 *   POST /token            {"provider":"grok"|"openai","model":"..."} -> {"token": "..."}
 *   POST /chat/grok|openai  body = chat.completions request          -> proxied response
 *
 * Guard rails (all configurable in wrangler.jsonc "vars"):
 *   - model allowlist per provider (exactly what the app sends)
 *   - max request body size (MAX_BODY_BYTES)
 *   - per-day request / voice-session / token limits, counted in the UsageCounter Durable Object
 * Errors are JSON: {"error": "<code>", "message": "<human readable>"}.
 */
import { DurableObject } from "cloudflare:workers";

export interface Env {
  APP_TOKEN: string;
  XAI_API_KEY?: string;
  OPENAI_API_KEY?: string;
  USAGE: DurableObjectNamespace<UsageCounter>;
  /** Comma-separated overrides; empty/unset = the built-in defaults below. */
  ALLOWED_CHAT_MODELS_GROK?: string;
  ALLOWED_CHAT_MODELS_OPENAI?: string;
  ALLOWED_VOICE_MODELS_GROK?: string;
  ALLOWED_VOICE_MODELS_OPENAI?: string;
  MAX_BODY_BYTES?: string;
  DAILY_REQUEST_LIMIT?: string;
  DAILY_VOICE_SESSION_LIMIT?: string;
  DAILY_TOKEN_LIMIT?: string;
}

const UPSTREAM = {
  grok: {
    clientSecret: "https://api.x.ai/v1/realtime/client_secrets",
    chat: "https://api.x.ai/v1/chat/completions",
    key: (e: Env) => e.XAI_API_KEY,
  },
  openai: {
    clientSecret: "https://api.openai.com/v1/realtime/client_secrets",
    chat: "https://api.openai.com/v1/chat/completions",
    key: (e: Env) => e.OPENAI_API_KEY,
  },
} as const;
type ProviderId = keyof typeof UPSTREAM;

/**
 * Exactly the models the app sends (app/.../settings/AgentSettings.kt defaults, used by
 * ProviderSpec -> ChatTextTransport, describe_screen, and RealtimeVoiceTransport -> /token).
 * Transcription models (grok-transcribe, gpt-4o-mini-transcribe) go over the realtime
 * socket, not through the broker, so they are not listed here.
 */
export const DEFAULT_MODELS: Record<ProviderId, { chat: string[]; voice: string[] }> = {
  grok: { chat: ["grok-4.7"], voice: ["grok-voice-3.0"] },
  openai: { chat: ["gpt-5"], voice: ["gpt-realtime"] },
};

/** A token only has to outlive the WebSocket handshake; the socket stays open after it expires. */
const TOKEN_TTL_SECONDS = 300;

const DEFAULTS = {
  MAX_BODY_BYTES: 512 * 1024,
  DAILY_REQUEST_LIMIT: 500,
  DAILY_VOICE_SESSION_LIMIT: 100,
  DAILY_TOKEN_LIMIT: 2_000_000,
};

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });

const fail = (status: number, error: string, message: string, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}) =>
  json({ error, message, ...extra }, status, headers);

function intVar(v: string | undefined, def: number): number {
  const n = Number.parseInt((v ?? "").trim(), 10);
  return Number.isFinite(n) && n >= 0 ? n : def;
}

function listVar(v: string | undefined, def: string[]): string[] {
  const l = (v ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  return l.length ? l : def;
}

export function allowedModels(env: Env, provider: ProviderId, kind: "chat" | "voice"): string[] {
  const key = `ALLOWED_${kind.toUpperCase()}_MODELS_${provider.toUpperCase()}` as keyof Env;
  return listVar(env[key] as string | undefined, DEFAULT_MODELS[provider][kind]);
}

/** UTC day; the counters reset at 00:00 UTC (02:00/03:00 Israel time). */
export function utcDay(now = new Date()): string {
  return now.toISOString().slice(0, 10);
}

function secondsUntilUtcMidnight(now = new Date()): number {
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  return Math.max(1, Math.ceil((next - now.getTime()) / 1000));
}

/** Constant-time: both sides are hashed to fixed-length digests, so neither content nor length leaks. */
async function authorized(req: Request, env: Env): Promise<boolean> {
  const got = req.headers.get("authorization") ?? "";
  const want = `Bearer ${env.APP_TOKEN}`;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(got)),
    crypto.subtle.digest("SHA-256", enc.encode(want)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

class TooLarge extends Error {}

/** Reads at most [limit] bytes; throws TooLarge as soon as the stream goes past it. */
async function readLimited(req: Request, limit: number): Promise<string> {
  const declared = Number(req.headers.get("content-length") ?? "NaN");
  if (Number.isFinite(declared) && declared > limit) throw new TooLarge();
  if (!req.body) return "";
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      throw new TooLarge();
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) { all.set(c, off); off += c.byteLength; }
  return new TextDecoder().decode(all);
}

function parseObject(text: string): Record<string, any> | null {
  try {
    const v = JSON.parse(text);
    return v && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

// ----------------------------------------------------------------------------- usage counter

export type UsageKind = "chat" | "voice";
export interface Limits { requests: number; voiceSessions: number; tokens: number }
export interface Usage { day: string; requests: number; voiceSessions: number; tokens: number }
export type Reservation = { ok: true; usage: Usage } | { ok: false; which: "requests" | "voice_sessions" | "tokens"; usage: Usage };

/**
 * One instance ("global") holds today's counters. A Durable Object is single-threaded, so
 * check-and-increment is atomic (KV is eventually consistent and would let bursts through,
 * and its free tier caps writes at 1,000/day).
 */
export class UsageCounter extends DurableObject<Env> {
  private async load(day: string): Promise<Usage> {
    const u = await this.ctx.storage.get<Usage>("usage");
    return u && u.day === day ? u : { day, requests: 0, voiceSessions: 0, tokens: 0 };
  }

  async reserve(day: string, kind: UsageKind, limits: Limits): Promise<Reservation> {
    const u = await this.load(day);
    if (u.requests >= limits.requests) return { ok: false, which: "requests", usage: u };
    if (kind === "voice" && u.voiceSessions >= limits.voiceSessions) return { ok: false, which: "voice_sessions", usage: u };
    if (kind === "chat" && u.tokens >= limits.tokens) return { ok: false, which: "tokens", usage: u };
    u.requests += 1;
    if (kind === "voice") u.voiceSessions += 1;
    await this.ctx.storage.put("usage", u);
    return { ok: true, usage: u };
  }

  async addTokens(day: string, n: number): Promise<void> {
    if (!(n > 0)) return;
    const u = await this.load(day);
    u.tokens += Math.floor(n);
    await this.ctx.storage.put("usage", u);
  }

  async read(day: string): Promise<Usage> {
    return this.load(day);
  }
}

function limitsOf(env: Env): Limits {
  return {
    requests: intVar(env.DAILY_REQUEST_LIMIT, DEFAULTS.DAILY_REQUEST_LIMIT),
    voiceSessions: intVar(env.DAILY_VOICE_SESSION_LIMIT, DEFAULTS.DAILY_VOICE_SESSION_LIMIT),
    tokens: intVar(env.DAILY_TOKEN_LIMIT, DEFAULTS.DAILY_TOKEN_LIMIT),
  };
}

const counter = (env: Env) => env.USAGE.get(env.USAGE.idFromName("global"));

async function reserveOrFail(env: Env, kind: UsageKind): Promise<Response | null> {
  if (!env.USAGE) return fail(503, "usage_store_missing", "The broker has no USAGE Durable Object binding, so limits cannot be enforced.");
  const r = await counter(env).reserve(utcDay(), kind, limitsOf(env));
  if (r.ok) return null;
  const what = { requests: "requests", voice_sessions: "voice sessions", tokens: "tokens" }[r.which];
  const retry = secondsUntilUtcMidnight();
  return fail(429, "daily_limit", `Daily limit for ${what} reached. It resets at 00:00 UTC.`,
    { limit: r.which, retry_after_seconds: retry }, { "retry-after": String(retry) });
}

// ----------------------------------------------------------------------------- handlers

async function handleToken(req: Request, env: Env, maxBody: number): Promise<Response> {
  const body = parseObject(await readLimited(req, maxBody));
  if (!body) return fail(400, "bad_json", "Request body must be a JSON object.");
  const provider = body.provider as ProviderId;
  if (typeof provider !== "string" || !(provider in UPSTREAM)) return fail(400, "provider", "Unknown provider.");
  const up = UPSTREAM[provider];
  const key = up.key(env);
  if (!key) return fail(503, "provider_not_configured", `No API key for ${provider} on the broker.`);
  const allowed = allowedModels(env, provider, "voice");
  const model = typeof body.model === "string" ? body.model : allowed[0];
  if (!allowed.includes(model)) return fail(403, "model_not_allowed", `Voice model "${model}" is not allowed on this broker.`, { allowed });

  const blocked = await reserveOrFail(env, "voice");
  if (blocked) return blocked;

  // xAI: only expires_after.seconds (no session / anchor fields).
  // OpenAI GA: session must carry type + model; expiry is anchored to creation.
  const upBody =
    provider === "grok"
      ? { expires_after: { seconds: TOKEN_TTL_SECONDS } }
      : {
          expires_after: { anchor: "created_at", seconds: TOKEN_TTL_SECONDS },
          session: { type: "realtime", model },
        };
  const r = await fetch(up.clientSecret, {
    method: "POST",
    headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
    body: JSON.stringify(upBody),
  });
  const data = (await r.json().catch(() => ({}))) as Record<string, any>;
  if (!r.ok) return fail(502, "upstream", `Provider returned ${r.status}.`, { status: r.status, detail: data });
  const token = data.value ?? data.client_secret?.value ?? data.token;
  return json({ token, expires_at: data.expires_at ?? data.client_secret?.expires_at });
}

async function handleChat(provider: ProviderId, req: Request, env: Env, ctx: ExecutionContext, maxBody: number): Promise<Response> {
  const text = await readLimited(req, maxBody);
  const body = parseObject(text);
  if (!body) return fail(400, "bad_json", "Request body must be a JSON object.");
  const up = UPSTREAM[provider];
  const key = up.key(env);
  if (!key) return fail(503, "provider_not_configured", `No API key for ${provider} on the broker.`);
  const allowed = allowedModels(env, provider, "chat");
  if (typeof body.model !== "string" || !allowed.includes(body.model))
    return fail(403, "model_not_allowed", `Model "${String(body.model)}" is not allowed on this broker.`, { allowed });

  const blocked = await reserveOrFail(env, "chat");
  if (blocked) return blocked;

  const r = await fetch(up.chat, {
    method: "POST",
    headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
    body: text,
  });
  // Count tokens from non-streaming JSON responses (what the app sends today).
  // Streaming responses are counted as requests only.
  if (r.ok && (r.headers.get("content-type") ?? "").includes("application/json")) {
    const copy = r.clone();
    ctx.waitUntil(
      copy.json().then((d: any) => {
        const n = Number(d?.usage?.total_tokens ?? 0);
        return n > 0 ? counter(env).addTokens(utcDay(), n) : undefined;
      }).catch(() => undefined),
    );
  }
  return new Response(r.body, { status: r.status, headers: { "content-type": r.headers.get("content-type") ?? "application/json" } });
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/health") return json({ ok: true });
    if (req.method !== "POST") return fail(405, "method", "Use POST.");
    if (!env.APP_TOKEN) return fail(503, "not_configured", "APP_TOKEN secret is not set on the broker.");
    if (!(await authorized(req, env))) return fail(401, "unauthorized", "App token was rejected. Run setup again.");

    const maxBody = intVar(env.MAX_BODY_BYTES, DEFAULTS.MAX_BODY_BYTES);
    try {
      if (url.pathname === "/token") return await handleToken(req, env, maxBody);
      const m = url.pathname.match(/^\/chat\/(grok|openai)$/);
      if (m) return await handleChat(m[1] as ProviderId, req, env, ctx, maxBody);
      if (url.pathname === "/usage") {
        if (!env.USAGE) return fail(503, "usage_store_missing", "No USAGE binding.");
        return json({ usage: await counter(env).read(utcDay()), limits: limitsOf(env), max_body_bytes: maxBody });
      }
    } catch (e) {
      if (e instanceof TooLarge)
        return fail(413, "too_large", `Request is larger than ${Math.round(maxBody / 1024)} KB.`, { max_body_bytes: maxBody });
      throw e;
    }
    return fail(404, "not_found", "Unknown endpoint.");
  },
};
