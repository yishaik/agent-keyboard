package helium314.keyboard.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a token-broker error response ({"error": code, "message": ...}) into a short reason the
 * panel can map to a localized string. Reasons look like "broker:daily_limit" or
 * "broker:model_not_allowed:grok-4.7"; anything else stays "HTTP <code>".
 */
object BrokerError {
    const val PREFIX = "broker:"

    data class Parsed(val reason: String, val retryable: Boolean)

    fun parse(status: Int, body: String?, model: String? = null): Parsed {
        val code = runCatching {
            Json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when {
            status == 401 && code == "unauthorized" -> Parsed("${PREFIX}unauthorized", false)
            status == 403 && code == "model_not_allowed" -> Parsed("${PREFIX}model_not_allowed:${model.orEmpty()}", true) // the other provider may be allowed
            status == 413 -> Parsed("${PREFIX}too_large", false)
            status == 429 && code == "daily_limit" -> Parsed("${PREFIX}daily_limit", false) // shared by both providers
            else -> Parsed("HTTP $status", status >= 500 || status == 429)
        }
    }
}
