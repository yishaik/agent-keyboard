package helium314.keyboard.agent.harness

import kotlinx.serialization.json.JsonObject

/**
 * A provider-neutral tool. The same definition is sent to Grok and OpenAI as a
 * function tool, and executed on the phone.
 */
interface Tool {
    val name: String
    val description: String
    /** JSON Schema for the arguments. */
    val parameters: JsonObject
    /** Write tools (insert, send, delete, run a connector write) default to ASK. */
    val writes: Boolean
    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

data class ToolResult(val output: String, val isError: Boolean = false)

/** What a tool can reach while it runs. Filled by the IME / assist session. */
interface ToolContext {
    val packageName: String?
    val fieldLanguage: String?
    /** Shows a confirmation card in the suggestion strip; returns the user's choice. */
    suspend fun confirm(title: String, detail: String): Boolean
    /** Shows a draft in the suggestion strip; returns true if the user tapped Insert. */
    suspend fun preview(text: String): Boolean
}
