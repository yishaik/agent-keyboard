package helium314.keyboard.agent.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.agent.connectors.OpenConnectorClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/*
 * Two new categories in HeliBoard's settings: "Agent" (first) and "Connections".
 * Strings are inline for the skeleton; TODO move to res/values{,-iw}/strings.xml.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = onBack) { Text("\u2190") } // TODO: reuse HeliBoard's back icon
        })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), content = content)
    }
}

@Composable private fun Section(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))

@Composable private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp)) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }

@Composable private fun <T : Enum<T>> ChoiceRow(label: String, value: T, options: Array<T>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp)) {
        Text(label, Modifier.weight(1f)); Text(value.name.lowercase().replace('_', ' '), color = MaterialTheme.colorScheme.secondary)
    }
    DropdownMenu(open, { open = false }) {
        options.forEach { o -> DropdownMenuItem(text = { Text(o.name.lowercase().replace('_', ' ')) }, onClick = { onPick(o); open = false }) }
    }
}

@Composable private fun TextRow(label: String, value: String, onChange: (String) -> Unit, lines: Int = 1) =
    OutlinedTextField(value, onChange, label = { Text(label) }, minLines = lines, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))

@Composable
fun AgentScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { AgentSettingsStore.get(ctx) }
    val secrets = remember { SecretStore(ctx) }
    val s by store.state.collectAsState()
    var brokerToken by remember { mutableStateOf(secrets.brokerAppToken.orEmpty()) }
    AgentScaffold(stringResource(R.string.settings_screen_agent), onClickBack) {
        Section(stringResource(R.string.as_providers))
        ChoiceRow(stringResource(R.string.as_voice_provider), s.voiceProvider, Provider.values()) { v -> store.update { it.copy(voiceProvider = v) } }
        ChoiceRow(stringResource(R.string.as_text_provider), s.textProvider, Provider.values()) { v -> store.update { it.copy(textProvider = v) } }
        ChoiceRow(stringResource(R.string.as_hebrew_voice), s.hebrewVoiceProvider ?: s.voiceProvider, Provider.values()) { v -> store.update { it.copy(hebrewVoiceProvider = v) } }
        ChoiceRow(stringResource(R.string.as_english_voice), s.englishVoiceProvider ?: s.voiceProvider, Provider.values()) { v -> store.update { it.copy(englishVoiceProvider = v) } }
        SwitchRow(stringResource(R.string.as_auto_fallback), s.autoFallback) { v -> store.update { it.copy(autoFallback = v) } }
        TextRow(stringResource(R.string.as_broker_url), s.tokenBrokerUrl, { v -> store.update { it.copy(tokenBrokerUrl = v.trim()) } })
        OutlinedTextField(brokerToken, { brokerToken = it; secrets.brokerAppToken = it.trim() },
            label = { Text(stringResource(R.string.agent_broker_token)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))

        Section(stringResource(R.string.as_activation))
        ChoiceRow(stringResource(R.string.as_mic_tap), s.micTap, MicTapAction.values()) { v -> store.update { it.copy(micTap = v) } }
        ChoiceRow(stringResource(R.string.as_turn_taking), s.turnTaking, TurnTaking.values()) { v -> store.update { it.copy(turnTaking = v) } }
        SwitchRow(stringResource(R.string.as_barge_in), s.bargeIn) { v -> store.update { it.copy(bargeIn = v) } }
        ChoiceRow(stringResource(R.string.as_spoken_reply), s.spokenReply, SpokenReply.values()) { v -> store.update { it.copy(spokenReply = v) } }

        Section(stringResource(R.string.as_screen_reading))
        ChoiceRow(stringResource(R.string.as_screen_mode), s.screenMode, ScreenMode.values()) { v -> store.update { it.copy(screenMode = v) } }
        SwitchRow(stringResource(R.string.as_allow_screenshots), s.allowScreenshot) { v -> store.update { it.copy(allowScreenshot = v) } }
        Text(stringResource(R.string.as_password_note), style = MaterialTheme.typography.bodySmall)
        // TODO: app picker for blockedPackages

        Section(stringResource(R.string.as_actions))
        ChoiceRow(stringResource(R.string.as_insert_text), s.insertMode, InsertMode.values()) { v -> store.update { it.copy(insertMode = v) } }
        // TODO: per-tool policy list from ToolRegistry

        Section(stringResource(R.string.as_knowledge))
        TextRow(stringResource(R.string.as_about_me), s.aboutMe, { v -> store.update { it.copy(aboutMe = v) } }, lines = 3)
        TextRow(stringResource(R.string.as_standing), s.standingInstructions, { v -> store.update { it.copy(standingInstructions = v) } }, lines = 3)
        TextRow(stringResource(R.string.as_writing_style), s.writingStyle, { v -> store.update { it.copy(writingStyle = v) } })
        SwitchRow(stringResource(R.string.as_memory), s.memoryEnabled) { v -> store.update { it.copy(memoryEnabled = v) } }

        Section(stringResource(R.string.as_language))
        ChoiceRow(stringResource(R.string.as_reply_language), s.replyLanguage, ReplyLanguage.values()) { v -> store.update { it.copy(replyLanguage = v) } }

        Section(stringResource(R.string.as_privacy))
        TextRow(stringResource(R.string.as_history_days), s.historyRetentionDays.toString(), { v -> v.toIntOrNull()?.let { d -> store.update { it.copy(historyRetentionDays = d) } } })
        TextRow(stringResource(R.string.as_monthly_cap), s.monthlyCapUsd.toString(), { v -> v.toFloatOrNull()?.let { c -> store.update { it.copy(monthlyCapUsd = c) } } })
    }
}

@Composable
fun ConnectionsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { AgentSettingsStore.get(ctx) }
    val secrets = remember { SecretStore(ctx) }
    val s by store.state.collectAsState()
    var token by remember { mutableStateOf(secrets.connectorRuntimeToken.orEmpty()) }
    AgentScaffold(stringResource(R.string.settings_screen_connections), onClickBack) {
        Section(stringResource(R.string.as_server))
        TextRow(stringResource(R.string.as_worker_url), s.connectorUrl, { v -> store.update { it.copy(connectorUrl = v) } })
        OutlinedTextField(token, { token = it; secrets.connectorRuntimeToken = it.trim() }, label = { Text(stringResource(R.string.as_runtime_token)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<String?>(null) }
        var connections by remember { mutableStateOf<List<String>>(emptyList()) }
        val client = remember { OpenConnectorClient(OkHttpClient(), { store.state.value.connectorUrl }, { secrets.connectorRuntimeToken }) }
        val okText = stringResource(R.string.agent_test_ok)
        val failText = stringResource(R.string.agent_test_fail)
        Button(enabled = s.connectorUrl.isNotBlank() && token.isNotBlank(), onClick = {
            scope.launch {
                status = runCatching {
                    val list = client.listConnections()
                    connections = connectionLines(list)
                    okText
                }.getOrElse { failText.format(it.message ?: "?") }
            }
        }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.agent_test)) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Section(stringResource(R.string.as_my_connections))
        if (connections.isEmpty()) Text(stringResource(R.string.as_tap_test), style = MaterialTheme.typography.bodySmall)
        connections.forEach { Text(it, modifier = Modifier.padding(vertical = 4.dp)) }

        Section(stringResource(R.string.as_add_connection))
        Button(enabled = s.connectorUrl.isNotBlank(), onClick = {
            CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(s.connectorUrl.trimEnd('/') + "/"))
        }) { Text(stringResource(R.string.as_open_console)) }

        Section(stringResource(R.string.as_agent_permissions))
        Text(stringResource(R.string.as_perm_note), style = MaterialTheme.typography.bodySmall)
    }
}

/** "gmail · work · me@example.com" lines from list_connections output, whatever its exact envelope. */
private fun connectionLines(e: JsonElement): List<String> {
    val arr = when (e) {
        is JsonArray -> e
        is JsonObject -> (e["connections"] ?: e["data"] ?: e["items"])?.let { it as? JsonArray } ?: JsonArray(listOf(e))
        else -> return listOf(e.toString())
    }
    return arr.map { el ->
        val o = el as? JsonObject ?: return@map el.toString()
        fun f(vararg k: String) = k.firstNotNullOfOrNull { o[it]?.let { v -> (v as? JsonPrimitive)?.contentOrNull } }
        listOfNotNull(f("service", "provider"), f("connectionName", "alias", "name"),
            f("accountName", "email", "login", "account"), if (o["default"]?.jsonPrimitive?.booleanOrNull == true) "default" else null)
            .joinToString(" · ")
    }
}
