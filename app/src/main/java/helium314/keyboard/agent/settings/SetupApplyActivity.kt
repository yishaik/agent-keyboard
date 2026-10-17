package helium314.keyboard.agent.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SettingsActivity

/** Receives agentkeyboard://setup?... from the phone setup page and writes the tokens. */
class SetupApplyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AgentSetup.fromIntent(intent)
        if (!cfg.isEmpty) {
            AgentSetup.apply(this, cfg)
            Toast.makeText(this, R.string.agent_setup_applied, Toast.LENGTH_SHORT).show()
        }
        startActivity(Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
