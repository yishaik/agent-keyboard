package helium314.keyboard.agent.settings

import kotlinx.serialization.Serializable

/** Which backend serves a modality. Both speak the OpenAI-compatible wire format. */
enum class Provider { GROK, OPENAI }

enum class MicTapAction { AGENT, DICTATION }
enum class TurnTaking { PUSH_TO_TALK, VAD }
enum class SpokenReply { ALWAYS, WHEN_NOTHING_TO_INSERT, NEVER }
enum class ScreenMode { OFF, ON_DEMAND, ALWAYS_ATTACH }
enum class InsertMode { PREVIEW, AUTO }
enum class ToolPolicy { ALLOW, ASK, BLOCK }
enum class ReplyLanguage { MATCH_FIELD, MATCH_REQUEST, FIXED }

/**
 * Everything the "Agent" and "Connections" settings categories control.
 * Persisted as JSON in the keyboard's SharedPreferences (key [PREF_KEY]).
 * Secrets (API keys, runtime token) are NOT stored here; see [SecretStore].
 */
@Serializable
data class AgentSettings(
    // 1. Providers
    val voiceProvider: Provider = Provider.GROK,
    val textProvider: Provider = Provider.GROK,
    val hebrewVoiceProvider: Provider? = null,   // per-language override, null = use voiceProvider
    val englishVoiceProvider: Provider? = null,
    val autoFallback: Boolean = true,
    val grokVoiceModel: String = "grok-voice-3.0",
    val grokTextModel: String = "grok-4.7",
    val openAiVoiceModel: String = "gpt-realtime",
    val openAiTextModel: String = "gpt-5",
    val voiceName: String = "",
    val speechSpeed: Float = 1.0f,
    val tokenBrokerUrl: String = "",               // your Cloudflare token Worker

    // 2. Activation
    val micTap: MicTapAction = MicTapAction.AGENT,
    val turnTaking: TurnTaking = TurnTaking.VAD,
    val vadSensitivity: Float = 0.5f,
    val bargeIn: Boolean = true,
    val spokenReply: SpokenReply = SpokenReply.WHEN_NOTHING_TO_INSERT,

    // 3. Screen reading
    val screenMode: ScreenMode = ScreenMode.ON_DEMAND,
    val allowScreenshot: Boolean = true,
    val blockedPackages: Set<String> = emptySet(), // banks, password managers, health
    // password fields are always blocked; intentionally not a setting

    // 4. Actions & permissions
    val insertMode: InsertMode = InsertMode.PREVIEW,
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(), // tool name -> policy

    // 5. Knowledge & style
    val aboutMe: String = "",
    val standingInstructions: String = "",
    val writingStyle: String = "regular",           // short | regular | formal
    val perAppStyle: Map<String, String> = emptyMap(),
    val memoryEnabled: Boolean = true,

    // 6. Language
    val replyLanguage: ReplyLanguage = ReplyLanguage.MATCH_FIELD,
    val fixedReplyLanguage: String = "he",

    // 7. Privacy & cost
    val historyRetentionDays: Int = 30,
    val monthlyCapUsd: Float = 0f,                  // 0 = no cap

    // 8. Appearance
    val agentGlow: Float = 0.6f,

    // Connections (OpenConnector, self-hosted on Cloudflare)
    val connectorUrl: String = "",                  // https://open-connector.<you>.workers.dev
) {
    fun voiceProviderFor(lang: String): Provider = when {
        lang.startsWith("he") || lang.startsWith("iw") -> hebrewVoiceProvider ?: voiceProvider
        lang.startsWith("en") -> englishVoiceProvider ?: voiceProvider
        else -> voiceProvider
    }

    companion object { const val PREF_KEY = "agent_settings_json" }
}
