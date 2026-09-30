@file:OptIn(ExperimentalMaterial3Api::class)

package gr.ipexpert.inboxhelper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.engine.IntelEngine
import gr.ipexpert.inboxhelper.engine.Intel
import gr.ipexpert.inboxhelper.engine.Stats

@Composable
private fun Header(title: String, subtitle: String, onBack: (() -> Unit)?) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column(Modifier.padding(start = if (onBack == null) 6.dp else 0.dp)) {
            SectionLabel(subtitle)
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun MoreScreen(intel: List<Intel>, now: Long, hasAccess: Boolean, nav: Nav) {
    val route = nav.more
    when {
        route == "contacts" -> ContactsScreen(intel, nav)
        route.startsWith("contact:") -> ContactDetail(route.removePrefix("contact:"), intel, now, nav)
        route == "analytics" -> AnalyticsScreen(intel, now, nav)
        route == "tasks" -> TasksScreen(intel, nav)
        route == "templates" -> TemplatesScreen(nav)
        route == "audit" -> AuditScreen(nav)
        route == "settings" -> SettingsScreen(hasAccess, nav)
        route == "m365" -> M365Screen(nav)
        route == "search" -> SearchScreen(nav)
        route == "topics" -> TopicsScreen(intel, nav)
        route.startsWith("topic:") -> TopicDetail(route.removePrefix("topic:"), intel, now, nav)
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Header("More", "Περισσότερα", null) }
            val entries = listOf(
                Triple("m365", "Microsoft 365 & sync health", if (gr.ipexpert.inboxhelper.m365.M365Config.connected) "Connected · Outlook, Teams, sync status" else "Connect Outlook and Teams"),
                Triple("search", "Search everything", "Across Outlook, Teams, Calendar and phone, with an AI summary"),
                Triple("topics", "Topics & projects", "Communication grouped by project, with entities"),
                Triple("contacts", "Contacts", "Who is waiting, response times, open issues"),
                Triple("analytics", "Analytics", "Response times, SLA compliance, workload"),
                Triple("tasks", "Follow-ups & commitments", "Reminders and promises from conversations"),
                Triple("templates", "Ready answers", "Your quick replies (ΕΛ / EN)"),
                Triple("audit", "Audit log", "Every AI result, override and reply"),
                Triple("settings", "Settings", "Claude, SLA, business hours, alerts, privacy"),
            )
            items(entries) { (key, title, sub) ->
                Panel(modifier = Modifier.clickable { nav.more = key }) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(sub, fontSize = 13.sp, color = Muted)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ contacts

@Composable
private fun ContactsScreen(intel: List<Intel>, nav: Nav) {
    val contacts = Stats.contacts(intel)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Contacts", "${contacts.size} senders") { nav.more = "" } }
        items(contacts, key = { it.name }) { s ->
            Panel(modifier = Modifier.clickable { nav.more = "contact:" + s.name }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (s.unanswered > 0) Pill("${s.unanswered} WAITING ON YOU", Color(0xFFF7E1D3), UrgentText)
                }
                Text("${s.conversations.size} conversations · ${s.open} open" +
                    (if (s.avgResponseMs > 0) " · avg reply ${Clock.duration(s.avgResponseMs)}" else "") +
                    (if (s.slaBreaches > 0) " · ${s.slaBreaches} SLA breached" else ""), fontSize = 13.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun ContactDetail(name: String, intel: List<Intel>, now: Long, nav: Nav) {
    val s = Stats.contacts(intel).firstOrNull { it.name == name }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header(name, "Contact") { nav.more = "contacts" } }
        if (s == null) { item { Text("No conversations.", color = Muted) }; return@LazyColumn }
        item {
            Panel {
                KeyValue("Conversations", s.conversations.size.toString())
                KeyValue("Open requests", s.open.toString())
                KeyValue("Unanswered", s.unanswered.toString(), if (s.unanswered > 0) Critical else Ink)
                KeyValue("Avg response", if (s.avgResponseMs > 0) Clock.duration(s.avgResponseMs) else "—")
                KeyValue("SLA breaches", s.slaBreaches.toString())
                val learned = IntelEngine.learnedPriority(name, Repo.corrections.value)
                if (learned != null) {
                    KeyValue("Learned rule", "Treat as ${Level.label(learned.first)} (${learned.second} corrections)", Blue)
                    TextButton(onClick = { Repo.forgetCorrections(name) }) { Text("Forget this rule") }
                }
            }
        }
        if (s.commitments.isNotEmpty()) item {
            Panel {
                SectionLabel("Commitments")
                s.commitments.forEach { Text("• $it", fontSize = 14.sp) }
            }
        }
        item { SectionLabel("Conversations") }
        items(s.conversations, key = { it.conv.id }) { i -> ConversationRow(i, now) { nav.open(i.conv.id) } }
    }
}

// ------------------------------------------------------------------ analytics

@Composable
private fun AnalyticsScreen(intel: List<Intel>, now: Long, nav: Nav) {
    var days by rememberSaveable { mutableStateOf(7) }
    val since = now - days * 86_400_000L
    val inRange = intel.filter { it.conv.lastTime >= since }
    val r = Stats.responses(intel, now, since)
    val c = Stats.counts(inRange)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header("Analytics", "Last $days days") { nav.more = "" } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Today", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (d, l) ->
                    FilterChip(days == d, { days = d }, { Text(l) }, colors = tealChip())
                }
            }
        }
        item {
            Panel {
                SectionLabel("Response times (your replies)")
                KeyValue("Replies measured", r.count.toString())
                KeyValue("Average", if (r.count > 0) Clock.duration(r.avgMs) else "—")
                KeyValue("Median", if (r.count > 0) Clock.duration(r.medianMs) else "—")
                KeyValue("First response", if (r.avgFirstMs > 0) Clock.duration(r.avgFirstMs) else "—")
                KeyValue("Within SLA", if (r.count > 0) "${r.withinSlaPct}% (at current priority)" else "—")
                Text("Time is measured from the first unanswered incoming message to your next reply.", fontSize = 12.sp, color = Muted)
            }
        }
        item {
            Panel {
                SectionLabel("Who was expected to act")
                val total = (r.waitingOnMeMs + r.waitingOnOthersMs).coerceAtLeast(1)
                BarRow("On you", (r.waitingOnMeMs * 100 / total).toInt(), 100, Urgent)
                BarRow("On others", (r.waitingOnOthersMs * 100 / total).toInt(), 100, Blue)
                Text("Share of conversation time (%) where the last message was theirs (on you) vs yours (on others).", fontSize = 12.sp, color = Muted)
            }
        }
        item {
            Panel {
                SectionLabel("Now")
                KeyValue("Conversations", inRange.size.toString())
                KeyValue("Unanswered", c.unanswered.toString())
                KeyValue("Open requests", c.open.toString())
                KeyValue("SLA breached", c.slaBreached.toString())
                KeyValue("Waiting for others", c.waitingOthers.toString())
            }
        }
        item {
            Panel {
                SectionLabel("Incoming messages per day")
                val perDay = Stats.perDay(intel, minOf(days, 14).coerceAtLeast(1))
                val max = perDay.maxOfOrNull { it.second } ?: 0
                perDay.forEach { (d, n) -> BarRow(d, n, max) }
            }
        }
        item {
            Panel {
                SectionLabel("Workload by priority")
                val by = Level.all.map { l -> Level.label(l) to inRange.count { it.priority == l } }
                val max = by.maxOfOrNull { it.second } ?: 0
                by.forEach { (l, n) -> BarRow(l, n, max, Urgent) }
            }
        }
        item {
            Panel {
                SectionLabel("Workload by category")
                val by = Stats.byKey(inRange) { it.category }.take(10)
                val max = by.maxOfOrNull { it.second } ?: 0
                by.forEach { (l, n) -> BarRow(l, n, max) }
            }
        }
        item {
            Panel {
                SectionLabel("Workload by contact")
                val by = Stats.byKey(inRange) { it.conv.contact }.take(10)
                val max = by.maxOfOrNull { it.second } ?: 0
                by.forEach { (l, n) -> BarRow(l, n, max, Blue) }
            }
        }
    }
}

