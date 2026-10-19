# Agent Keyboard — setup

Three pieces: the app on the phone, a **token broker** Worker (holds your provider keys), and
optionally your own **OpenConnector** Worker (connected apps: Gmail, Calendar, GitHub, Notion…).
The phone never holds a provider API key or the OpenConnector admin token.

## 1. Provider API keys
- xAI (Grok Voice 3.0 + Grok text): https://console.x.ai/team/default/api-keys
- OpenAI (Realtime + text, used as fallback): https://platform.openai.com/api-keys

One of the two is enough; with both, the agent falls over automatically when one fails.

## 2. Token broker (Cloudflare Worker, `worker/token-broker`)
Needs Node.js 22+ and a Cloudflare account.

```bash
cd worker/token-broker
npm install
npx wrangler login
openssl rand -base64 32          # -> your APP_TOKEN, keep it, the phone needs it
npx wrangler secret put APP_TOKEN
npx wrangler secret put XAI_API_KEY
npx wrangler secret put OPENAI_API_KEY   # optional
npm run deploy                   # prints https://agent-keyboard-token-broker.<you>.workers.dev
curl https://agent-keyboard-token-broker.<you>.workers.dev/health   # {"ok":true}
```

Endpoints: `POST /token` mints a 300-second realtime client secret (Grok or OpenAI);
`POST /chat/grok|openai` proxies chat completions; `POST /usage` shows today's counters.
All need `Authorization: Bearer <APP_TOKEN>`.

Guard rails (edit `vars` in `wrangler.jsonc`, then redeploy):

| Var | Default | What it does |
|---|---|---|
| `MAX_BODY_BYTES` | `524288` (512 KB) | Larger requests get `413 too_large`. The app sends screenshots as ≤340 KB JPEGs, so this fits one screenshot plus the prompt and tool list. |
| `DAILY_REQUEST_LIMIT` | `500` | All `/chat` + `/token` calls per UTC day, then `429 daily_limit`. |
| `DAILY_VOICE_SESSION_LIMIT` | `100` | `/token` calls (each opens a realtime voice session) per UTC day. |
| `DAILY_TOKEN_LIMIT` | `2000000` | Sum of `usage.total_tokens` from chat responses; checked before each chat call. |
| `ALLOWED_*_MODELS_*` | empty = built-in | Comma-separated model allowlist overrides. Built-in: chat `grok-4.7`, `gpt-5`; voice `grok-voice-3.0`, `gpt-realtime`. Anything else gets `403 model_not_allowed`. |

Counters live in the `UsageCounter` Durable Object (binding `USAGE`). `wrangler deploy` creates it
from the `durable_objects` + `migrations` blocks in `wrangler.jsonc`; nothing to create by hand.
They reset at 00:00 UTC. Limits do not cap how long one voice session runs once its socket is open.

Local check (no remote calls): `npm run typecheck` and `npm test` (runs `wrangler dev` locally).

## 3. OpenConnector (optional, for connected apps)
Follow https://raw.githubusercontent.com/oomol-lab/open-connector/main/docs/cloudflare.md
(Worker + its own D1 + R2). In short:

```bash
git clone https://github.com/oomol-lab/open-connector && cd open-connector
npm install && cp wrangler.example.jsonc wrangler.local.jsonc
npx wrangler d1 create open-connector
npx wrangler r2 bucket create open-connector-transit-files
# put the D1 database_id + bucket name into wrangler.local.jsonc
npx wrangler d1 migrations apply open-connector --remote --config wrangler.local.jsonc
npx wrangler secret put OOMOL_CONNECT_ADMIN_TOKEN --config wrangler.local.jsonc      # openssl rand -base64 32
npx wrangler secret put OOMOL_CONNECT_ENCRYPTION_KEY --config wrangler.local.jsonc   # another one, back it up
npm run deploy:cloudflare
```

Open the Worker URL in a browser, sign in with the admin token, connect your accounts, then
create a **runtime token** (Console, or `POST /api/runtime-tokens` with the admin token).
The phone gets only the runtime token.

## 4. Phone
1. Install the APK (`dev.yishaik.agentkeyboard.debug`, installs next to HeliBoard).
2. Settings › System › Keyboard › enable **Agent Keyboard** and pick it.
3. Settings › Apps › Default apps › **Digital assistant app** › Agent Keyboard (needed for reading the screen).
4. Open the setup page and tap **Apply in Agent Keyboard**. The app shows which servers and tokens
   will be replaced; tap **Apply** (Cancel is the default). Links to servers other than
   `agent-keyboard-token-broker.yishai-k.workers.dev`, `agent-keyboard-setup.yishai-k.workers.dev`
   and `connect.yishaik.com`, or non-https links, are refused (edit `SetupGuard.ALLOWED_HOSTS` to move).
   Or by hand: **Agent** › Token broker URL + App token. **Connections** › Worker URL + runtime token › Test.
5. First voice use asks for the microphone once.

## Using it
- ✦ key (toolbar): tap = typed agent (keys and glide type into the agent line, Enter sends);
  long-press = voice agent.
- Mic: tap = voice agent, long-press = normal dictation (swap in Agent › Mic tap).
- Drafts show in the strip with Insert / Cancel; write actions in connected apps ask Allow / Deny.
- Hebrew + English layouts are on out of the box; the Hebrew dictionary is bundled.

## Glide typing
Uses Google's closed-source gesture library (no open-source one exists). It is **not in git**.
For your own build: `scripts/fetch-glide.sh`, then build with `-Pagent.bundleGlide=true`; the app
installs it on first start. Without it, use HeliBoard's Settings › Gesture typing › Load library.

## Building
- With Android SDK + NDK: `./gradlew assembleDebugNoMinify`
- Without NDK (e.g. arm64 Linux): add `-Pagent.prebuiltNative=true` (uses `app/prebuilt-jni`).
