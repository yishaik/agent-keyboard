package helium314.keyboard.agent.harness

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Events every transport emits, normalised across Grok and OpenAI. */
sealed interface AgentEvent {
    data class TranscriptDelta(val text: String, val fromUser: Boolean) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class AudioChunk(val pcm16: ByteArray) : AgentEvent
    data class ToolCall(val callId: String, val name: String, val args: JsonObject) : AgentEvent
    data object TurnDone : AgentEvent
    data class Failed(val reason: String, val retryable: Boolean) : AgentEvent
}

data class Turn(val role: String, val text: String)

/** Realtime speech-to-speech session (Grok Voice / OpenAI Realtime). */
interface VoiceTransport {
    val events: Flow<AgentEvent>
    suspend fun connect(instructions: String, tools: List<JsonObject>, history: List<Turn>)
    fun sendAudio(pcm16: ByteArray)
    fun sendToolResult(callId: String, output: String)
    fun interrupt()
    fun close()
}

/** Typed requests via chat completions with function calling. */
interface TextTransport {
    fun run(instructions: String, tools: List<JsonObject>, history: List<Turn>, user: String): Flow<AgentEvent>
    suspend fun sendToolResult(callId: String, output: String): Flow<AgentEvent>
}
