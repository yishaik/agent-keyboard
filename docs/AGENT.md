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

## Using it
- Toolbar: the sparkle key (first in the toolbar) toggles typed agent mode. Keys then type into the
  agent line in the strip; Enter or Send runs it. Long-press the sparkle for the voice agent.
- Mic key: tap = voice agent, long-press = normal dictation (swap in Settings > Agent > Mic tap).
- Drafts appear in the strip with Insert / Cancel. Write actions in connected apps show Allow / Deny.
- The agent never runs in password fields.

## Building
Normal machine with the Android SDK + NDK: `./gradlew assembleDebugNoMinify`.
Without an NDK (e.g. linux-arm64): `./gradlew -Pagent.prebuiltNative=true assembleDebugNoMinify`
packages `app/prebuilt-jni/*/libjni_latinime.so` from the HeliBoard v4.2 release instead of building it.

## Not done yet (next steps)
- [x] Build and fix compile errors (debugNoMinify APK builds).
- [x] Agent key (toolbar AGENT key, codes AGENT / AGENT_VOICE / VOICE_INPUT_LONG); keys routed to the agent line.
- [x] `AgentController`: ToolContext with preview / confirm in the strip, typed + voice sessions, failover.
- [x] Audio: 24 kHz PCM16 `AudioRecord` (VOICE_COMMUNICATION, echo-cancelled) / `AudioTrack`, barge-in, push-to-talk.
- [x] Mic key: tap = agent / long-press = dictation (or swapped).
- [x] Hebrew + English strings for keyboard UI and settings entries.
- [x] OpenConnector via its MCP endpoint (search_actions, list_connections, execute_action); Test + accounts list in Connections.
- [x] Token broker pins model + 300 s expiry per provider docs.
- [x] Keystore AES-GCM for `SecretStore` (API 23+).
- [ ] On-device test pass (real phone): VAD sensitivity, playback/echo, RTL layout of the strip.
- [ ] Agent as a subtype in the globe cycle (currently a toolbar key).
- [ ] Gesture typing into the agent line (batch input still goes to the field).
- [ ] Translate remaining Agent settings labels (screen titles are translated; row labels are English).
- [ ] Per-tool policy editor and blocked-apps picker in settings.
- [ ] Token usage meter for the monthly cap.
