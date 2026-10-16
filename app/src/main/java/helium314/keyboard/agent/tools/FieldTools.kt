package helium314.keyboard.agent.tools

import android.view.inputmethod.InputConnection
import helium314.keyboard.agent.harness.Tool
import helium314.keyboard.agent.harness.ToolContext
import helium314.keyboard.agent.harness.ToolResult
import kotlinx.serialization.json.*

/** Tools that read and write the focused field through the IME's InputConnection. */
class FieldTools(private val ic: () -> InputConnection?) {

    val getField = object : Tool {
        override val name = "get_field"
        override val description = "Read the text in the focused field around the cursor, and the selection."
        override val parameters = schema()
        override val writes = false
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val c = ic() ?: return ToolResult("No field", true)
            val before = c.getTextBeforeCursor(2000, 0) ?: ""
            val sel = c.getSelectedText(0) ?: ""
            val after = c.getTextAfterCursor(2000, 0) ?: ""
            return ToolResult(buildJsonObject {
                put("before", before.toString()); put("selected", sel.toString()); put("after", after.toString())
                put("language", ctx.fieldLanguage ?: "unknown")
            }.toString())
        }
    }

    val insertText = object : Tool {
        override val name = "insert_text"
        override val description = "Insert text at the cursor in the focused field. The user sees a preview first."
        override val parameters = schema("text" to "string")
        override val writes = false // gated by preview, not by the ASK dialog
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val text = args["text"]?.jsonPrimitive?.content ?: return ToolResult("Missing text", true)
            if (!ctx.preview(text)) return ToolResult("User did not insert")
            ic()?.commitText(Bidi.wrapIfMixed(text), 1)
            return ToolResult("Inserted")
        }
    }

    val replaceText = object : Tool {
        override val name = "replace_text"
        override val description = "Replace the whole field (or the selection, if any) with new text. Preview first."
        override val parameters = schema("text" to "string")
        override val writes = false
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val text = args["text"]?.jsonPrimitive?.content ?: return ToolResult("Missing text", true)
            if (!ctx.preview(text)) return ToolResult("User did not replace")
            val c = ic() ?: return ToolResult("No field", true)
            if (c.getSelectedText(0).isNullOrEmpty()) c.performContextMenuAction(android.R.id.selectAll)
            c.commitText(Bidi.wrapIfMixed(text), 1)
            return ToolResult("Replaced")
        }
    }

    fun all(): Array<Tool> = arrayOf(getField, insertText, replaceText)
}

internal fun schema(vararg props: Pair<String, String>) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (k, t) -> putJsonObject(k) { put("type", t) } } }
    putJsonArray("required") { props.forEach { add(it.first) } }
}

/** Minimal bidi helper: Hebrew paragraphs that start with a Latin word get an RLM. */
object Bidi {
    private const val RLM = '\u200F'
    fun wrapIfMixed(s: String): String {
        val firstStrong = s.firstOrNull { Character.getDirectionality(it).let { d ->
            d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT } }
        val hasHebrew = s.any { it in '\u0590'..'\u05FF' }
        return if (hasHebrew && firstStrong != null && Character.getDirectionality(firstStrong) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) "$RLM$s" else s
    }
}
