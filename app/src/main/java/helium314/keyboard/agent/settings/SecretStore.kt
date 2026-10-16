package helium314.keyboard.agent.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Holds secrets: the OpenConnector runtime token and the token-broker app token.
 * Provider API keys never live on the phone; the token broker Worker mints
 * short-lived ephemeral tokens instead.
 *
 * TODO: back with androidx.security EncryptedSharedPreferences / Android Keystore.
 */
class SecretStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_secrets", Context.MODE_PRIVATE)

    var connectorRuntimeToken: String?
        get() = prefs.getString("connector_runtime_token", null)
        set(v) = prefs.edit().putString("connector_runtime_token", v).apply()

    var brokerAppToken: String?
        get() = prefs.getString("broker_app_token", null)
        set(v) = prefs.edit().putString("broker_app_token", v).apply()
}
