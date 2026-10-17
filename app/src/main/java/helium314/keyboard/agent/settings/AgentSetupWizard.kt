package helium314.keyboard.agent.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import helium314.keyboard.agent.ime.MicPermissionActivity
import helium314.keyboard.latin.R

/**
 * After the IME enable/select wizard: connect the server, become the assistant, optional mic.
 */
@Composable
fun AgentSetupWizard(close: () -> Unit) {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf(AgentSetup.state(ctx)) }
    var paste by remember { mutableStateOf("") }
    fun refresh() { st = AgentSetup.state(ctx) }

    DisposableEffect(ctx) {
        val owner = ctx as? ComponentActivity
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val imeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(stringResource(R.string.agent_setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.agent_setup_lede),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )

            SetupRow(
                done = st.imeEnabled,
                title = stringResource(R.string.agent_setup_ime_enable),
                action = stringResource(R.string.agent_setup_open_settings),
            ) {
                imeLauncher.launch(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT))
            }
            SetupRow(
                done = st.imeCurrent,
                title = stringResource(R.string.agent_setup_ime_select),
                action = stringResource(R.string.agent_setup_choose),
                enabled = st.imeEnabled,
            ) {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            SetupRow(
                done = st.assistant,
                title = stringResource(R.string.agent_setup_assistant),
                action = stringResource(R.string.agent_setup_allow),
            ) { roleLauncher.launch(AgentSetup.assistantIntent(ctx)) }
            SetupRow(
                done = st.broker,
                title = stringResource(R.string.agent_setup_server),
                action = stringResource(R.string.agent_setup_open_page),
            ) { AgentSetup.openSetupPage(ctx) }

            if (!st.broker) {
                Text(
                    stringResource(R.string.agent_setup_apply_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    paste,
                    { paste = it },
                    label = { Text(stringResource(R.string.agent_setup_paste)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 2,
                )
                Button(
                    onClick = {
                        val cfg = SetupConfig.parse(paste)
                        if (cfg.isEmpty) {
                            Toast.makeText(ctx, R.string.agent_setup_paste_bad, Toast.LENGTH_SHORT).show()
                        } else {
                            AgentSetup.apply(ctx, cfg)
                            paste = ""
                            refresh()
                            Toast.makeText(ctx, R.string.agent_setup_applied, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = paste.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.agent_setup_save_paste)) }
            } else {
                Text(
                    if (st.connector) stringResource(R.string.agent_setup_server_ok)
                    else stringResource(R.string.agent_setup_server_broker_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            val micOk = Build.VERSION.SDK_INT < 23 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            SetupRow(
                done = micOk,
                title = stringResource(R.string.agent_setup_mic),
                action = stringResource(R.string.agent_grant),
            ) {
                ctx.startActivity(Intent(ctx, MicPermissionActivity::class.java))
            }

            Spacer(Modifier.height(24.dp))
            val ready = st.imeEnabled && st.imeCurrent && st.broker
            Button(
                onClick = close,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.agent_setup_done)) }
            if (!st.assistant || !st.broker) {
                TextButton(onClick = {
                    if (!st.assistant) AgentSetup.skipAssistant(ctx)
                    if (!st.broker) AgentSetup.skipConfig(ctx)
                    close()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.agent_setup_later))
                }
            }
        }
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    title: String,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(if (done) "✓  $title" else title, style = MaterialTheme.typography.titleMedium)
        }
        if (!done) {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}
