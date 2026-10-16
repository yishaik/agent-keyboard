package helium314.keyboard.agent.harness

import helium314.keyboard.agent.settings.AgentSettings
import helium314.keyboard.agent.settings.ToolPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Holds every tool and enforces the user's per-tool policy before running one. */
class ToolRegistry(private val settings: () -> AgentSettings) {
    private val tools = linkedMapOf<String, Tool>()

    fun register(vararg t: Tool) = t.forEach { tools[it.name] = it }

    fun policyOf(tool: Tool): ToolPolicy =
        settings().toolPolicies[tool.name] ?: if (tool.writes) ToolPolicy.ASK else ToolPolicy.ALLOW

    /** Tools exposed to the model: everything not blocked. */
    fun visible(): List<Tool> = tools.values.filter { policyOf(it) != ToolPolicy.BLOCK }

    /** Function-tool definitions in the shape both providers accept. */
    fun definitions(): List<JsonObject> = visible().map {
        buildJsonObject {
            put("type", "function")
            put("name", it.name)
            put("description", it.description)
            put("parameters", it.parameters)
        }
    }

    suspend fun call(name: String, args: JsonObject, ctx: ToolContext): ToolResult {
        val tool = tools[name] ?: return ToolResult("Unknown tool $name", isError = true)
        return when (policyOf(tool)) {
            ToolPolicy.BLOCK -> ToolResult("Blocked by user settings", isError = true)
            ToolPolicy.ASK -> if (ctx.confirm(tool.name, args.toString())) tool.run(args, ctx)
                              else ToolResult("User declined", isError = true)
            ToolPolicy.ALLOW -> tool.run(args, ctx)
        }
    }
}
