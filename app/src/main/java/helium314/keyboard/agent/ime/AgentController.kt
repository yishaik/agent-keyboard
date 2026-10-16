package helium314.keyboard.agent.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import helium314.keyboard.agent.audio.AudioIO
import helium314.keyboard.agent.connectors.OpenConnectorClient
import helium314.keyboard.agent.harness.AgentEvent
import helium314.keyboard.agent.harness.AgentSession
import helium314.keyboard.agent.harness.ToolContext
import helium314.keyboard.agent.harness.ToolRegistry
import helium314.keyboard.agent.providers.ChatTextTransport
import helium314.keyboard.agent.providers.ProviderSpec
import helium314.keyboard.agent.providers.RealtimeVoiceTransport
import helium314.keyboard.agent.providers.TokenBroker
import helium314.keyboard.agent.screen.ScreenContextHolder
import helium314.keyboard.agent.settings.AgentSettings
import helium314.keyboard.agent.settings.AgentSettingsStore
import helium314.keyboard.agent.settings.InsertMode
import helium314.keyboard.agent.settings.MicTapAction
import helium314.keyboard.agent.settings.Provider
import helium314.keyboard.agent.settings.ScreenMode
import helium314.keyboard.agent.settings.SecretStore
import helium314.keyboard.agent.settings.SpokenReply
import helium314.keyboard.agent.settings.TurnTaking
import helium314.keyboard.agent.tools.ConnectorTools
import helium314.keyboard.agent.tools.FieldTools
import helium314.keyboard.agent.tools.ScreenTools
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The agent inside the keyboard. Owns the agent strip (shown in place of the
 * suggestion strip), the typed-request buffer, the voice session and audio, and the
 * [ToolContext] the tools run against. LatinIME forwards key events here first.
 *
 * Modes, like switching language: OFF (normal keyboard) -> TYPING (keys go to the
 * request line) -> WORKING -> PREVIEW / CONFIRM -> back to TYPING or OFF.
 * VOICE runs beside any of them.
 */
class AgentController(private val ime: LatinIME) : ToolContext {

    private enum class Mode { OFF, TYPING, WORKING, PREVIEW, CONFIRM, MESSAGE }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = AgentSettingsStore.get(ime)
    private val secrets = SecretStore(ime)
    private val settings: AgentSettings get() = store.state.value
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val broker = TokenBroker(http, { settings.tokenBrokerUrl }, { secrets.brokerAppToken })
    private val connector = OpenConnectorClient(http, { settings.connectorUrl }, { secrets.connectorRuntimeToken })
    private val audio = AudioIO()

    private var strip: AgentStripView? = null
    private var mode = Mode.OFF
    private val buffer = StringBuilder()
    private var answer = StringBuilder()
    private var pending: CompletableDeferred<Boolean>? = null
    private var voiceOn = false
    private var session: AgentSession? = null

    private val registry = ToolRegistry { settings }.apply {
        register(*FieldTools { ime.currentInputConnection.takeUnless { isPasswordField() } }.all())
        register(*ScreenTools(::describeImage).all())
        register(*ConnectorTools(connector) { id -> isWriteAction(id) }.all())
    }

    /** True while the agent owns the strip; LatinIME then leaves suggestions alone. */
    val isShowing get() = mode != Mode.OFF || voiceOn

    // ---------------------------------------------------------------- key routing

    /** Called by LatinIME.onEvent before normal input. Returns true if consumed. */
    fun onEvent(event: Event): Boolean {
        when (event.keyCode) {
            KeyCode.AGENT -> { toggleTyping(); return true }
            KeyCode.AGENT_VOICE -> { toggleVoice(); return true }
            KeyCode.VOICE_INPUT -> return onMicTap()
            KeyCode.VOICE_INPUT_LONG -> {
                if (settings.micTap == MicTapAction.AGENT) ime.startDictation() else toggleVoice()
                return true
            }
        }
        if (mode != Mode.TYPING) return false
        val cp = event.codePoint
        return when {
            event.keyCode == KeyCode.DELETE -> {
                if (buffer.isNotEmpty()) buffer.setLength(Character.offsetByCodePoints(buffer, buffer.length, -1))
                renderTyping(); true
            }
            cp == Constants.CODE_ENTER -> { submitTyped(); true }
            cp > 0 && !event.isFunctionalKeyEvent -> { buffer.appendCodePoint(cp); renderTyping(); true }
            else -> false // shift, symbols, language switch... keep working
        }
    }

