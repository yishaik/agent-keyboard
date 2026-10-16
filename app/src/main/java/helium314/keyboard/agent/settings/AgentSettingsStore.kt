package helium314.keyboard.agent.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** Loads/saves [AgentSettings] as one JSON blob next to HeliBoard's own prefs. */
class AgentSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AgentSettings> = _state

    private fun load(): AgentSettings = prefs.getString(AgentSettings.PREF_KEY, null)
        ?.let { runCatching { json.decodeFromString(AgentSettings.serializer(), it) }.getOrNull() } ?: AgentSettings()

    fun update(block: (AgentSettings) -> AgentSettings) {
        val next = block(_state.value)
        prefs.edit().putString(AgentSettings.PREF_KEY, json.encodeToString(AgentSettings.serializer(), next)).apply()
        _state.value = next
    }

    companion object {
        @Volatile private var instance: AgentSettingsStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: AgentSettingsStore(context.applicationContext).also { instance = it }
        }
    }
}
