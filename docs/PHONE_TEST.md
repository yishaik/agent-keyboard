# Real-phone test script

One pass takes about 30–40 minutes. Do it on your daily phone with Hebrew and English enabled.
Log every problem in the bug table at the end (one row per bug, even small ones).

**Before you start**
- Broker deployed from the `security-hardening` code (limits + allowlist). Check:
  `curl https://agent-keyboard-token-broker.yishai-k.workers.dev/health` → `{"ok":true}`.
- APK from the latest GitHub release or the CI artifact of the commit you are testing.
  Write the version / commit in the bug table header.
- Wi-Fi on. Have WhatsApp, Gmail and Chrome installed and signed in.

## 1. Install
1. Download the APK on the phone, allow "install unknown apps" for the browser, install.
2. Open Agent Keyboard. The setup checklist appears.
3. Enable the keyboard, select it as current, set it as **Digital assistant app**.

✅ Checklist shows each step done. HeliBoard (if installed) is untouched.

## 2. One-tap setup link (new confirmation)
1. Open the setup page, tap **Apply in Agent Keyboard**.
2. The dialog **"Apply server settings?"** lists: Token broker server / App token /
   Connections server / Connections token, each with *will be set* or *will be replaced* and the host.
   No token value is visible.
3. **Apply** is greyed out for ~1 s. Press Back → dialog closes, nothing changes (checklist still
   shows the server step not done if it was not done before).
4. Open the link again, wait, tap **Apply** → "Server connected" toast, settings open, checklist
   shows the server done.
5. Open the link a third time. Rows now say *will be replaced*. Tap outside the dialog → closes, nothing changes.
6. Hostile link (paste each one into Chrome's address bar; if Chrome only searches, put it in a
   note or a WhatsApp message to yourself and tap it):
   - `agentkeyboard://setup?broker=https://evil.example&app=x`
     → **"Setup link blocked"**, names `evil.example`, no Apply button.
   - `agentkeyboard://setup?broker=http://agent-keyboard-token-broker.yishai-k.workers.dev&app=x`
     → blocked as non-https.
   - `agentkeyboard://setup?connector=https://connect.yishaik.com.evil.example&runtime=x` → blocked.
7. Paste the blocked link into the in-app **Paste setup link** box → long toast saying it is blocked.
8. `adb logcat | grep -iE "token|oct_"` while doing 4 (optional, needs USB) → no token values.

✅ Agent still works after steps 5–7 (settings were not replaced).

## 3. Typed agent, insert / replace
In each of **WhatsApp**, **Gmail** (compose body), **Chrome** (a search box and a long text area):
1. Tap ✦, type "write a short hello to Dana", Enter. Preview shows; tap **Insert**.
2. Select a sentence, ✦ "make this more formal" → **Replace** swaps only the selection.
3. Same in Hebrew: "תכתוב הודעה קצרה לדנה שאני מאחר". Text is RTL, punctuation at the correct end.
4. Glide-type into the agent line. Words land in the agent line, not in the app's field.
5. Tap a password field, tap ✦ → "The agent is off in password fields".

✅ Correct text, correct direction, cursor in a sensible place, nothing inserted without Insert.

## 4. Voice (Hebrew and English)
1. Long-press ✦ (or tap the mic). Grant the mic once.
2. English: "Reply that I'll be there at eight." Hebrew: "תענה שאני מגיע בשמונה".
3. **Echo:** speaker on, no headphones, let the agent talk. It must not hear itself and reply to itself.
4. **Interruption:** start talking while it speaks → playback stops within ~0.5 s and it listens.
5. Push-to-talk (Agent › Turn taking): hold, speak, release → one reply.
6. Airplane mode mid-session → clear error, no hang. Turn it back on, retry works.
7. Failover (both provider keys on the broker): temporarily set `ALLOWED_VOICE_MODELS_GROK` and
   `ALLOWED_CHAT_MODELS_GROK` to `none` and redeploy → voice and typed requests show
   "fallback: Openai" and still answer. Clear both vars and redeploy afterwards.

✅ Transcript in the strip is right in both languages, reply language matches the request.

## 5. Screen context with the keyboard open
1. Open a WhatsApp chat with a few messages, tap the reply field (keyboard open).
2. ✦ → strip says "Seeing WhatsApp". Ask "reply to this".
3. **The keyboard must stay open** while the assist session captures. Note any flicker, keyboard
   closing, or the app losing focus.
4. Ask "what's in the picture above?" (forces `describe_screen` with a screenshot). Answer must be
   about the screen; no 413 error (the screenshot is now JPEG ≤ 340 KB).
5. Open a banking or password-manager app (FLAG_SECURE) → no screenshot, a clear message, no crash.

## 6. Connected apps (OpenConnector)
1. Settings › Connections › **Test** → lists your accounts.
2. Read: ✦ "what's my next calendar event?" / "summarise my last email from <name>" → runs with no prompt.
3. Write: ✦ "send an email to myself saying test" → **Allow / Deny** appears.
   - **Deny** → nothing is sent; agent says it did not send.
   - Repeat, **Allow** → mail arrives once.

## 7. Broker limits (needs a laptop with `curl`, or test on staging values)
Set `TOKEN=<APP_TOKEN>` and `B=https://agent-keyboard-token-broker.yishai-k.workers.dev`.
1. Model allowlist:
   `curl -s -XPOST $B/chat/grok -H "authorization: Bearer $TOKEN" -d '{"model":"grok-4-heavy"}'`
   → `403 {"error":"model_not_allowed",...}`.
2. Size: `head -c 600000 /dev/zero | tr '\0' a | curl -s -XPOST $B/chat/grok -H "authorization: Bearer $TOKEN" --data-binary @-`
   → `413 too_large`.
3. Usage: `curl -s -XPOST $B/usage -H "authorization: Bearer $TOKEN"` → counters go up after phone use.
4. Daily limit: temporarily set `DAILY_REQUEST_LIMIT` to the current count, redeploy, use ✦ →
   panel shows **"Daily usage limit reached…"** (not "HTTP 429"). Set it back and redeploy.
5. Wrong token: set a wrong App token in settings → panel shows **"The broker rejected the app token…"**.

## 8. Agent off: normal typing
With agent mode off for 5 minutes of normal use:
- Typing, glide, autocorrect, suggestions, emoji, Hebrew ↔ English switch all behave like HeliBoard.
- No mic or network activity (Android's privacy indicator stays off; optional: Settings › Network
  usage for the app shows no growth).
- No slowdown, no extra strip unless ✦ is tapped.

## Bug log

Build / commit: `________`  Phone + Android version: `________`  Date: `________`

| # | Section | Steps to reproduce | Expected | Actual | Severity (blocker / major / minor) | Screenshot / log | Status |
|---|---|---|---|---|---|---|---|
| 1 | | | | | | | |
| 2 | | | | | | | |
| 3 | | | | | | | |
