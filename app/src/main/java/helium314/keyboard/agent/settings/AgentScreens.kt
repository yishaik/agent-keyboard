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
        Section("Providers")
        ChoiceRow("Voice provider", s.voiceProvider, Provider.values()) { v -> store.update { it.copy(voiceProvider = v) } }
        ChoiceRow("Text provider", s.textProvider, Provider.values()) { v -> store.update { it.copy(textProvider = v) } }
        ChoiceRow("Hebrew voice", s.hebrewVoiceProvider ?: s.voiceProvider, Provider.values()) { v -> store.update { it.copy(hebrewVoiceProvider = v) } }
        ChoiceRow("English voice", s.englishVoiceProvider ?: s.voiceProvider, Provider.values()) { v -> store.update { it.copy(englishVoiceProvider = v) } }
        SwitchRow("Automatic fallback", s.autoFallback) { v -> store.update { it.copy(autoFallback = v) } }
        TextRow("Token broker URL", s.tokenBrokerUrl, { v -> store.update { it.copy(tokenBrokerUrl = v.trim()) } })
        OutlinedTextField(brokerToken, { brokerToken = it; secrets.brokerAppToken = it.trim() },
            label = { Text(stringResource(R.string.agent_broker_token)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))

        Section("Activation")
        ChoiceRow("Mic tap", s.micTap, MicTapAction.values()) { v -> store.update { it.copy(micTap = v) } }
        ChoiceRow("Turn taking", s.turnTaking, TurnTaking.values()) { v -> store.update { it.copy(turnTaking = v) } }
        SwitchRow("Let me interrupt", s.bargeIn) { v -> store.update { it.copy(bargeIn = v) } }
        ChoiceRow("Spoken reply", s.spokenReply, SpokenReply.values()) { v -> store.update { it.copy(spokenReply = v) } }

        Section("Screen reading")
        ChoiceRow("Mode", s.screenMode, ScreenMode.values()) { v -> store.update { it.copy(screenMode = v) } }
        SwitchRow("Allow screenshots", s.allowScreenshot) { v -> store.update { it.copy(allowScreenshot = v) } }
        Text("Password fields are never read.", style = MaterialTheme.typography.bodySmall)
        // TODO: app picker for blockedPackages

        Section("Actions & permissions")
        ChoiceRow("Insert text", s.insertMode, InsertMode.values()) { v -> store.update { it.copy(insertMode = v) } }
        // TODO: per-tool policy list from ToolRegistry

        Section("Knowledge & style")
        TextRow("About me", s.aboutMe, { v -> store.update { it.copy(aboutMe = v) } }, lines = 3)
        TextRow("Standing instructions", s.standingInstructions, { v -> store.update { it.copy(standingInstructions = v) } }, lines = 3)
        TextRow("Writing style (short / regular / formal)", s.writingStyle, { v -> store.update { it.copy(writingStyle = v) } })
        SwitchRow("Memory", s.memoryEnabled) { v -> store.update { it.copy(memoryEnabled = v) } }

        Section("Language")
        ChoiceRow("Reply language", s.replyLanguage, ReplyLanguage.values()) { v -> store.update { it.copy(replyLanguage = v) } }

        Section("Privacy & cost")
        TextRow("Keep history (days)", s.historyRetentionDays.toString(), { v -> v.toIntOrNull()?.let { d -> store.update { it.copy(historyRetentionDays = d) } } })
        TextRow("Monthly cap, USD (0 = none)", s.monthlyCapUsd.toString(), { v -> v.toFloatOrNull()?.let { c -> store.update { it.copy(monthlyCapUsd = c) } } })
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
        Section("Server (self-hosted OpenConnector)")
        TextRow("Worker URL", s.connectorUrl, { v -> store.update { it.copy(connectorUrl = v) } })
        OutlinedTextField(token, { token = it; secrets.connectorRuntimeToken = it.trim() }, label = { Text("Runtime token (not the admin token)") },
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

        Section("My connections")
        if (connections.isEmpty()) Text("Tap Test to load your connected accounts.", style = MaterialTheme.typography.bodySmall)
        connections.forEach { Text(it, modifier = Modifier.padding(vertical = 4.dp)) }

        Section("Add connection")
        Button(enabled = s.connectorUrl.isNotBlank(), onClick = {
            CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(s.connectorUrl.trimEnd('/') + "/"))
        }) { Text("Open console") }

        Section("Agent permissions")
        Text("Reads allowed, writes ask. Per-action allow / ask / block. TODO.", style = MaterialTheme.typography.bodySmall)
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
