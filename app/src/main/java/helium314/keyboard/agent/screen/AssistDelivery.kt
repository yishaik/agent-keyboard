package helium314.keyboard.agent.screen

/**
 * When the invisible assist session is ready to hand data back to the keyboard.
 *
 * Android does not always call [android.service.voice.VoiceInteractionSession.onHandleScreenshot]
 * (FLAG_SECURE, OEM skips, screenshot disabled). Waiting for both text and screenshot
 * used to hang until the keyboard timed out with nothing.
 */
object AssistDelivery {
    /** After this, text or a screenshot alone is enough. */
    const val TEXT_GRACE_MS = 400L
    /** After this, deliver whatever we have, including empty. */
    const val GIVE_UP_MS = 1200L

    fun ready(gotText: Boolean, gotShot: Boolean, elapsedMs: Long): Boolean {
        if (gotText && gotShot) return true
        if ((gotText || gotShot) && elapsedMs >= TEXT_GRACE_MS) return true
        if (elapsedMs >= GIVE_UP_MS) return true
        return false
    }
}
