package helium314.keyboard.agent.connectors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for a self-hosted OpenConnector runtime (github.com/oomol-lab/open-connector)
 * on Cloudflare Workers. Authenticates with a runtime token (never the admin token).
 *
 * Uses the runtime's MCP endpoint (stateless JSON-RPC over `POST /mcp`), whose small
 * discovery tool set is exactly what the keyboard needs:
 *   search_actions {query, service?, limit}  -> ranked actions with input summaries
 *   list_connections {service?}               -> accounts (connectionName) per provider
 *   get_action_guide {actionId, connectionName?}
 *   execute_action {actionId, input, connectionName?}
 * See docs/runtime-api.md "MCP" in the OpenConnector repo.
 */
class OpenConnectorClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val runtimeToken: () -> String?,
) {
    private val ids = AtomicInteger(1)
    val isConfigured get() = baseUrl().isNotBlank() && !runtimeToken().isNullOrBlank()

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(baseUrl().trimEnd('/') + "/health").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun searchActions(query: String, service: String? = null, limit: Int = 8): JsonElement =
        callTool("search_actions", buildJsonObject {
            put("query", query); service?.let { put("service", it) }; put("limit", limit)
        })

    suspend fun listConnections(service: String? = null): JsonElement =
        callTool("list_connections", buildJsonObject { service?.let { put("service", it) } })

    suspend fun actionGuide(actionId: String, connection: String? = null): JsonElement =
        callTool("get_action_guide", buildJsonObject { put("actionId", actionId); connection?.let { put("connectionName", it) } })

    suspend fun runAction(actionId: String, input: JsonObject, connection: String?): JsonElement =
        callTool("execute_action", buildJsonObject {
            put("actionId", actionId); put("input", input); connection?.let { put("connectionName", it) }
        })

    /** JSON-RPC tools/call; returns the tool's JSON payload (or its text when not JSON). */
    private suspend fun callTool(name: String, args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        check(isConfigured) { "Connections are not set up (Settings › Connections)" }
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ids.getAndIncrement()); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", args) }
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(baseUrl().trimEnd('/') + "/mcp")
            .header("Authorization", "Bearer ${runtimeToken()}")
            .header("Accept", "application/json, text/event-stream")
            .post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "OpenConnector ${r.code}: ${text.take(300)}" }
            val msg = parseRpc(text)
            msg["error"]?.let { error("OpenConnector: ${it.jsonObject["message"]?.jsonPrimitive?.content ?: it}") }
            val result = msg["result"]?.jsonObject ?: return@use JsonNull
            val payload = result["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }?.joinToString("\n").orEmpty()
            val parsed = runCatching { Json.parseToJsonElement(payload) }.getOrElse { JsonPrimitive(payload) }
            if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) error("OpenConnector: ${payload.take(300)}")
            parsed
        }
    }

    /** Accepts a plain JSON response or a single SSE `data:` frame. */
    private fun parseRpc(text: String): JsonObject {
        val json = if (text.trimStart().startsWith("{")) text
            else text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.lastOrNull().orEmpty()
        return Json.parseToJsonElement(json).jsonObject
    }

    /** Web Console URL for adding connections in a Chrome Custom Tab. */
    fun consoleUrl(): String = baseUrl().trimEnd('/') + "/"
}
