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
import androidx.compose.ui.unit.dp

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
    val s by store.state.collectAsState()
    AgentScaffold("Agent", onClickBack) {
        Section("Providers")
        ChoiceRow("Voice provider", s.voiceProvider, Provider.values()) { v -> store.update { it.copy(voiceProvider = v) } }
        ChoiceRow("Text provider", s.textProvider, Provider.values()) { v -> store.update { it.copy(textProvider = v) } }
        ChoiceRow("Hebrew voice", s.hebrewVoiceProvider ?: s.voiceProvider, Provider.values()) { v -> store.update { it.copy(hebrewVoiceProvider = v) } }
        ChoiceRow("English voice", s.englishVoiceProvider ?: s.voiceProvider, Provider.values()) { v -> store.update { it.copy(englishVoiceProvider = v) } }
        SwitchRow("Automatic fallback", s.autoFallback) { v -> store.update { it.copy(autoFallback = v) } }
        TextRow("Token broker URL", s.tokenBrokerUrl, { v -> store.update { it.copy(tokenBrokerUrl = v) } })

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
    AgentScaffold("Connections", onClickBack) {
        Section("Server (self-hosted OpenConnector)")
        TextRow("Worker URL", s.connectorUrl, { v -> store.update { it.copy(connectorUrl = v) } })
        OutlinedTextField(token, { token = it; secrets.connectorRuntimeToken = it }, label = { Text("Runtime token (not the admin token)") }, modifier = Modifier.fillMaxWidth())
        // TODO: "Test" button -> OpenConnectorClient.health()

        Section("My connections")
        Text("TODO: list connections with alias, status, scopes; reconnect / disconnect.", style = MaterialTheme.typography.bodySmall)

        Section("Add connection")
        Button(enabled = s.connectorUrl.isNotBlank(), onClick = {
            CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(s.connectorUrl.trimEnd('/') + "/"))
        }) { Text("Open console") }

        Section("Agent permissions")
        Text("Reads allowed, writes ask. Per-action allow / ask / block. TODO.", style = MaterialTheme.typography.bodySmall)
    }
}
