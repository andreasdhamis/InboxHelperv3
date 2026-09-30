@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.ai.Ai
import gr.ipexpert.inboxhelper.data.Demo
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.work.Analyzer

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
private fun parseHm(s: String): Int? = Regex("^(\\d{1,2}):(\\d{2})$").find(s.trim())?.let {
    val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
    if (h in 0..24 && m in 0..59) (h * 60 + m).coerceAtMost(24 * 60) else null
}

@Composable
fun SettingsScreen(hasAccess: Boolean, nav: Nav) {
    val ctx = LocalContext.current
    // Re-read values whenever settings change.
    @Suppress("UNUSED_VARIABLE") val version = Settings.version.collectAsState().value

    var key by remember { mutableStateOf("") }
    var fast by remember { mutableStateOf(Settings.fastModel) }
    var smart by remember { mutableStateOf(Settings.smartModel) }
    var about by remember { mutableStateOf(Settings.aboutMe) }
    var conf by remember { mutableStateOf(Settings.confidenceThreshold.toString()) }
    var start by remember { mutableStateOf(hm(Settings.workStartMin)) }
    var end by remember { mutableStateOf(hm(Settings.workEndMin)) }
    var holidays by remember { mutableStateOf(Settings.holidays.sorted().joinToString(", ")) }
    var retention by remember { mutableStateOf(Settings.retentionDays.toString()) }
    var briefHour by remember { mutableStateOf(Settings.briefingHour.toString()) }
    val slaText = remember { Level.all.take(4).associateWith { mutableStateOf(Settings.slaMinutes(it).toString()) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.more = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column { SectionLabel("Ρυθμίσεις"); Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }
        }

        Panel(bg = if (hasAccess) TealSoft else Color(0xFFF7E1D3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hasAccess) "Notification access: ON" else "Notification access: OFF", fontWeight = FontWeight.Bold,
                    color = if (hasAccess) Teal else UrgentText, modifier = Modifier.weight(1f))
                TextButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Change") }
            }
        }

        // ---------------- AI
        Panel {
            SectionLabel("AI · ${Ai.provider.name}")
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (Settings.hasKey) "API key saved — paste to replace" else "Claude API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("console.anthropic.com → API Keys. Encrypted with the phone's keystore; never displayed.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = key.isNotBlank(), onClick = { Settings.apiKey = key; key = ""; Analyzer.sweep() }) { Text("Save key") }
                if (Settings.hasKey) TextButton(onClick = { Settings.apiKey = "" }) { Text("Remove key") }
            }
            OutlinedTextField(fast, { fast = it; Settings.fastModel = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Fast model (analysis, search)") }, supportingText = { Text("Default ${Settings.DEFAULT_FAST_MODEL}") })
            OutlinedTextField(smart, { smart = it; Settings.smartModel = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Smart model (drafts, assistant, briefings)") }, supportingText = { Text("Default ${Settings.DEFAULT_SMART_MODEL}") })
            OutlinedTextField(about, { about = it; Settings.aboutMe = it }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("About you — helps judge importance") },
                placeholder = { Text("e.g. Network engineer at ipexpert; hotel clients and outages are top priority") })
            Text("AI language (summaries, explanations)", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("gr" to "Ελληνικά", "en" to "English").forEach { (k, l) -> FilterChip(Settings.aiLang == k, { Settings.aiLang = k }, { Text(l) }, colors = tealChip()) }
            }
            OutlinedTextField(conf, { conf = it; it.toIntOrNull()?.let { v -> Settings.confidenceThreshold = v } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Flag for review below confidence (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SwitchRow("Analyse new messages automatically", "Only new or changed conversations are sent, once.", Settings.autoAnalyze) { Settings.autoAnalyze = it }
            SwitchRow("Learn from my corrections", "After 2 identical priority changes for a contact, apply them automatically (shown as “learned”).", Settings.learningEnabled) { Settings.learningEnabled = it }
        }

        // ---------------- voice
        Panel {
            SectionLabel("Voice")
            Text("Speech recognition language", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("el-GR" to "Ελληνικά", "en-US" to "English").forEach { (k, l) -> FilterChip(Settings.voiceLang == k, { Settings.voiceLang = k }, { Text(l) }, colors = tealChip()) }
            }
            Text("Tap 🎤 anywhere and speak naturally. Sending always asks for your confirmation.", fontSize = 12.sp, color = Muted)
        }

        Panel(modifier = Modifier.clickable { nav.more = "m365" }) {
            SectionLabel("Microsoft 365")
            Text(if (gr.ipexpert.inboxhelper.m365.M365Config.connected) "Connected — manage Outlook/Teams, permissions and sync health →" else "Connect Outlook and Teams →", color = Teal, fontWeight = FontWeight.SemiBold)
        }

        // ---------------- SLA
        Panel {
            SectionLabel("Response SLA (minutes)")
            Level.all.take(4).forEach { l ->
                val st = slaText.getValue(l)
                OutlinedTextField(st.value, { st.value = it; it.toIntOrNull()?.let { v -> Settings.setSla(l, v) } }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(Level.label(l)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("States: within < 50% · approaching 50–80% · at risk 80–100% · breached > 100% of the target.", fontSize = 12.sp, color = Muted)
        }

        // ---------------- business hours
        Panel {
            SectionLabel("Business hours · ${gr.ipexpert.inboxhelper.engine.Clock.zone.id}")
            SwitchRow("Count only working time", "Waiting times and SLAs use business hours.", Settings.businessHoursOn) { Settings.businessHoursOn = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun").forEach { (d, l) ->
                    val on = d in Settings.workDays
                    FilterChip(on, { Settings.workDays = if (on) Settings.workDays - d else Settings.workDays + d }, { Text(l) }, colors = tealChip())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it; parseHm(it)?.let { v -> Settings.workStartMin = v } }, Modifier.weight(1f), singleLine = true, label = { Text("Start (HH:MM)") })
                OutlinedTextField(end, { end = it; parseHm(it)?.let { v -> Settings.workEndMin = v } }, Modifier.weight(1f), singleLine = true, label = { Text("End (HH:MM)") })
            }
            OutlinedTextField(holidays, { holidays = it; Settings.holidays = it.split(",").map { s -> s.trim() }.toSet() }, Modifier.fillMaxWidth(),
                label = { Text("Holidays (YYYY-MM-DD, comma separated)") }, placeholder = { Text("2026-10-28, 2026-12-25") })
        }

        // ---------------- notifications
        Panel {
            SectionLabel("Alerts")
            SwitchRow("New critical conversation", "", Settings.notifyCritical) { Settings.notifyCritical = it }
            SwitchRow("SLA at risk / breached", "", Settings.notifySla) { Settings.notifySla = it }
            SwitchRow("Deadline approaching / passed", "", Settings.notifyDeadline) { Settings.notifyDeadline = it }
            SwitchRow("Escalation detected", "", Settings.notifyEscalation) { Settings.notifyEscalation = it }
            SwitchRow("Morning briefing", "Daily summary notification", Settings.morningBriefing) { Settings.morningBriefing = it }
            OutlinedTextField(briefHour, { briefHour = it; it.toIntOrNull()?.let { v -> Settings.briefingHour = v } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Briefing hour (0–23)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Checks run about every 15 minutes (Android's minimum for background work).", fontSize = 12.sp, color = Muted)
        }

        // ---------------- apps
        Panel {
            SectionLabel("Apps to watch")
            Settings.knownApps.forEachIndexed { idx, a ->
                if (idx > 0) HorizontalDivider(color = Line)
                SwitchRow(a.name, a.channel.uppercase(), Settings.isAppEnabled(a.pkg)) { Settings.setAppEnabled(a.pkg, it) }
            }
        }

        // ---------------- privacy & data
        Panel {
            SectionLabel("Privacy & data")
            Text("Message text is sent only to the AI provider above, with your key, and only the recent part of a conversation " +
                "(quotes and signatures removed, size-capped). The assistant receives summaries and status, not full messages. " +
                "Everything is stored in the app's private storage on this phone.", fontSize = 13.sp, color = Muted)
            OutlinedTextField(retention, { retention = it; it.toIntOrNull()?.let { v -> Settings.retentionDays = v } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Delete conversations inactive for (days)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Demo.load(System.currentTimeMillis()); Analyzer.sweep() }) { Text("Load demo data") }
                OutlinedButton(onClick = { Demo.remove() }) { Text("Remove demo data") }
                OutlinedButton(onClick = { Repo.clearAll() }) { Text("Delete all conversations") }
            }
        }
        Text("Inbox Helper 2.0", fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(24.dp).width(1.dp))
    }
}
