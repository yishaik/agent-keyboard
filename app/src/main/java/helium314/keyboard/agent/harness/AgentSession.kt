package helium314.keyboard.agent.harness

import helium314.keyboard.agent.settings.AgentSettings
import helium314.keyboard.agent.settings.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
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
    private val ctx: ToolContext,
    private val onEvent: (AgentEvent) -> Unit,
) {
    private val history = mutableListOf<Turn>()
    private var voice: VoiceTransport? = null
    private var provider: Provider? = null

    fun startVoice(language: String, fieldHint: String?) {
        val s = settings()
        val p = s.voiceProviderFor(language)
        connectVoice(p, fieldHint)
    }

    private fun connectVoice(p: Provider, fieldHint: String?) {
        val s = settings()
        provider = p
        val t = voiceFactory(p).also { voice = it }
        scope.launch {
            t.connect(PromptBuilder.build(s, ctx.packageName, ctx.fieldLanguage, fieldHint), registry.definitions(), history)
            t.events.collect { e ->
                when (e) {
                    is AgentEvent.ToolCall -> {
                        val r = registry.call(e.name, e.args, ctx)
                        t.sendToolResult(e.callId, r.output)
                    }
                    is AgentEvent.TranscriptDelta -> { record(e); onEvent(e) }
                    is AgentEvent.Failed -> {
                        val other = if (p == Provider.GROK) Provider.OPENAI else Provider.GROK
                        if (e.retryable && s.autoFallback && provider == p) {
                            t.close(); connectVoice(other, fieldHint)
                        } else onEvent(e)
                    }
                    else -> onEvent(e)
                }
            }
        }
    }

    fun sendAudio(pcm16: ByteArray) = voice?.sendAudio(pcm16)
    fun interrupt() = voice?.interrupt()
    fun close() { voice?.close(); voice = null }

    private fun record(e: AgentEvent.TranscriptDelta) {
        val role = if (e.fromUser) "user" else "assistant"
        val last = history.lastOrNull()
        if (last != null && last.role == role) history[history.lastIndex] = last.copy(text = last.text + e.text)
        else history += Turn(role, e.text)
    }
}
