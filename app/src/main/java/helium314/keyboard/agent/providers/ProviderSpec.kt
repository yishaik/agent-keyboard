package helium314.keyboard.agent.providers

import helium314.keyboard.agent.settings.AgentSettings
import helium314.keyboard.agent.settings.Provider

/** Endpoint differences between Grok and OpenAI. Everything else is shared. */
data class ProviderSpec(
    val provider: Provider,
    val realtimeUrl: String,
    val chatUrl: String,
    val voiceModel: String,
    val textModel: String,
    val transcriptionModel: String?, // null = provider transcribes input on its own
) {
    companion object {
        fun of(p: Provider, s: AgentSettings) = when (p) {
            Provider.GROK -> ProviderSpec(
                p,
                realtimeUrl = "wss://api.x.ai/v1/realtime?model=${s.grokVoiceModel}",
                chatUrl = "https://api.x.ai/v1/chat/completions",
                voiceModel = s.grokVoiceModel,
                textModel = s.grokTextModel,
                transcriptionModel = "grok-transcribe", // gives us the user's words for the strip and failover replay
            )
            Provider.OPENAI -> ProviderSpec(
                p,
                realtimeUrl = "wss://api.openai.com/v1/realtime?model=${s.openAiVoiceModel}",
                chatUrl = "https://api.openai.com/v1/chat/completions",
                voiceModel = s.openAiVoiceModel,
                textModel = s.openAiTextModel,
                transcriptionModel = "gpt-4o-mini-transcribe",
            )
        }
    }
}
