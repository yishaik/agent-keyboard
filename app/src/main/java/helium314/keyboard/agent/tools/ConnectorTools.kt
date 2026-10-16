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
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = runCatching {
            client.searchActions(args["query"]?.jsonPrimitive?.content.orEmpty()).toString().take(6000)
        }.fold({ ToolResult(it) }, { ToolResult(it.message ?: "failed", true) })
    }

    val listConnections = object : Tool {
        override val name = "list_connections"
        override val description = "List the user's connected accounts (e.g. two Gmail accounts). Pass a connectionName to run_action to pick one."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("service") { put("type", "string"); put("description", "Optional provider id: gmail, github, notion...") } }
        }
        override val writes = false
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = runCatching {
            client.listConnections(args["service"]?.jsonPrimitive?.content).toString().take(4000)
        }.fold({ ToolResult(it) }, { ToolResult(it.message ?: "failed", true) })
    }

    val runAction = object : Tool {
        override val name = "run_action"
        override val description = "Run a connected-app action by id with its input object."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action_id") { put("type", "string") }
                putJsonObject("input") { put("type", "object") }
                putJsonObject("connection") { put("type", "string"); put("description", "connectionName from list_connections; omit for the default account") }
            }
            putJsonArray("required") { add("action_id"); add("input") }
        }
        override val writes = false // per-action check below
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args["action_id"]!!.jsonPrimitive.content
            val input = args["input"]?.jsonObject ?: JsonObject(emptyMap())
            if (askFor(id) && !ctx.confirm(id, input.toString())) return ToolResult("User declined", true)
            return runCatching { client.runAction(id, input, args["connection"]?.jsonPrimitive?.content) }
                .fold({ ToolResult(it.toString()) }, { ToolResult(it.message ?: "failed", true) })
        }
    }

    fun all(): Array<Tool> = arrayOf(findAction, listConnections, runAction)
}
