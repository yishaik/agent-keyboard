package helium314.keyboard.agent.ime

/** One visible turn in the agent panel. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * UI transcript of the current agent session. Separate from [helium314.keyboard.agent.harness.AgentSession]
 * history (that one is for the model). This one is what the user reads.
 */
class ConversationLog(private val maxTurns: Int = 30) {
    private val turns = ArrayList<ChatTurn>()

    fun snapshot(): List<ChatTurn> = turns.toList()

    fun addUser(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val last = turns.lastOrNull()
        if (last != null && !last.fromUser && last.text.isEmpty()) {
            turns.add(turns.lastIndex, ChatTurn(true, t))
        } else {
            turns += ChatTurn(true, t)
        }
        trim()
    }

    /** Voice user transcripts arrive complete; replace the last user line if we already have one. */
    fun replaceLastUser(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (turns.lastOrNull()?.fromUser == true) turns[turns.lastIndex] = ChatTurn(true, t)
        else addUser(t)
    }

    fun startAssistant() {
        val last = turns.lastOrNull()
        if (last != null && !last.fromUser && last.text.isEmpty()) return
        turns += ChatTurn(false, "")
        trim()
    }

    fun appendAssistant(delta: String) {
        if (delta.isEmpty()) return
        val last = turns.lastOrNull()
        if (last == null || last.fromUser) {
            turns += ChatTurn(false, delta)
            trim()
        } else {
            turns[turns.lastIndex] = last.copy(text = last.text + delta)
        }
    }

    fun setAssistant(text: String) {
        val last = turns.lastOrNull()
        if (last == null || last.fromUser) {
            turns += ChatTurn(false, text)
            trim()
        } else {
            turns[turns.lastIndex] = last.copy(text = text)
        }
    }

    fun clear() { turns.clear() }

    fun format(you: String, agent: String): String = turns
        .filter { it.text.isNotBlank() }
        .joinToString("\n") { "${if (it.fromUser) you else agent}: ${it.text}" }

    private fun trim() { while (turns.size > maxTurns) turns.removeAt(0) }
}
