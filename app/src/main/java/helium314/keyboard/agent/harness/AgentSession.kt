package helium314.keyboard.agent.harness

import helium314.keyboard.agent.settings.AgentSettings
import helium314.keyboard.agent.settings.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * One conversation for one field. Picks the provider from settings (per language),
 * runs the tool loop, and fails over to the other provider when allowed,
 * replaying the transcript so the user doesn't notice.
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val settings: () -> AgentSettings,
    private val registry: ToolRegistry,
    private val voiceFactory: (Provider) -> VoiceTransport,
    private val textFactory: (Provider) -> TextTransport,
    private val ctx: ToolContext,
    private val onEvent: (AgentEvent) -> Unit,
) {
    private val history = mutableListOf<Turn>()
    private var voice: VoiceTransport? = null
    private var voiceJob: Job? = null
    private var textJob: Job? = null
    private var provider: Provider? = null

    val isVoiceActive get() = voice != null

    // ---------- voice ----------

    fun startVoice(language: String, fieldHint: String?, screenText: String? = null) {
        connectVoice(settings().voiceProviderFor(language), fieldHint, screenText, failedOver = false)
    }

    private fun connectVoice(p: Provider, fieldHint: String?, screenText: String?, failedOver: Boolean) {
        val s = settings()
        provider = p
        val t = voiceFactory(p).also { voice = it }
        voiceJob = scope.launch {
            launch {
                t.events.collect { e ->
                    when (e) {
                        is AgentEvent.ToolCall -> scope.launch {
                            val r = registry.call(e.name, e.args, ctx)
                            onEvent(AgentEvent.ToolDone(e.name, r.isError))
                            t.sendToolResult(e.callId, r.output)
                        }
                        is AgentEvent.TranscriptDelta -> { record(e.text, e.fromUser); onEvent(e) }
                        is AgentEvent.Failed -> {
                            if (e.retryable && s.autoFallback && !failedOver && voice === t) {
                                t.close()
                                onEvent(AgentEvent.Status("fallback:${other(p).name}"))
                                connectVoice(other(p), fieldHint, screenText, failedOver = true)
                            } else onEvent(e)
                        }
                        else -> onEvent(e)
                    }
                }
            }
            runCatching {
                t.connect(PromptBuilder.build(s, ctx.packageName, ctx.fieldLanguage, fieldHint, screenText), registry.definitions(), history)
            }.onFailure {
                if (s.autoFallback && !failedOver) {
                    t.close()
                    connectVoice(other(p), fieldHint, screenText, failedOver = true)
                } else onEvent(AgentEvent.Failed(it.message ?: "connect failed", false))
            }
        }
    }

    fun sendAudio(pcm16: ByteArray) { voice?.sendAudio(pcm16) }
    /** Push-to-talk: user released the button. */
    fun commitAudio() { voice?.commitAudio() }
    fun interrupt() { voice?.interrupt() }

    fun stopVoice() {
        voice?.close(); voice = null
        voiceJob?.cancel(); voiceJob = null
    }

    // ---------- text ----------

    /** Typed request. Runs the chat + tool loop until the model stops calling tools. */
    fun sendText(text: String, fieldHint: String?, screenText: String? = null) {
        textJob?.cancel()
        val s = settings()
        textJob = scope.launch {
            val first = s.textProvider
            val ok = runTextWith(first, text, fieldHint, screenText)
            if (!ok && s.autoFallback) {
                onEvent(AgentEvent.Status("fallback:${other(first).name}"))
                if (!runTextWith(other(first), text, fieldHint, screenText)) lastTextFailure?.let(onEvent)
            } else if (!ok) lastTextFailure?.let(onEvent)
        }
    }

    /** Last failure of a text round, shown if every provider failed before showing anything. */
    private var lastTextFailure: AgentEvent.Failed? = null

    private suspend fun runTextWith(p: Provider, text: String, fieldHint: String?, screenText: String?): Boolean {
        val s = settings()
        val t = textFactory(p)
        val prompt = PromptBuilder.build(s, ctx.packageName, ctx.fieldLanguage, fieldHint, screenText)
        var flow: Flow<AgentEvent>? = t.run(prompt, registry.definitions(), history.toList(), text)
        record(text, fromUser = true)
        var rounds = 0
        while (flow != null && rounds++ < 8) {
            val calls = mutableListOf<AgentEvent.ToolCall>()
            var failed: AgentEvent.Failed? = null
            try {
                flow.collect { e ->
                    when (e) {
                        is AgentEvent.ToolCall -> calls += e
                        is AgentEvent.TextDelta -> { record(e.text, fromUser = false); onEvent(e) }
                        is AgentEvent.Failed -> failed = e
                        else -> onEvent(e)
                    }
                }
            } catch (e: Exception) {
                failed = AgentEvent.Failed(e.message ?: "request failed", retryable = true)
            }
            failed?.let { f ->
                lastTextFailure = f
                if (rounds == 1 && f.retryable) return false // nothing shown yet: let caller fall back
                onEvent(f); return true
            }
            if (calls.isEmpty()) break
            flow = null
            for (c in calls) {
                val r = registry.call(c.name, c.args, ctx)
                onEvent(AgentEvent.ToolDone(c.name, r.isError))
                flow = t.sendToolResult(c.callId, r.output) // last one carries the follow-up completion
            }
        }
        onEvent(AgentEvent.TurnDone)
        return true
    }

    fun cancel() {
        textJob?.cancel(); textJob = null
        stopVoice()
    }

    fun close() { cancel(); history.clear() }

    private fun other(p: Provider) = if (p == Provider.GROK) Provider.OPENAI else Provider.GROK

    private fun record(text: String, fromUser: Boolean) {
        val role = if (fromUser) "user" else "assistant"
        val last = history.lastOrNull()
        if (last != null && last.role == role) history[history.lastIndex] = last.copy(text = last.text + text)
        else history += Turn(role, text)
        while (history.size > 40) history.removeAt(0)
    }
}
