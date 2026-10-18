package helium314.keyboard.agent

import helium314.keyboard.agent.settings.SetupConfig
import helium314.keyboard.agent.settings.SetupGuard
import helium314.keyboard.agent.settings.SetupGuard.Field
import helium314.keyboard.agent.settings.SetupGuard.Problem
import helium314.keyboard.agent.settings.SetupGuard.Review
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SetupGuardTest {
    private val fresh = SetupGuard.Current(SetupConfig.DEFAULT_BROKER_URL, false, SetupConfig.DEFAULT_CONNECTOR_URL, false)
    private val configured = fresh.copy(hasAppToken = true, hasRuntimeToken = true)

    private fun link(broker: String, connector: String = SetupConfig.DEFAULT_CONNECTOR_URL) =
        SetupConfig.parse("agentkeyboard://setup?broker=$broker&app=tok123&connector=$connector&runtime=oct_abc")

    @Test fun `known hosts are accepted and tokens listed without values`() {
        val r = SetupGuard.review(link(SetupConfig.DEFAULT_BROKER_URL), configured)
        assertIs<Review.Ok>(r)
        assertEquals(listOf(Field.APP_TOKEN, Field.RUNTIME_TOKEN), r.changes.map { it.field })
        assertTrue(r.changes.all { it.replacesExisting })
        assertFalse(r.changes.toString().contains("tok123"))
        assertFalse(r.changes.toString().contains("oct_abc"))
    }

    @Test fun `unknown broker host is rejected`() {
        val r = SetupGuard.review(link("https://evil.example"), configured)
        assertEquals(Review.Rejected(Field.BROKER_URL, Problem.HOST_NOT_ALLOWED, "evil.example"), r)
    }

    @Test fun `lookalike hosts are rejected`() {
        for (u in listOf(
            "https://agent-keyboard-token-broker.yishai-k.workers.dev.evil.example",
            "https://evil-agent-keyboard-token-broker.yishai-k.workers.dev",
            "https://other.yishai-k.workers.dev",
        )) assertIs<Review.Rejected>(SetupGuard.review(link(u), fresh), u)
        assertIs<Review.Rejected>(SetupGuard.review(link(SetupConfig.DEFAULT_BROKER_URL, "https://connect.yishaik.com.evil.example"), fresh))
    }

    @Test fun `http is rejected even for a known host`() {
        val r = SetupGuard.review(link("http://agent-keyboard-token-broker.yishai-k.workers.dev"), fresh)
        assertEquals(Problem.NOT_HTTPS, (r as Review.Rejected).problem)
    }

    @Test fun `userinfo and odd ports are rejected`() {
        assertIs<Review.Rejected>(SetupGuard.review(link("https://x@agent-keyboard-token-broker.yishai-k.workers.dev"), fresh))
        assertIs<Review.Rejected>(SetupGuard.review(link("https://agent-keyboard-token-broker.yishai-k.workers.dev:8443"), fresh))
    }

    @Test fun `host case and trailing dot are normalised`() {
        assertIs<Review.Ok>(SetupGuard.review(link("https://Agent-Keyboard-Token-Broker.yishai-k.workers.dev./"), fresh))
    }

    @Test fun `token-only link fills default hosts`() {
        val r = SetupGuard.review(SetupConfig.parse("""{"app":"tok","runtime":"rt"}"""), fresh)
        assertIs<Review.Ok>(r)
        assertEquals(SetupConfig.DEFAULT_BROKER_URL, r.config.brokerUrl)
        assertTrue(r.changes.none { it.replacesExisting })
    }

    @Test fun `switching away from a custom broker is flagged`() {
        val cur = configured.copy(brokerUrl = "https://connect.yishaik.com")
        val r = SetupGuard.review(link(SetupConfig.DEFAULT_BROKER_URL), cur) as Review.Ok
        assertTrue(r.changes.first { it.field == Field.BROKER_URL }.hostChanges)
    }

    @Test fun `control characters in tokens are rejected`() {
        val r = SetupGuard.review(SetupConfig(appToken = "a\nb"), fresh)
        assertEquals(Problem.BAD_TOKEN, (r as Review.Rejected).problem)
    }

    @Test fun `empty payload does nothing`() {
        assertEquals(Review.Empty, SetupGuard.review(SetupConfig.parse("hello"), fresh))
    }
}
