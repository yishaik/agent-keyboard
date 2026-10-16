package helium314.keyboard.agent.screen

import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.CompletableDeferred
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

    suspend fun request(timeoutMs: Long = 2500): ScreenSnapshot? {
        val svc = service ?: return null // app is not the default assistant
        val d = CompletableDeferred<ScreenSnapshot?>().also { pending = it }
        val flags = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        svc.showSession(Bundle().apply { putBoolean(AgentAssistService.EXTRA_SILENT, true) }, flags)
        return withTimeoutOrNull(timeoutMs) { d.await() }
    }

    internal fun deliver(s: ScreenSnapshot?) {
        val blocked = s?.packageName != null && s.packageName in blockedPackages
        pending?.complete(if (blocked) null else s)
        pending = null
    }
}
