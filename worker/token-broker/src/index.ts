/**
 * Token broker for Agent Keyboard. Runs on Cloudflare Workers.
 * Provider API keys live only here (wrangler secrets). The phone authenticates
 * with APP_TOKEN and gets either a short-lived realtime token or a proxied chat call.
 *
 *   POST /token            {"provider":"grok"|"openai"}  -> {"token": "..."}
 *   POST /chat/grok|openai  body = chat.completions request -> proxied response
 */
export interface Env {
  APP_TOKEN: string;
  XAI_API_KEY: string;
  OPENAI_API_KEY: string;
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

/** A token only has to outlive the WebSocket handshake; the socket stays open after it expires. */
const TOKEN_TTL_SECONDS = 300;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function authorized(req: Request, env: Env): boolean {
  const got = req.headers.get("authorization") ?? "";
  const want = `Bearer ${env.APP_TOKEN}`;
  if (got.length !== want.length) return false;
  let diff = 0;
  for (let i = 0; i < got.length; i++) diff |= got.charCodeAt(i) ^ want.charCodeAt(i);
  return diff === 0;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/health") return json({ ok: true });
    if (req.method !== "POST") return json({ error: "method" }, 405);
    if (!authorized(req, env)) return json({ error: "unauthorized" }, 401);

    if (url.pathname === "/token") {
      const { provider, model } = (await req.json()) as { provider: ProviderId; model?: string };
      const up = UPSTREAM[provider];
      if (!up) return json({ error: "provider" }, 400);
      // xAI: only expires_after.seconds (no session / anchor fields).
      // OpenAI GA: session must carry type + model; expiry is anchored to creation.
      const body =
        provider === "grok"
          ? { expires_after: { seconds: TOKEN_TTL_SECONDS } }
          : {
              expires_after: { anchor: "created_at", seconds: TOKEN_TTL_SECONDS },
              session: { type: "realtime", model: model ?? "gpt-realtime" },
            };
      const r = await fetch(up.clientSecret, {
        method: "POST",
        headers: { authorization: `Bearer ${up.key(env)}`, "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = (await r.json()) as Record<string, any>;
      if (!r.ok) return json({ error: "upstream", status: r.status, detail: data }, 502);
      const token = data.value ?? data.client_secret?.value ?? data.token;
      return json({ token, expires_at: data.expires_at ?? data.client_secret?.expires_at });
    }

    const m = url.pathname.match(/^\/chat\/(grok|openai)$/);
    if (m) {
      const up = UPSTREAM[m[1] as ProviderId];
      return fetch(up.chat, {
        method: "POST",
        headers: { authorization: `Bearer ${up.key(env)}`, "content-type": "application/json" },
        body: req.body,
      });
    }
    return json({ error: "not found" }, 404);
  },
};
