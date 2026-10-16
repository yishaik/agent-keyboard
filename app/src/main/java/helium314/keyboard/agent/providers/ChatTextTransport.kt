package helium314.keyboard.agent.providers

import helium314.keyboard.agent.harness.AgentEvent
import helium314.keyboard.agent.harness.TextTransport
import helium314.keyboard.agent.harness.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Text mode: chat completions with function calling. api.x.ai and api.openai.com
 * accept the same request shape. Requests go through the token-broker Worker's
 * /chat/{provider} proxy, so no provider key is ever on the phone.
 * Non-streaming for the skeleton; TODO: SSE streaming.
 */
class ChatTextTransport(
    private val http: OkHttpClient,
    private val spec: ProviderSpec,
    private val brokerUrl: () -> String,
    private val appToken: () -> String?,
) : TextTransport {
    private val messages = mutableListOf<JsonObject>()
    private var tools: List<JsonObject> = emptyList()

    override fun run(instructions: String, tools: List<JsonObject>, history: List<Turn>, user: String): Flow<AgentEvent> {
        this.tools = tools
        messages.clear()
        messages += msg("system", instructions)
        history.forEach { messages += msg(it.role, it.text) }
        messages += msg("user", user)
        return complete()
    }

    override suspend fun sendToolResult(callId: String, output: String): Flow<AgentEvent> {
        messages += buildJsonObject { put("role", "tool"); put("tool_call_id", callId); put("content", output) }
        return complete()
    }

    private fun complete(): Flow<AgentEvent> = flow {
        val body = buildJsonObject {
            put("model", spec.textModel)
            putJsonArray("messages") { messages.forEach { add(it) } }
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { t -> addJsonObject {
                put("type", "function")
                putJsonObject("function") { put("name", t["name"]!!); put("description", t["description"]!!); put("parameters", t["parameters"]!!) }
            } } }
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(brokerUrl().trimEnd('/') + "/chat/" + spec.provider.name.lowercase())
            .header("Authorization", "Bearer ${appToken() ?: error("Token broker not configured")}").post(body).build()
        val res = http.newCall(req).execute()
        if (!res.isSuccessful) { emit(AgentEvent.Failed("HTTP ${res.code}", retryable = res.code >= 500 || res.code == 429)); return@flow }
        val msg = Json.parseToJsonElement(res.body!!.string()).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        messages += msg
        msg["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { emit(AgentEvent.TextDelta(it)) }
        msg["tool_calls"]?.jsonArray?.forEach { c ->
            val o = c.jsonObject; val f = o["function"]!!.jsonObject
            emit(AgentEvent.ToolCall(o["id"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content,
                Json.parseToJsonElement(f["arguments"]!!.jsonPrimitive.content).jsonObject))
        }
        emit(AgentEvent.TurnDone)
    }.flowOn(Dispatchers.IO)

    private fun msg(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
}
