package helium314.keyboard.agent.providers

import android.util.Base64
import helium314.keyboard.agent.harness.AgentEvent
import helium314.keyboard.agent.harness.Turn
import helium314.keyboard.agent.harness.VoiceTransport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import okhttp3.*

/**
 * OpenAI-Realtime-compatible WebSocket client. Grok Voice speaks the same event
 * shape, so one class serves both; a few event names differ and are handled here.
 */
class RealtimeVoiceTransport(
    private val http: OkHttpClient,
    private val spec: ProviderSpec,
    private val broker: TokenBroker,
    private val voiceName: String,
    private val vad: Boolean,
) : VoiceTransport {
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<AgentEvent> = _events
    private var ws: WebSocket? = null

    override suspend fun connect(instructions: String, tools: List<JsonObject>, history: List<Turn>) {
        val token = broker.ephemeral(spec.provider, "realtime")
        val req = Request.Builder().url(spec.realtimeUrl).header("Authorization", "Bearer $token").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(sessionUpdate(instructions, tools))
                history.forEach { send(historyItem(it)) } // failover replay
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _events.tryEmit(AgentEvent.Failed(t.message ?: "socket failure", retryable = true))
            }
        })
    }

    private fun sessionUpdate(instructions: String, tools: List<JsonObject>) = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("instructions", instructions)
            if (voiceName.isNotBlank()) put("voice", voiceName)
            putJsonArray("tools") { tools.forEach { add(it) } }
            if (vad) putJsonObject("turn_detection") { put("type", "server_vad") }
            else put("turn_detection", JsonNull)
        }
    }

    private fun historyItem(t: Turn) = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message"); put("role", t.role)
            putJsonArray("content") { addJsonObject {
                put("type", if (t.role == "user") "input_text" else "output_text"); put("text", t.text)
            } }
        }
    }

    private fun handle(raw: String) {
        val e = Json.parseToJsonElement(raw).jsonObject
        val type = e["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            // audio out: GA name and older/xAI name
            "response.output_audio.delta", "response.audio.delta" ->
                e["delta"]?.jsonPrimitive?.content?.let { _events.tryEmit(AgentEvent.AudioChunk(Base64.decode(it, Base64.NO_WRAP))) }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" ->
                e["delta"]?.jsonPrimitive?.content?.let { _events.tryEmit(AgentEvent.TranscriptDelta(it, fromUser = false)) }
            "conversation.item.input_audio_transcription.completed" ->
                e["transcript"]?.jsonPrimitive?.content?.let { _events.tryEmit(AgentEvent.TranscriptDelta(it, fromUser = true)) }
            "response.function_call_arguments.done" -> _events.tryEmit(AgentEvent.ToolCall(
                callId = e["call_id"]!!.jsonPrimitive.content,
                name = e["name"]!!.jsonPrimitive.content,
                args = runCatching { Json.parseToJsonElement(e["arguments"]!!.jsonPrimitive.content).jsonObject }.getOrDefault(JsonObject(emptyMap())),
            ))
            "response.done" -> _events.tryEmit(AgentEvent.TurnDone)
            "error" -> _events.tryEmit(AgentEvent.Failed(e["error"]?.toString() ?: "error", retryable = false))
        }
    }

    override fun sendAudio(pcm16: ByteArray) {
        send(buildJsonObject { put("type", "input_audio_buffer.append"); put("audio", Base64.encodeToString(pcm16, Base64.NO_WRAP)) })
    }

    override fun sendToolResult(callId: String, output: String) {
        send(buildJsonObject {
            put("type", "conversation.item.create")
            putJsonObject("item") { put("type", "function_call_output"); put("call_id", callId); put("output", output) }
        })
        send(buildJsonObject { put("type", "response.create") })
    }

    override fun interrupt() = send(buildJsonObject { put("type", "response.cancel") })
    override fun close() { ws?.close(1000, null); ws = null }
    private fun send(o: JsonObject) { ws?.send(o.toString()) }
}
