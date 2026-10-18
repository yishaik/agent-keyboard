package helium314.keyboard.agent.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SettingsActivity

/**
 * Receives agentkeyboard://setup?... (from the setup page, or anything else on the phone) and
 * asks before writing anything. Nothing is applied unless the user taps Apply:
 *  - hosts outside [SetupGuard.ALLOWED_HOSTS] and non-https URLs are rejected with no Apply button;
 *  - the dialog lists each field that will be replaced and the host it points to;
 *  - Cancel is the default (focused, back button, tap outside), and Apply stays disabled for a
 *    moment and ignores touches through overlays, so a page cannot tap it through for the user.
 * Token values are never shown or logged.
 */
class SetupApplyActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = AgentSetup.fromIntent(intent)
        when (val r = SetupGuard.review(raw, AgentSetup.current(this))) {
            SetupGuard.Review.Empty -> finish()
            is SetupGuard.Review.Rejected -> showRejected(r)
            is SetupGuard.Review.Ok -> showConfirm(r)
        }
    }

    private fun showRejected(r: SetupGuard.Review.Rejected) {
        dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle(R.string.agent_setup_rejected_title)
            .setMessage(rejectedMessage(this, r))
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun showConfirm(r: SetupGuard.Review.Ok) {
        val d = AlertDialog.Builder(this, dialogTheme())
            .setTitle(R.string.agent_setup_confirm_title)
            .setMessage(confirmMessage(this, r))
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .setPositiveButton(R.string.agent_setup_confirm_apply) { _, _ ->
                AgentSetup.apply(this, r.config)
                Toast.makeText(this, R.string.agent_setup_applied, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            .setCancelable(true)
            .setOnDismissListener { finish() }
            .create()
        d.setCanceledOnTouchOutside(true)
        d.setOnShowListener {
            d.window?.decorView?.filterTouchesWhenObscured = true
            val apply: Button = d.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancel: Button = d.getButton(AlertDialog.BUTTON_NEGATIVE)
            apply.filterTouchesWhenObscured = true
            apply.isEnabled = false
            cancel.isFocusableInTouchMode = true
            cancel.requestFocus()
            main.postDelayed({ apply.isEnabled = true }, APPLY_DELAY_MS)
        }
        dialog = d
        d.show()
    }

    private fun dialogTheme(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        const val APPLY_DELAY_MS = 1200L

        fun confirmMessage(ctx: Context, r: SetupGuard.Review.Ok): String = buildString {
            append(ctx.getString(R.string.agent_setup_confirm_lede)).append("\n\n")
            for (c in r.changes) {
                val label = when (c.field) {
                    SetupGuard.Field.BROKER_URL -> R.string.agent_setup_field_broker
                    SetupGuard.Field.APP_TOKEN -> R.string.agent_setup_field_app_token
                    SetupGuard.Field.CONNECTOR_URL -> R.string.agent_setup_field_connector
                    SetupGuard.Field.RUNTIME_TOKEN -> R.string.agent_setup_field_runtime_token
                }
                val verb = if (c.replacesExisting) R.string.agent_setup_will_replace else R.string.agent_setup_will_set
                append("• ").append(ctx.getString(label)).append(": ").append(ctx.getString(verb))
                c.host?.let { append("\n   ").append(it) }
                if (c.hostChanges) append("\n   ").append(ctx.getString(R.string.agent_setup_host_changes))
                append('\n')
            }
            append('\n').append(ctx.getString(R.string.agent_setup_confirm_warning))
        }

        fun rejectedMessage(ctx: Context, r: SetupGuard.Review.Rejected): String {
            val host = r.host ?: "?"
            return when (r.problem) {
                SetupGuard.Problem.NOT_HTTPS -> ctx.getString(R.string.agent_setup_rejected_https, host)
                SetupGuard.Problem.HOST_NOT_ALLOWED -> ctx.getString(R.string.agent_setup_rejected_host, host)
                SetupGuard.Problem.BAD_URL -> ctx.getString(R.string.agent_setup_rejected_url)
                SetupGuard.Problem.BAD_TOKEN -> ctx.getString(R.string.agent_setup_rejected_token)
            }
        }
    }
}