    /** Multi-character input (emoji, text keys) while typing a request. */
    fun onTextInput(text: String): Boolean {
        if (mode != Mode.TYPING) return false
        buffer.append(text); renderTyping(); return true
    }

    /** A glide-typed word while typing a request: append it with a space, like the field would. */
    fun onGestureWord(word: String): Boolean {
        if (mode != Mode.TYPING) return false
        if (buffer.isNotEmpty() && !buffer.endsWith(" ")) buffer.append(' ')
        buffer.append(word).append(' ')
        renderTyping(); return true
    }

    private fun onMicTap(): Boolean {
        if (settings.micTap == MicTapAction.DICTATION) return false // LatinIME does normal voice input
        toggleVoice(); return true
    }

    // ---------------------------------------------------------------- lifecycle

    fun onStartInputView(editorInfo: EditorInfo?) {
        // A new field ends any request bound to the previous one.
        if (mode == Mode.PREVIEW || mode == Mode.CONFIRM) resolvePending(false)
        if (mode != Mode.OFF && isPasswordField()) exit()
        else if (mode != Mode.OFF || voiceOn) render()
    }

    fun onFinishInputView() {
        resolvePending(false)
        stopVoice()
        session?.close(); session = null
        mode = Mode.OFF
        buffer.clear()
    }

    fun onDestroy() {
        onFinishInputView()
        audio.release()
        scope.cancel()
    }

    // ---------------------------------------------------------------- typed mode

    private fun toggleTyping() {
        if (mode == Mode.OFF) {
            if (!checkReady()) return
            mode = Mode.TYPING
            render()
        } else exit()
    }

    private fun submitTyped() {
        val text = buffer.toString().trim()
        if (text.isEmpty()) return
        buffer.clear()
        answer = StringBuilder()
        mode = Mode.WORKING
        render()
        sessionOrNew().sendText(text, fieldHint())
    }

    private fun exit() {
        resolvePending(false)
        session?.cancel()
        stopVoice()
        mode = Mode.OFF
        buffer.clear()
        ime.showAgentStrip(null)
    }

    // ---------------------------------------------------------------- voice

