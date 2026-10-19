package helium314.keyboard.agent.settings

import java.net.URI

/**
 * Decides whether a setup payload (deep link, intent extras or in-app paste) may be applied.
 *
 * agentkeyboard://setup is reachable from any web page and any installed app, so a payload is
 * untrusted input. Rules:
 *  - every URL must be https, with no user-info and the default port;
 *  - its host must be in [ALLOWED_HOSTS]. Other hosts are rejected outright, not just warned
 *    about: a warning still lets a convincing page talk the user into one more tap, and pointing
 *    the broker or connector at someone else's server hands them every prompt, screenshot and
 *    connected-app call. Moving servers means changing [ALLOWED_HOSTS] and rebuilding.
 *  - tokens must be printable and of sane length.
 * Nothing here logs or returns token values.
 */
object SetupGuard {
    val ALLOWED_HOSTS: Set<String> = setOf(
        hostOf(SetupConfig.DEFAULT_BROKER_URL)!!,  // agent-keyboard-token-broker.yishai-k.workers.dev
        hostOf(SetupConfig.SETUP_PAGE_URL)!!,      // agent-keyboard-setup.yishai-k.workers.dev
        hostOf(SetupConfig.DEFAULT_CONNECTOR_URL)!!, // connect.yishaik.com
    )

    const val MAX_TOKEN_LENGTH = 4096

    enum class Field { BROKER_URL, APP_TOKEN, CONNECTOR_URL, RUNTIME_TOKEN }

    enum class Problem { NOT_HTTPS, HOST_NOT_ALLOWED, BAD_URL, BAD_TOKEN }

    /** One line of the confirmation screen. [host] is set for URL fields, never a token value. */
    data class Change(val field: Field, val host: String?, val replacesExisting: Boolean, val hostChanges: Boolean)

    sealed interface Review {
        data object Empty : Review
        data class Rejected(val field: Field, val problem: Problem, val host: String?) : Review
        data class Ok(val config: SetupConfig, val changes: List<Change>) : Review
    }

    /** What is stored now, without exposing token values. */
    data class Current(val brokerUrl: String, val hasAppToken: Boolean, val connectorUrl: String, val hasRuntimeToken: Boolean)

    fun review(raw: SetupConfig, current: Current): Review {
        if (listOf(raw.brokerUrl, raw.appToken, raw.connectorUrl, raw.runtimeToken).all { it.isBlank() }) return Review.Empty
        val cfg = raw.withDefaults() // this is exactly what AgentSetup.apply writes
        checkUrl(Field.BROKER_URL, cfg.brokerUrl)?.let { return it }
        checkUrl(Field.CONNECTOR_URL, cfg.connectorUrl)?.let { return it }
        if (cfg.appToken.isNotEmpty() && !tokenOk(cfg.appToken)) return Review.Rejected(Field.APP_TOKEN, Problem.BAD_TOKEN, null)
        if (cfg.runtimeToken.isNotEmpty() && !tokenOk(cfg.runtimeToken)) return Review.Rejected(Field.RUNTIME_TOKEN, Problem.BAD_TOKEN, null)

        val changes = buildList {
            val bHost = hostOf(cfg.brokerUrl)
            val oldB = hostOf(current.brokerUrl)
            if (cfg.brokerUrl != current.brokerUrl)
                add(Change(Field.BROKER_URL, bHost, current.brokerUrl.isNotBlank(), oldB != null && oldB != bHost))
            if (cfg.appToken.isNotEmpty()) add(Change(Field.APP_TOKEN, bHost, current.hasAppToken, false))
            val cHost = hostOf(cfg.connectorUrl)
            val oldC = hostOf(current.connectorUrl)
            if (cfg.connectorUrl != current.connectorUrl)
                add(Change(Field.CONNECTOR_URL, cHost, current.connectorUrl.isNotBlank(), oldC != null && oldC != cHost))
            if (cfg.runtimeToken.isNotEmpty()) add(Change(Field.RUNTIME_TOKEN, cHost, current.hasRuntimeToken, false))
        }
        if (changes.isEmpty()) return Review.Empty
        return Review.Ok(cfg, changes)
    }

    private fun checkUrl(field: Field, url: String): Review.Rejected? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return Review.Rejected(field, Problem.BAD_URL, null)
        val host = u.host?.lowercase()?.trimEnd('.')
        if (!"https".equals(u.scheme, ignoreCase = true)) return Review.Rejected(field, Problem.NOT_HTTPS, host)
        if (host.isNullOrEmpty() || u.rawUserInfo != null || (u.port != -1 && u.port != 443))
            return Review.Rejected(field, Problem.BAD_URL, host)
        if (host !in ALLOWED_HOSTS) return Review.Rejected(field, Problem.HOST_NOT_ALLOWED, host)
        return null
    }

    private fun tokenOk(t: String) = t.length <= MAX_TOKEN_LENGTH && t.all { it in '\u0021'..'\u007e' }

    fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.trimEnd('.') }.getOrNull()
}
