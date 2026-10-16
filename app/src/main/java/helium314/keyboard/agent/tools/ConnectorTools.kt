package helium314.keyboard.agent.tools

import helium314.keyboard.agent.connectors.OpenConnectorClient
import helium314.keyboard.agent.harness.Tool
import helium314.keyboard.agent.harness.ToolContext
import helium314.keyboard.agent.harness.ToolResult
import kotlinx.serialization.json.*

/**
 * Two tools instead of thousands: search the user's connected actions, then run one.
 * Write actions are confirmed in the suggestion strip unless the user allowed them.
 */
class ConnectorTools(private val client: OpenConnectorClient, private val askFor: (String) -> Boolean) {

    val findAction = object : Tool {
        override val name = "find_action"
        override val description = "Search actions available in the user's connected apps (Gmail, Calendar, GitHub, Notion...). Returns action ids and input schemas."
        override val parameters = schema("query" to "string")
        override val writes = false
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
            ToolResult(client.searchActions(args["query"]!!.jsonPrimitive.content).toString())
    }

    val runAction = object : Tool {
        override val name = "run_action"
        override val description = "Run a connected-app action by id with its input object."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action_id") { put("type", "string") }
                putJsonObject("input") { put("type", "object") }
                putJsonObject("connection") { put("type", "string"); put("description", "Connection alias, e.g. work-gmail") }
            }
            putJsonArray("required") { add("action_id"); add("input") }
        }
        override val writes = false // per-action check below
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args["action_id"]!!.jsonPrimitive.content
            val input = args["input"]?.jsonObject ?: JsonObject(emptyMap())
            if (askFor(id) && !ctx.confirm("Run $id?", input.toString())) return ToolResult("User declined", true)
            return runCatching { client.runAction(id, input, args["connection"]?.jsonPrimitive?.content) }
                .fold({ ToolResult(it.toString()) }, { ToolResult(it.message ?: "failed", true) })
        }
    }

    fun all(): Array<Tool> = arrayOf(findAction, runAction)
}
