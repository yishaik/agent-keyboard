package helium314.keyboard.agent.ime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import helium314.keyboard.latin.R

/**
 * An input method can't ask for runtime permissions itself, so the voice agent opens
 * this transparent activity once to get RECORD_AUDIO, then closes.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 23) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.agent_mic_title)
            .setMessage(R.string.agent_mic_body)
            .setPositiveButton(R.string.agent_grant) { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
            .setNegativeButton(R.string.agent_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
