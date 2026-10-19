package helium314.keyboard.agent.tools

import helium314.keyboard.agent.harness.UntrustedContent

import helium314.keyboard.agent.harness.Tool
import helium314.keyboard.agent.harness.ToolContext
import helium314.keyboard.agent.harness.ToolResult
import helium314.keyboard.agent.screen.ScreenContextHolder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Screen context comes from the assist session (AssistStructure + screenshot). */
class ScreenTools(private val describeImage: suspend (ByteArray, String) -> String) {

    val readScreen = object : Tool {
        override val name = "read_screen"
        override val description = "Get the visible text on the current screen (view tree), excluding password fields."
        override val parameters = schema()
        override val writes = false
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val snap = ScreenContextHolder.request(screenshot = false) ?: return ToolResult("Screen not available: set Agent Keyboard as the default digital assistant app, or this app is blocked in settings", true)
            return ToolResult(UntrustedContent.wrap("screen", snap.text))
        }
    }

    val describeScreen = object : Tool {
        override val name = "describe_screen"
        override val description = "Look at a screenshot of the current screen and answer a question about it."
        override val parameters = schema("question" to "string")
        override val writes = false
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val snap = ScreenContextHolder.request(screenshot = true) ?: return ToolResult("Screen not available", true)
            val png = snap.screenshotPng ?: return ToolResult("No screenshot (FLAG_SECURE or disabled)", true)
            val q = args["question"]?.jsonPrimitive?.content ?: "Describe the screen."
            return ToolResult(UntrustedContent.wrap("screen_description", describeImage(png, q)))
        }
    }

    fun all(): Array<Tool> = arrayOf(readScreen, describeScreen)
}
