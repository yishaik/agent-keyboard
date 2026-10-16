package helium314.keyboard.agent.screen

import android.service.voice.VoiceInteractionSession
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class ScreenSnapshot(val packageName: String?, val text: String, val screenshotPng: ByteArray?)

/**
 * Bridge between the keyboard (which can't see the screen) and the assist session
 * (which can). The keyboard's agent key calls [request]; the active
 * [AgentAssistService] opens an invisible session with assist data + screenshot.
 */
object ScreenContextHolder {
    @Volatile internal var service: AgentAssistService? = null
    @Volatile private var pending: CompletableDeferred<ScreenSnapshot?>? = null
    @Volatile var blockedPackages: Set<String> = emptySet()
    private val gate = Mutex()

    /** True once [AgentAssistService.onReady] has run (app is the default assistant). */
    val isAssistantReady: Boolean get() = service != null

    suspend fun request(timeoutMs: Long = 1600, screenshot: Boolean = true): ScreenSnapshot? = gate.withLock {
        val svc = service ?: return@withLock null // app is not the default assistant
        val d = CompletableDeferred<ScreenSnapshot?>().also { pending = it }
        try {
            var flags = VoiceInteractionSession.SHOW_WITH_ASSIST
            if (screenshot) flags = flags or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
            svc.showSession(Bundle().apply { putBoolean(AgentAssistService.EXTRA_SILENT, true) }, flags)
            withTimeoutOrNull(timeoutMs) { d.await() }
        } catch (_: Throwable) {
            null
        } finally {
            if (pending === d) pending = null
        }
    }

    internal fun deliver(s: ScreenSnapshot?) {
        val blocked = s?.packageName != null && s.packageName in blockedPackages
        pending?.complete(if (blocked) null else s)
        pending = null
    }
}