// ------------------------------------------------------------------ tasks / commitments

@Composable
private fun TasksScreen(intel: List<Intel>, nav: Nav) {
    val tasks by Repo.tasks.collectAsState()
    val open = intel.filter { !it.conv.archived }.flatMap { i -> (i.conv.analysis?.commitments ?: emptyList()).map { i to it } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Follow-ups", "Tasks & commitments") { nav.more = "" } }
        item { SectionLabel("Reminders") }
        if (tasks.isEmpty()) item { Text("No reminders. Create one from a commitment inside a conversation.", fontSize = 14.sp, color = Muted) }
        items(tasks, key = { it.id }) { t ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t.done, onCheckedChange = { Repo.setTaskDone(t.id, it) })
                    Column(Modifier.weight(1f).clickable { if (Repo.get(t.convId) != null) nav.open(t.convId) }) {
                        Text(t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Due ${Clock.short(t.dueAt)}" + (Repo.get(t.convId)?.let { " · ${it.contact}" } ?: ""), fontSize = 12.sp,
                            color = if (!t.done && t.dueAt < System.currentTimeMillis()) Critical else Muted)
                    }
                    IconButton(onClick = { Repo.deleteTask(t.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete reminder", tint = Muted) }
                }
            }
        }
        item { SectionLabel("Open commitments (from AI)", Modifier.padding(top = 8.dp)) }
        if (open.isEmpty()) item { Text("None detected.", fontSize = 14.sp, color = Muted) }
        items(open) { (i, cm) ->
            Panel(modifier = Modifier.clickable { nav.open(i.conv.id) }) {
                Text(when (cm.owner) { "me" -> "You"; "them" -> i.conv.contact; else -> "Third party" } + ": " + cm.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text((if (cm.due.isNotBlank()) "Due ${cm.due} · " else "") + i.conv.appName, fontSize = 12.sp, color = Muted)
            }
        }
    }
}

// ------------------------------------------------------------------ templates

@Composable
private fun TemplatesScreen(nav: Nav) {
    val templates by Settings.templates.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf("gr") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Ready answers", "Πρότυπα") { nav.more = "" } }
        item {
            Panel {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("New answer · Νέα απάντηση") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("gr" to "Ελληνικά", "en" to "English").forEach { (k, l) -> FilterChip(lang == k, { lang = k }, { Text(l) }, colors = tealChip()) }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = text.isNotBlank(), onClick = { Settings.addTemplate(lang, text); text = "" }) { Text("Add") }
                }
            }
        }
        items(templates, key = { it.id }) { t ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (t.lang == "gr") "ΕΛ" else "EN", if (t.lang == "gr") BlueSoft else Color(0xFFF5E3D8), if (t.lang == "gr") Blue else Color(0xFF8A3B12))
                    Spacer(Modifier.width(10.dp))
                    Text(t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { Settings.removeTemplate(t.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete answer", tint = Muted) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ audit

@Composable
private fun AuditScreen(nav: Nav) {
    val audit by Repo.audit.collectAsState()
    var query by remember { mutableStateOf("") }
    val list = audit.filter { query.isBlank() || (it.action + " " + it.detail + " " + it.convId).contains(query, ignoreCase = true) }.take(300)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Header("Audit log", "${audit.size} entries") { nav.more = "" } }
        item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Filter…") }) }
        items(list) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row {
                    Text(e.action.replace('_', ' '), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(Clock.short(e.time), fontSize = 12.sp, color = Muted)
                }
                Text("${e.actor}${if (e.convId.isNotBlank()) " · " + (Repo.get(e.convId)?.contact ?: e.convId.substringAfterLast('|')) else ""}" +
                    (if (e.detail.isNotBlank()) " — ${e.detail}" else ""), fontSize = 12.sp, color = Muted)
            }
        }
    }
}
