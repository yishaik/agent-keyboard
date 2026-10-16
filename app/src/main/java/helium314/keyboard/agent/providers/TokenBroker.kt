package helium314.keyboard.agent.providers

import helium314.keyboard.agent.settings.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Asks your Cloudflare token Worker (worker/token-broker) for a short-lived
 * provider token. Provider API keys live only in the Worker's secrets.
 */
class TokenBroker(private val http: OkHttpClient, private val baseUrl: () -> String, private val appToken: () -> String?) {
    suspend fun ephemeral(provider: Provider, kind: String = "realtime"): String = withContext(Dispatchers.IO) {
        val body = """{"provider":"${provider.name.lowercase()}","kind":"$kind"}"""
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(baseUrl().trimEnd('/') + "/token")
            .header("Authorization", "Bearer ${appToken() ?: error("Token broker not configured")}")
            .post(body).build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "Token broker ${r.code}" }
            Json.parseToJsonElement(r.body!!.string()).jsonObject["token"]!!.jsonPrimitive.content
        }
    }
}
