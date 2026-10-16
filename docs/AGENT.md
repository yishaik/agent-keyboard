# Agent Keyboard: architecture

A HeliBoard fork where the keyboard is also an AI agent. One key switches into agent
mode the way you switch languages; the mic key becomes a voice agent; the agent can
read the screen, write into the field, and act in connected apps. Hebrew and English
are both first-class.

## Pieces

| Piece | Where | What it does |
|---|---|---|
| Settings model | `agent/settings/AgentSettings.kt` | Every Agent + Connections option, one JSON blob |
| Settings UI | `agent/settings/AgentScreens.kt` | "Agent" and "Connections" categories at the top of HeliBoard's settings |
| Harness | `agent/harness/` | Tool interface, registry with allow/ask/block, prompt builder, session with failover |
| Providers | `agent/providers/` | One OpenAI-Realtime-compatible voice client (Grok Voice 3.0 / OpenAI), chat text client, token broker client |
| Tools | `agent/tools/` | `get_field`, `insert_text`, `replace_text`, `read_screen`, `describe_screen`, `find_action`, `run_action` |
| Screen | `agent/screen/` | App registers as default digital assistant; agent key calls `showSession(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)` |
| Connections | `agent/connectors/OpenConnectorClient.kt` | Self-hosted OpenConnector on Cloudflare, runtime token only |
| Token broker | `worker/token-broker/` | Cloudflare Worker holding provider keys; mints realtime tokens, proxies chat |

## Rules baked in
- Provider keys never on the phone. OpenConnector admin token never on the phone.
- Password fields are never read or written by the agent (not a setting).
- Text writes always go through a preview in the suggestion strip unless the user picks auto-insert.
- Write actions in connected apps ask first by default.
- Reply language follows the field's conversation by default.

## Setup
1. Deploy OpenConnector to Cloudflare (its `docs/cloudflare.md`), give it its own D1. Create a runtime token in its console.
2. Deploy `worker/token-broker`: `npx wrangler secret put APP_TOKEN`, `XAI_API_KEY`, `OPENAI_API_KEY`, then `npm run deploy`.
3. Install the app, enable the keyboard, set it as Digital assistant app.
4. Settings > Agent: token broker URL. Settings > Connections: Worker URL + runtime token.

## Not done yet (next steps)
- [ ] Build and fix compile errors (skeleton was written without an Android SDK build).
- [ ] Agent key: new key code + agent subtype in the globe cycle; route keys to an internal buffer in agent mode.
- [ ] `AgentController` in the IME: implements `ToolContext` (preview/confirm cards in the suggestion strip).
- [ ] Audio: `AudioRecord` PCM16 capture to `sendAudio`, `AudioTrack` playback of `AudioChunk`.
- [ ] Mic key: tap = agent / long-press = dictation (or swapped per settings).
- [ ] Hebrew + English string resources; RTL check of agent UI.
- [ ] OpenConnector: confirm catalog-search and named-connection endpoints, connections list in settings.
- [ ] Token broker: pin model and expiry in client-secret requests per provider docs.
- [ ] EncryptedSharedPreferences for `SecretStore`.
