# Handoff: Agent Keyboard (state as of 2026-10-19)

Read this first, then `docs/AGENT.md` (architecture + checklist), `docs/SETUP.md`, `docs/PHONE_TEST.md`.

## Current state
- Prototype. HeliBoard fork (GPL-3.0) with a built-in AI agent (typed + voice, Grok / OpenAI), screen context, OpenConnector apps.
- GitHub releases `v4.2.1` and `v4.2.2` (2026-10-17). **Never tested on a real phone.**
- `main` CI ("Build debug APK") is green.

## Branches / PRs
- **PR #1** https://github.com/yishaik/agent-keyboard/pull/1 (`security-hardening`), open, CI green, not merged. Contains:
  - Setup-link confirmation: dialog lists every field + host, Cancel default, delayed Apply. `SetupGuard` requires https and rejects unknown hosts (no Apply button).
  - Broker hardening (`worker/token-broker`): model allowlist `grok-4.7`, `gpt-5`, `grok-voice-3.0`, `gpt-realtime`; 512 KB body cap; daily limits 500 requests / 100 voice sessions / 2M tokens in the `UsageCounter` Durable Object (counters reset 00:00 UTC); `POST /usage`; constant-time hashed `APP_TOKEN` compare; 503 if `APP_TOKEN` unset (previously `Bearer undefined` passed).
  - Panel shows clear errors for 401/403/413/429.
  - Screenshots sent as JPEG ≤ 340 KB.
  - CI test-task fix `70b95ed`: `android.onlyEnableUnitTestForTheTestedBuildType=false`, Java 21 toolchain for unit tests, 9 pre-existing HeliBoard failures skipped in the `runTests` variant only.

## Next steps (in order)
1. Merge PR #1 (Yishai).
2. `wrangler deploy` the broker (creates the `USAGE` DO via the `v1` migration).
3. Run `docs/PHONE_TEST.md` on a real phone.
4. Fix the bugs it finds.
5. Verify `grok-4.7` accepts image input (screenshots).

## Smallest usable v1
- Installs from the CI APK; one-tap setup link.
- Typed agent drafts/replies into the current field with preview, in WhatsApp / Gmail / Chrome, Hebrew and English.
- Hands-free voice with no echo.
- Screen-aware "reply to this".
- Clear errors; broker spend caps.
- Normal typing unchanged when the agent is off.

**v1.1:** connector writes, memory, streaming, per-app styles, blockedPackages picker, long-press-Home voice UI.

## Known issues
- "Test build" workflow (`.github/workflows/build-test-auto.yml`) still uses JDK 17 and old action versions. Editing it needs a token with `workflow` scope (the agent token lacks it).
- 9 HeliBoard tests fail (also on `main`): 6 `InputTest` NPE, 2 `XLinkTest` 404, 1 `InputLogicTest.moveCursorHorizontally`.
- No-op settings: `monthlyCapUsd`, `historyRetentionDays`, `memoryEnabled`.
- No SSE streaming of text replies.
- Agent history is wiped on field blur.
- `get_action_guide` isn't exposed as a tool.
- `AgentRecognitionService` is a stub.
- Write-approval for connector actions is name-heuristic based.
- `ubuntu-latest` is moving to Ubuntu 26; pin if CI breaks.

## Licensing
- CI bundles Google's proprietary glide typing lib (`-Pagent.bundleGlide=true`) → **never distribute the APK publicly**.
- `main_he.dict` provenance is unverified.
- `scripts/fetch-glide.sh` depends on a pinned third-party raw URL.

## Open questions (for Yishai)
- Restrict each setup field to its own host (broker field → broker host only, etc.)?
- Are the daily limits right?
- Token-only setup links (no URLs in the link)?

## Planned integration
Instinct Companion will expose a read-only MCP v1 with a per-agent token, consumed via OpenConnector. Agent Keyboard is the first client, after its phone test.

## Env vars and secrets (names only)
Token broker Worker (`worker/token-broker/wrangler.jsonc`):
- Secrets: `APP_TOKEN`, `XAI_API_KEY`, `OPENAI_API_KEY` (optional).
- Vars: `MAX_BODY_BYTES`, `DAILY_REQUEST_LIMIT`, `DAILY_VOICE_SESSION_LIMIT`, `DAILY_TOKEN_LIMIT`, `ALLOWED_CHAT_MODELS_GROK`, `ALLOWED_CHAT_MODELS_OPENAI`, `ALLOWED_VOICE_MODELS_GROK`, `ALLOWED_VOICE_MODELS_OPENAI` (empty = built-in `DEFAULT_MODELS`).
- Durable Object binding: `USAGE` → class `UsageCounter`.

App side: the app token and the OpenConnector runtime token are entered via the setup link (`agentkeyboard://setup`) and stored with Keystore AES-GCM (`SecretStore`).

Hosts (`SetupGuard.ALLOWED_HOSTS`): `agent-keyboard-token-broker.yishai-k.workers.dev`, `agent-keyboard-setup.yishai-k.workers.dev`, `connect.yishaik.com`. The setup-page Worker and OpenConnector are **not** in this repo.

## Build / run / test
App (JDK 21, Android SDK; NDK unless prebuilt):
```
./gradlew :app:assembleDebugNoMinify                          # needs NDK
./gradlew -Pagent.prebuiltNative=true :app:assembleDebugNoMinify  # no NDK (e.g. linux-arm64)
bash scripts/fetch-glide.sh && ./gradlew -Pagent.bundleGlide=true :app:assembleDebugNoMinify  # what CI builds (personal use only)
./gradlew :app:testDebugUnitTest      # all unit tests (9 HeliBoard failures expected)
./gradlew testRunTestsUnitTest        # CI variant, skips the known failures
```
(`assembleDebug` also exists but is minified; the docs and CI use `assembleDebugNoMinify`.)

Broker (Node 22), in `worker/token-broker`:
```
npm install
npm run typecheck
npm test              # node test/smoke.mjs
npx wrangler secret put APP_TOKEN   # + XAI_API_KEY, OPENAI_API_KEY
npm run deploy        # wrangler deploy
```
