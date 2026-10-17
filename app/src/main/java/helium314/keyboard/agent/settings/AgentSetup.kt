package helium314.keyboard.agent.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils

/** What the first-run checklist still needs. */
data class AgentSetupState(
    val imeEnabled: Boolean,
    val imeCurrent: Boolean,
    val assistant: Boolean,
    val broker: Boolean,
    val connector: Boolean,
) {
    val ready get() = imeEnabled && imeCurrent && assistant && broker
}

object AgentSetup {
    fun state(context: Context): AgentSetupState {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val store = AgentSettingsStore.get(context)
        val secrets = SecretStore(context)
        val s = store.state.value
        return AgentSetupState(
            imeEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(context, imm),
            imeCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm),
            assistant = isAssistant(context),
            broker = s.tokenBrokerUrl.isNotBlank() && !secrets.brokerAppToken.isNullOrBlank(),
            connector = s.connectorUrl.isNotBlank() && !secrets.connectorRuntimeToken.isNullOrBlank(),
        )
    }

    fun needsWizard(context: Context): Boolean {
        val st = state(context)
        val skip = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
        val skipAssistant = skip.getBoolean(PREF_SKIP_ASSISTANT, false)
        val skipConfig = skip.getBoolean(PREF_SKIP_CONFIG, false)
        if (!st.imeEnabled || !st.imeCurrent) return true
        if (!st.assistant && !skipAssistant) return true
        if (!st.broker && !skipConfig) return true
        return false
    }

    fun skipAssistant(context: Context) {
        context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_SKIP_ASSISTANT, true).apply()
    }

    fun skipConfig(context: Context) {
        context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_SKIP_CONFIG, true).apply()
    }

    fun isAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java) ?: return false
            return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        return false
    }

    fun assistantIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                return rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }
        return if (Build.VERSION.SDK_INT >= 24)
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        else
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    fun apply(context: Context, config: SetupConfig) {
        val c = config.withDefaults()
        val store = AgentSettingsStore.get(context)
        val secrets = SecretStore(context)
        store.update {
            it.copy(
                tokenBrokerUrl = c.brokerUrl.ifBlank { it.tokenBrokerUrl },
                connectorUrl = c.connectorUrl.ifBlank { it.connectorUrl },
            )
        }
        if (c.appToken.isNotBlank()) secrets.brokerAppToken = c.appToken
        if (c.runtimeToken.isNotBlank()) secrets.connectorRuntimeToken = c.runtimeToken
        context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_SKIP_CONFIG, false).apply()
    }

    fun fromIntent(intent: Intent?): SetupConfig {
        if (intent == null) return SetupConfig()
        val fromData = SetupConfig.parse(intent.data?.toString())
        if (!fromData.isEmpty) return fromData
        val extra = intent.getStringExtra(EXTRA_PAYLOAD)
        if (!extra.isNullOrBlank()) return SetupConfig.parse(extra)
        return SetupConfig(
            brokerUrl = intent.getStringExtra("broker").orEmpty(),
            appToken = intent.getStringExtra("app").orEmpty(),
            connectorUrl = intent.getStringExtra("connector").orEmpty(),
            runtimeToken = intent.getStringExtra("runtime").orEmpty(),
        )
    }

    fun openSetupPage(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SetupConfig.SETUP_PAGE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    const val EXTRA_PAYLOAD = "agent_setup_payload"
    private const val PREF_SKIP_ASSISTANT = "agent_setup_skip_assistant"
    private const val PREF_SKIP_CONFIG = "agent_setup_skip_config"
}
