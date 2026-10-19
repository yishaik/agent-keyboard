# AGENTS.md

Instructions for coding agents working on this repo.

- Start with [docs/HANDOFF.md](docs/HANDOFF.md): current state, open PRs, next steps, known issues, env var names, build/test commands.
- Architecture and feature checklist: [docs/AGENT.md](docs/AGENT.md).
- Agent code lives in `app/src/main/java/helium314/keyboard/agent/`; the token broker in `worker/token-broker/`.
- Never commit secret values. Never publish the APK (it bundles proprietary glide).
- Don't push to `main`, merge, or deploy without Yishai's OK.
