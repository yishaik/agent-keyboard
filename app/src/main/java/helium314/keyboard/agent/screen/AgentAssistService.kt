package helium314.keyboard.agent.screen

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.text.InputType
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream

/**
 * Registers the app as the device's default digital assistant
 * (Settings > Apps > Default apps > Digital assistant app). That role is what lets
 * the keyboard's agent key get the screen's view tree and a screenshot.
 */
class AgentAssistService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); ScreenContextHolder.service = this }
    override fun onShutdown() { ScreenContextHolder.service = null; super.onShutdown() }
    companion object { const val EXTRA_SILENT = "agent_silent" }
}

class AgentAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentAssistSession(this)
}

/** Invisible session: captures assist data, hands it to the keyboard, hides. */
class AgentAssistSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private var text: String? = null
    private var pkg: String? = null
    private var png: ByteArray? = null
    private var gotText = false
    private var gotShot = false
    private var delivered = false
    private var shownAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (Build.VERSION.SDK_INT >= 23) setUiEnabled(false)
        shownAt = SystemClock.uptimeMillis()
        handler.postDelayed({ maybeDeliver() }, AssistDelivery.TEXT_GRACE_MS)
        handler.postDelayed({ maybeDeliver() }, AssistDelivery.GIVE_UP_MS)
        // TODO: when launched by long-press home (not silent), show a full voice UI instead.
    }

    override fun onHide() {
        handler.removeCallbacksAndMessages(null)
        if (!delivered) deliverOnce()
        super.onHide()
    }

    @RequiresApi(29)
    override fun onHandleAssist(state: AssistState) {
        applyStructure(state.assistStructure)
    }

    @Deprecated("Use onHandleAssist(AssistState)")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        applyStructure(structure)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        png = screenshot?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray() }
        gotShot = true
        maybeDeliver()
    }

    private fun applyStructure(structure: AssistStructure?) {
        pkg = structure?.activityComponent?.packageName
        text = structure?.let { flatten(it) } ?: ""
        gotText = true
        maybeDeliver()
    }

    private fun maybeDeliver() {
        val elapsed = if (shownAt == 0L) 0L else SystemClock.uptimeMillis() - shownAt
        if (!AssistDelivery.ready(gotText, gotShot, elapsed)) return
        deliverOnce()
    }

    private fun deliverOnce() {
        if (delivered) return
        delivered = true
        handler.removeCallbacksAndMessages(null)
        ScreenContextHolder.deliver(ScreenSnapshot(pkg, text.orEmpty(), png))
        hide()
    }

    private fun flatten(s: AssistStructure): String = buildString {
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, this)
    }

    private fun walk(n: AssistStructure.ViewNode, out: StringBuilder) {
        if (!isPassword(n)) {
            n.text?.takeIf { it.isNotBlank() }?.let { out.appendLine(it) }
            n.contentDescription?.takeIf { it.isNotBlank() && n.text.isNullOrBlank() }?.let { out.appendLine("[$it]") }
        }
        for (i in 0 until n.childCount) walk(n.getChildAt(i), out)
    }

    private fun isPassword(n: AssistStructure.ViewNode): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val v = n.inputType and InputType.TYPE_MASK_VARIATION
        return v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}

/** Required by the assistant role; dictation goes through the harness, so this is a stub. */
class AgentRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { /* TODO */ }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
