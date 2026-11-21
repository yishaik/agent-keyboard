# Agent Keyboard local QA assessment

Date: 2026-10-20. Checkout: main, a3ef81fdf702cdde9400d396b933a923d3d7fd99.

## Result
Incomplete verification; no release recommendation. No box created and no /boxes or CRM update performed because this execution permits local work only. No production credentials accessed, external services called intentionally, APK published, or deployment attempted. Test subprocesses received an isolated environment, offline npm configuration and no inherited provider credentials.

## Maturity: 2/5 — prototype (provisional)
Rubric: 1 scaffold; 2 implemented prototype; 3 device-verified usable version; 4 release-ready with regression evidence; 5 operationally proven.
The source contains Android keyboard integration, agent UI, provider transports, encrypted secret storage, setup guards, broker limits, and agent unit tests. These establish implementation breadth, not runtime correctness. docs/HANDOFF.md explicitly reports no real-phone test pass, nine known inherited test failures, no-op settings, and incomplete features. Live CI and GitHub release status were not independently checked.

## Automated verification
- Broker typecheck: blocked; tsc is unavailable (exit 127). See broker-typecheck.log.
- Broker smoke: blocked at startup (exit 1): wrangler dev did not start; no HTTP cases executed. See broker-smoke.log. The existing suite exercises 13 HTTP guard cases plus Retry-After and usage, using dummy credentials and a zero request limit; it does not validate successful provider calls, UTC rollover, or nonzero quota accounting.
- Smoke script syntax: PASS via node --check; this establishes parsing only (smoke-syntax.log).
- Android regression: blocked (exit 1); see android-regression.log. Java is unavailable; Android tests cannot run here. adb is also unavailable, so device verification is blocked.
- Browser verification: not applicable to the native keyboard interface. The setup-page Worker is outside this checkout per the handoff. No browser pass is claimed.

## Prioritized regression plan
1. In an Android-equipped local environment, run testRunTestsUnitTest and build assembleDebugNoMinify without proprietary glide bundling. Run the full debug unit suite separately to track the nine documented exclusions.
2. With broker development dependencies installed, run npm run typecheck and npm test in an isolated local Worker environment. Add local mocked-upstream cases for allowed requests, missing APP_TOKEN, quota reservation/accounting, UTC reset, concurrent requests, and provider errors.
3. Execute docs/PHONE_TEST.md on an authorized test device using local/staging fixtures: installation, rejected setup links, cancel/apply persistence, draft Insert/Cancel, selection replacement, password-field isolation, Hebrew RTL, microphone permission denial, echo/barge-in, offline recovery, secure-screen behavior, and connector Allow/Deny with mocked actions.
4. Verify ordinary typing with the agent off, field-focus transitions/history behavior, and documented glide-to-agent routing gap. Record device/Android/build versions and per-case expected/actual results.
5. Treat PHONE_TEST production curls, deployments, temporary production limit changes, and real email sends as outside this local plan. Never use production secrets for this assessment.

## Findings and limitations
- Major release gate: no evidence of on-device keyboard, voice, screen capture, or privacy behavior verification.
- Documented product gaps: gesture input still targets the field, incomplete settings translation, no-op cap/retention/memory controls, and name-based connector write approval. These were reviewed in documentation, not reproduced on a device.
- Handoff state is stale relative to this checkout: it describes security hardening as an open PR, while this main checkout already contains SetupGuardTest and broker guard implementations. Branch/PR lifecycle needs independent authorized reconciliation.
- Licensing gate: handoff prohibits public distribution of APKs containing the proprietary glide library and flags Hebrew dictionary provenance.

Evidence sources: docs/HANDOFF.md, docs/AGENT.md, docs/PHONE_TEST.md, worker/token-broker/test/smoke.mjs, app/src/test/java/helium314/keyboard/agent/SetupGuardTest.kt, app/src/test/java/helium314/keyboard/agent/AgentUxTest.kt.
