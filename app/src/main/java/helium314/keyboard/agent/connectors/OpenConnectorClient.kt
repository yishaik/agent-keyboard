package helium314.keyboard.agent.connectors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for a self-hosted OpenConnector runtime (github.com/oomol-lab/open-connector)
 * on Cloudflare Workers. Authenticates with a runtime token (never the admin token).
 *
 * Endpoints used (verify against docs/runtime-api.md of your deployed version):
 *  GET  /health
 *  GET  /v1/...catalog / connections listing   (TODO: confirm exact paths)
 *  POST /v1/actions/{actionId}  body {"input": {...}}
 */
class OpenConnectorClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val runtimeToken: () -> String?,
) {
    private fun req(path: String) = Request.Builder().url(baseUrl().trimEnd('/') + path)
        .header("Authorization", "Bearer ${runtimeToken() ?: error("OpenConnector runtime token not set")}")

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url(baseUrl().trimEnd('/') + "/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun runAction(actionId: String, input: JsonObject, connection: String?): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("input", input)
            connection?.let { put("connection", it) } // TODO: confirm field name for named connections
        }.toString().toRequestBody("application/json".toMediaType())
        http.newCall(req("/v1/actions/$actionId").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "OpenConnector ${r.code}: ${text.take(300)}" }
            Json.parseToJsonElement(text)
        }
    }

    /** TODO: back with the runtime's catalog search endpoint, filtered to authenticated apps. */
    suspend fun searchActions(query: String): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(req("/v1/catalog/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")).get().build()).execute().use { r ->
            Json.parseToJsonElement(r.body?.string().orEmpty().ifBlank { "[]" })
        }
    }

    /** Web Console URL for adding connections in a Chrome Custom Tab. */
    fun consoleUrl(): String = baseUrl().trimEnd('/') + "/"
}