    private fun toggleVoice() {
        if (voiceOn) { stopVoice(); if (mode == Mode.OFF) ime.showAgentStrip(null) else render(); return }
        if (!checkReady()) return
        if (Build.VERSION.SDK_INT >= 23 &&
            ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ime.startActivity(Intent(ime, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            message(ime.getString(R.string.agent_mic_permission))
            return
        }
        voiceOn = true
        answer = StringBuilder()
        render(status = ime.getString(R.string.agent_connecting))
        sessionOrNew().startVoice(fieldLanguage ?: "en", fieldHint())
        if (settings.turnTaking == TurnTaking.VAD) startCapture()
        startLevelMeter()
    }

    private fun startCapture() {
        runCatching { audio.startCapture { pcm -> session?.sendAudio(pcm) } }
            .onFailure { error(it.message ?: "mic"); stopVoice() }
    }

    private fun stopVoice() {
        if (!voiceOn) return
        voiceOn = false
        audio.stopCapture()
        audio.flushPlayback()
        session?.stopVoice()
        main.removeCallbacks(levelTick)
        strip?.setActivity(0f)
    }

    private val levelTick = object : Runnable {
        override fun run() {
            strip?.setActivity(audio.level * 6f)
            if (voiceOn) main.postDelayed(this, 80)
        }
    }
    private fun startLevelMeter() { main.removeCallbacks(levelTick); main.post(levelTick) }

    // ---------------------------------------------------------------- session

    private fun sessionOrNew(): AgentSession = session ?: AgentSession(
        scope = scope,
        settings = { settings },
        registry = registry,
        voiceFactory = { p: Provider ->
            RealtimeVoiceTransport(http, ProviderSpec.of(p, settings), broker, settings.voiceName,
                vad = settings.turnTaking == TurnTaking.VAD)
        },
        textFactory = { p: Provider ->
            ChatTextTransport(http, ProviderSpec.of(p, settings), { settings.tokenBrokerUrl }, { secrets.brokerAppToken })
        },
        ctx = this,
        onEvent = { e -> main.post { onAgentEvent(e) } },
    ).also { session = it }

    private fun onAgentEvent(e: AgentEvent) {
        when (e) {
            is AgentEvent.TextDelta -> { answer.append(e.text); if (mode == Mode.WORKING) render() }
            is AgentEvent.TranscriptDelta -> {
                if (e.fromUser) answer = StringBuilder("\u201C${e.text.trim()}\u201D ")
                else answer.append(e.text)
                if (mode == Mode.OFF || mode == Mode.WORKING || mode == Mode.MESSAGE || mode == Mode.TYPING) render()
            }
            is AgentEvent.AudioChunk -> if (settings.spokenReply != SpokenReply.NEVER) audio.play(e.pcm16)
            is AgentEvent.SpeechStarted -> if (settings.bargeIn) { audio.flushPlayback(); session?.interrupt() }
            is AgentEvent.ToolCall -> {}
            is AgentEvent.ToolDone -> {}
            is AgentEvent.Status -> when {
                e.what == "ready" -> if (voiceOn) render(status = ime.getString(R.string.agent_listening))
                e.what.startsWith("fallback:") -> render(status = ime.getString(R.string.agent_fallback,
                    e.what.removePrefix("fallback:").lowercase().replaceFirstChar { it.uppercase() }))
            }
            is AgentEvent.Failed -> error(e.reason)
            AgentEvent.TurnDone -> if (mode == Mode.WORKING) {
                // Answer with nothing inserted: leave it on the strip; next key press starts a new request.
                mode = if (answer.isBlank()) Mode.TYPING else Mode.MESSAGE
                render()
            }
        }
    }

    // ---------------------------------------------------------------- ToolContext

    override val packageName: String? get() = ime.currentInputEditorInfo?.packageName

    override val fieldLanguage: String?
        get() {
            val before = ime.currentInputConnection?.getTextBeforeCursor(500, 0)?.toString().orEmpty()
            val he = before.count { it in '\u0590'..'\u05FF' }
            val latin = before.count { it in 'a'..'z' || it in 'A'..'Z' }
            return when {
                he > latin -> "he"
                latin > he -> "en"
                else -> RichInputMethodManager.getInstance().currentSubtypeLocale.language
            }
        }

    override suspend fun preview(text: String): Boolean = withContext(Dispatchers.Main) {
        if (settings.insertMode == InsertMode.AUTO) return@withContext true
        val d = CompletableDeferred<Boolean>()
        pending = d
        mode = Mode.PREVIEW
        strip().apply {
            setText(text)
            setButtons(
                AgentStripView.Action(R.string.agent_insert, primary = true) { resolvePending(true) },
                AgentStripView.Action(R.string.agent_cancel) { resolvePending(false) },
            )
        }
        val ok = d.await()
        mode = if (voiceOn) Mode.MESSAGE else Mode.TYPING
        answer = StringBuilder()
        render()
        ok
    }

    override suspend fun confirm(title: String, detail: String): Boolean = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<Boolean>()
        pending = d
        val previous = mode
        mode = Mode.CONFIRM
        strip().apply {
            setText(ime.getString(R.string.agent_confirm_run, title) + "  " + detail.take(200))
            setButtons(
                AgentStripView.Action(R.string.agent_allow, primary = true) { resolvePending(true) },
                AgentStripView.Action(R.string.agent_deny) { resolvePending(false) },
            )
        }
        val ok = d.await()
        mode = if (previous == Mode.CONFIRM) Mode.WORKING else previous
        render()
        ok
    }

    private fun resolvePending(v: Boolean) { pending?.complete(v); pending = null }

    // ---------------------------------------------------------------- rendering

    private fun strip(): AgentStripView =
        (strip ?: AgentStripView(ime).also { strip = it }).also { ime.showAgentStrip(it) }

    private fun render(status: String? = null) {
        val s = strip()
        when (mode) {
            Mode.TYPING -> return renderTyping()
            Mode.WORKING -> s.setText(answer.ifEmpty { status ?: ime.getString(R.string.agent_thinking) }, italic = answer.isEmpty())
            Mode.MESSAGE -> s.setText(answer)
            Mode.OFF -> s.setText(answer.ifEmpty { status ?: ime.getString(R.string.agent_listening) }, italic = answer.isEmpty())
            Mode.PREVIEW, Mode.CONFIRM -> return
        }
        s.setButtons(*stripButtons())
    }

    private fun renderTyping() {
        if (mode == Mode.MESSAGE) mode = Mode.TYPING
        val s = strip()
        s.setText(buffer, hint = ime.getString(R.string.agent_hint))
        s.setButtons(*stripButtons())
    }

    private fun stripButtons(): Array<AgentStripView.Button> = buildList {
        if (voiceOn) {
            if (settings.turnTaking == TurnTaking.PUSH_TO_TALK)
                add(AgentStripView.Hold(R.string.agent_hold_to_talk,
                    onDown = { audio.flushPlayback(); startCapture() },
                    onUp = { audio.stopCapture(); session?.commitAudio() }))
            add(AgentStripView.Action(R.string.agent_stop) { toggleVoice() })
        } else when (mode) {
            Mode.TYPING -> add(AgentStripView.Action(R.string.agent_send, primary = true) { submitTyped() })
            Mode.WORKING -> add(AgentStripView.Action(R.string.agent_stop) { session?.cancel(); mode = Mode.TYPING; render() })
            else -> {}
        }
        add(AgentStripView.Action(R.string.agent_exit) { exit() })
    }.toTypedArray()

    private fun message(text: String) {
        if (mode == Mode.OFF && !voiceOn) mode = Mode.MESSAGE
        answer = StringBuilder(text)
        strip().setText(text)
        strip().setButtons(AgentStripView.Action(R.string.agent_exit) { exit() })
    }

    private fun error(reason: String) {
        Log.w(TAG, "agent error: $reason")
        if (mode == Mode.WORKING) mode = Mode.MESSAGE
        message(ime.getString(R.string.agent_error, reason.take(160)))
    }

    // ---------------------------------------------------------------- helpers

    private fun checkReady(): Boolean {
        if (isPasswordField()) { message(ime.getString(R.string.agent_password_blocked)); return false }
        if (settings.tokenBrokerUrl.isBlank() || secrets.brokerAppToken.isNullOrBlank()) {
            message(ime.getString(R.string.agent_not_configured)); return false
        }
        ScreenContextHolder.blockedPackages = settings.blockedPackages
        return true
    }

    /** Password fields are never read or written by the agent. Not a setting. */
    fun isPasswordField(): Boolean {
        val t = ime.currentInputEditorInfo?.inputType ?: return false
        val v = t and InputType.TYPE_MASK_VARIATION
        val cls = t and InputType.TYPE_MASK_CLASS
        return (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun fieldHint(): String? {
        val ei = ime.currentInputEditorInfo ?: return null
        val parts = listOfNotNull(ei.hintText?.toString()?.takeIf { it.isNotBlank() }, ei.label?.toString()?.takeIf { it.isNotBlank() })
        val screen = if (settings.screenMode == ScreenMode.ALWAYS_ATTACH) " (call read_screen first for context)" else ""
        return (parts.joinToString(" / ").ifBlank { null }?.plus(screen)) ?: screen.ifBlank { null }
    }

    /** Write-looking connector actions ask first (unless the user set a policy for run_action). */
    private fun isWriteAction(id: String): Boolean {
        val l = id.lowercase()
        return WRITE_WORDS.any { it in l }
    }

    /** One-shot vision call through the broker's chat proxy, used by describe_screen. */
    private suspend fun describeImage(png: ByteArray, question: String): String = withContext(Dispatchers.IO) {
        if (!settings.allowScreenshot) return@withContext "Screenshots are off in settings."
        val spec = ProviderSpec.of(settings.textProvider, settings)
        val body = buildJsonObject {
            put("model", spec.textModel)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", question) }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)) }
                        }
                    }
                }
            }
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(settings.tokenBrokerUrl.trimEnd('/') + "/chat/" + spec.provider.name.lowercase())
            .header("Authorization", "Bearer ${secrets.brokerAppToken}").post(body).build()
        runCatching {
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@use "Vision request failed: ${r.code}"
                Json.parseToJsonElement(txt).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!
                    .jsonObject["content"]!!.jsonPrimitive.content
            }
        }.getOrElse { "Vision request failed: ${it.message}" }
    }

    private companion object {
        const val TAG = "AgentController"
        val WRITE_WORDS = listOf("send", "create", "delete", "remove", "update", "post", "reply", "write",
            "insert", "move", "archive", "pay", "transfer", "invite", "share", "publish", "merge", "close")
    }
}
