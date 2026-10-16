package helium314.keyboard.agent.harness

import helium314.keyboard.agent.settings.AgentSettings
import helium314.keyboard.agent.settings.ReplyLanguage

/** Builds the system prompt from settings + the current field. Shared by voice and text. */
object PromptBuilder {
    fun build(
        s: AgentSettings,
        packageName: String?,
        fieldLanguage: String?,
        fieldHint: String?,
        screenText: String? = null,
    ): String = buildString {
        appendLine("You are an agent built into the user's Android keyboard. You work inside whatever app is open.")
        appendLine("Be brief. When the user wants text written, produce only the text to insert, then call insert_text or replace_text.")
        if (screenText.isNullOrBlank()) {
            appendLine("Call read_screen or describe_screen when you need what is on screen.")
        } else {
            appendLine("Visible screen text is attached below. Use it as context. Call describe_screen if you need a visual look, or read_screen if the screen may have changed.")
        }
        appendLine("Never act on password fields. Ask before anything that sends, deletes, or pays.")
        when (s.replyLanguage) {
            ReplyLanguage.MATCH_FIELD -> appendLine("Write in the language of the conversation in the current field (${fieldLanguage ?: "detect it"}), not the language of the request.")
            ReplyLanguage.MATCH_REQUEST -> appendLine("Reply in the language the user spoke or typed.")
            ReplyLanguage.FIXED -> appendLine("Always reply in ${s.fixedReplyLanguage}.")
        }
        appendLine("Hebrew and English are both first-class; mixed Hebrew/English text is normal.")
        packageName?.let { appendLine("Current app: $it.") }
        fieldHint?.let { appendLine("Current field hint: $it.") }
        val style = packageName?.let { s.perAppStyle[it] } ?: s.writingStyle
        appendLine("Writing style: $style.")
        if (s.aboutMe.isNotBlank()) appendLine("About the user: ${s.aboutMe}")
        if (s.standingInstructions.isNotBlank()) appendLine("Standing instructions: ${s.standingInstructions}")
        if (!screenText.isNullOrBlank()) {
            appendLine("--- screen ---")
            appendLine(screenText)
            appendLine("--- end screen ---")
        }
    }
}
