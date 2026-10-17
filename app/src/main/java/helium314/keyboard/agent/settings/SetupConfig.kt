package helium314.keyboard.agent.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * Server config for the phone: broker + OpenConnector. Tokens never go in git;
 * they arrive from the password-gated setup page via a deep link or one paste.
 */
data class SetupConfig(
    val brokerUrl: String = "",
    val appToken: String = "",
    val connectorUrl: String = "",
    val runtimeToken: String = "",
) {
    val hasBroker get() = brokerUrl.isNotBlank() && appToken.isNotBlank()
    val hasConnector get() = connectorUrl.isNotBlank() && runtimeToken.isNotBlank()
    val isEmpty get() = !hasBroker && !hasConnector

    fun withDefaults() = copy(
        brokerUrl = brokerUrl.ifBlank { DEFAULT_BROKER_URL },
        connectorUrl = connectorUrl.ifBlank { DEFAULT_CONNECTOR_URL },
    )

    companion object {
        const val DEFAULT_BROKER_URL = "https://agent-keyboard-token-broker.yishai-k.workers.dev"
        const val DEFAULT_CONNECTOR_URL = "https://connect.yishaik.com"
        const val SETUP_PAGE_URL = "https://agent-keyboard-setup.yishai-k.workers.dev/"
        const val SCHEME = "agentkeyboard"
        const val HOST = "setup"

        fun parse(raw: String?): SetupConfig {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return SetupConfig()
            if (s.startsWith("{")) return fromJson(s)
            return fromQuery(s)
        }

        private fun fromJson(s: String): SetupConfig {
            val o = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return SetupConfig()
            fun f(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
                o[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            return SetupConfig(
                brokerUrl = f("broker", "brokerUrl", "tokenBrokerUrl"),
                appToken = f("app", "appToken", "brokerToken", "token"),
                connectorUrl = f("connector", "connectorUrl", "workerUrl"),
                runtimeToken = f("runtime", "runtimeToken"),
            )
        }

        private fun fromQuery(raw: String): SetupConfig {
            val q = raw.substringAfter('?', raw).substringBefore('#')
            val map = LinkedHashMap<String, String>()
            for (part in q.split('&')) {
                if (part.isEmpty()) continue
                val i = part.indexOf('=')
                if (i <= 0) continue
                map[decode(part.substring(0, i))] = decode(part.substring(i + 1))
            }
            fun f(vararg keys: String) = keys.firstNotNullOfOrNull { map[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }.orEmpty()
            return SetupConfig(
                brokerUrl = f("broker", "brokerUrl"),
                appToken = f("app", "appToken", "token"),
                connectorUrl = f("connector", "connectorUrl"),
                runtimeToken = f("runtime", "runtimeToken"),
            )
        }

        private fun decode(s: String): String = runCatching {
            URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(s)
    }
}
