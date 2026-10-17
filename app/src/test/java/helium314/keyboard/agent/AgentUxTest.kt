package helium314.keyboard.agent

import helium314.keyboard.agent.harness.PromptBuilder
import helium314.keyboard.agent.ime.ConversationLog
import helium314.keyboard.agent.screen.AssistDelivery
import helium314.keyboard.agent.settings.AgentSettings
import helium314.keyboard.agent.settings.SetupConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentUxTest {
    @Test fun `conversation keeps user and assistant turns`() {
        val log = ConversationLog()
        log.addUser("what's on screen?")
        log.appendAssistant("A chat with Dana.")
        log.addUser("reply yes")
        log.appendAssistant("Sure.")
        assertEquals(
            "You: what's on screen?\nAgent: A chat with Dana.\nYou: reply yes\nAgent: Sure.",
            log.format("You", "Agent"),
        )
    }

    @Test fun `assistant deltas merge into one turn`() {
        val log = ConversationLog()
        log.addUser("hi")
        log.startAssistant()
        log.appendAssistant("Hel")
        log.appendAssistant("lo")
        assertEquals("You: hi\nAgent: Hello", log.format("You", "Agent"))
        assertEquals(2, log.snapshot().size)
    }

    @Test fun `replaceLastUser updates the last user line`() {
        val log = ConversationLog()
        log.addUser("he")
        log.replaceLastUser("hello there")
        assertEquals("You: hello there", log.format("You", "Agent"))
        assertEquals(1, log.snapshot().size)
    }

    @Test fun `blank assistant line is hidden until text arrives`() {
        val log = ConversationLog()
        log.addUser("go")
        log.startAssistant()
        assertEquals("You: go", log.format("You", "Agent"))
        log.appendAssistant("ok")
        assertEquals("You: go\nAgent: ok", log.format("You", "Agent"))
    }

    @Test fun `trim drops oldest turns`() {
        val log = ConversationLog(maxTurns = 4)
        repeat(6) { i ->
            log.addUser("u$i")
            log.appendAssistant("a$i")
        }
        assertEquals(4, log.snapshot().size)
        assertEquals("You: u4", log.format("You", "Agent").lines().first())
    }

    @Test fun `assist delivers as soon as both arrive`() {
        assertTrue(AssistDelivery.ready(gotText = true, gotShot = true, elapsedMs = 0))
    }

    @Test fun `assist waits briefly for the missing half`() {
        assertFalse(AssistDelivery.ready(gotText = true, gotShot = false, elapsedMs = 0))
        assertFalse(AssistDelivery.ready(gotText = false, gotShot = true, elapsedMs = 0))
        assertTrue(AssistDelivery.ready(gotText = true, gotShot = false, elapsedMs = AssistDelivery.TEXT_GRACE_MS))
        assertTrue(AssistDelivery.ready(gotText = false, gotShot = true, elapsedMs = AssistDelivery.TEXT_GRACE_MS))
    }

    @Test fun `assist gives up so a missing screenshot cannot hang`() {
        assertFalse(AssistDelivery.ready(gotText = false, gotShot = false, elapsedMs = AssistDelivery.GIVE_UP_MS - 1))
        assertTrue(AssistDelivery.ready(gotText = false, gotShot = false, elapsedMs = AssistDelivery.GIVE_UP_MS))
    }

    @Test fun `prompt attaches screen text when present`() {
        val prompt = PromptBuilder.build(
            AgentSettings(),
            "com.whatsapp",
            "he",
            "message",
            "Hello Dana\nDinner at 8?",
        )
        assertTrue(prompt.contains("Hello Dana"))
        assertTrue(prompt.contains("Dinner at 8?"))
        assertTrue(prompt.contains("com.whatsapp"))
        assertTrue(prompt.contains("--- screen ---"))
        assertFalse(prompt.contains("Call read_screen or describe_screen when you need what is on screen."))
    }

    @Test fun `prompt asks for a tool when no screen text is attached`() {
        val prompt = PromptBuilder.build(AgentSettings(), "com.whatsapp", "en", null, null)
        assertTrue(prompt.contains("Call read_screen or describe_screen when you need what is on screen."))
        assertFalse(prompt.contains("--- screen ---"))
    }

    @Test fun `setup query keeps plus signs in the app token`() {
        val token = "jL+crQBs/token="
        val raw = "agentkeyboard://setup?broker=https%3A%2F%2Fbroker.example%2F&app=jL%2BcrQBs%2Ftoken%3D&connector=https%3A%2F%2Foc.example%2F&runtime=oct_abc"
        val c = SetupConfig.parse(raw)
        assertEquals("https://broker.example/", c.brokerUrl)
        assertEquals(token, c.appToken)
        assertEquals("https://oc.example/", c.connectorUrl)
        assertEquals("oct_abc", c.runtimeToken)
        assertTrue(c.hasBroker)
        assertTrue(c.hasConnector)
    }

    @Test fun `setup json and defaults`() {
        val c = SetupConfig.parse("""{"app":"tok","runtime":"rt"}""").withDefaults()
        assertEquals(SetupConfig.DEFAULT_BROKER_URL, c.brokerUrl)
        assertEquals("tok", c.appToken)
        assertEquals(SetupConfig.DEFAULT_CONNECTOR_URL, c.connectorUrl)
        assertEquals("rt", c.runtimeToken)
    }

    @Test fun `empty paste is empty config`() {
        assertTrue(SetupConfig.parse("").isEmpty)
        assertTrue(SetupConfig.parse("hello").isEmpty)
    }
}
