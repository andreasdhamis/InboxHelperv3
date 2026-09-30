@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.ai.AiService
import gr.ipexpert.inboxhelper.data.AnalysisState
import gr.ipexpert.inboxhelper.data.CATEGORIES
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.data.Status
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.engine.DeadlineState
import gr.ipexpert.inboxhelper.engine.Filter
import gr.ipexpert.inboxhelper.engine.Intel
import gr.ipexpert.inboxhelper.engine.SlaState
import gr.ipexpert.inboxhelper.engine.Sort
import kotlinx.coroutines.launch

@Composable
fun tealChip() = FilterChipDefaults.filterChipColors(selectedContainerColor = Teal, selectedLabelColor = Color.White)

@Composable
fun InboxScreen(intel: List<Intel>, now: Long, nav: Nav) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val f = nav.filter
    val list = nav.sort.apply(intel.filter { f.matches(it) })

    fun runAiSearch() {
        if (query.isBlank()) return
        if (!Settings.hasKey) { nav.filter = f.copy(text = query.trim()); return }
        aiBusy = true; aiError = ""
        scope.launch {
            try { nav.filter = AiService.parseSearch(query.trim()) }
            catch (e: Exception) { aiError = e.message ?: "Search failed"; nav.filter = f.copy(text = query.trim()) }
            aiBusy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(6.dp, 10.dp, 6.dp, 2.dp)) {
                SectionLabel("Priority inbox")
                Text("${list.size} conversations", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        item { Column {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Ask: unanswered customer requests older than 24h…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runAiSearch() }),
                trailingIcon = {
                    if (aiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { runAiSearch() }) { Text(if (Settings.hasKey) "✦ Search" else "Search") }
                },
            )
            if (aiError.isNotBlank()) Text(aiError, color = UrgentText, fontSize = 12.sp)
        } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val presets = listOf(
                    "Needs me" to Filter(attentionOnly = true),
                    "Unanswered" to Filter(statuses = Status.waitingOnMe),
                    "Waiting on others" to Filter(statuses = Status.waitingOnOthers),
                    "Critical + High" to Filter(priorities = setOf(Level.CRITICAL, Level.HIGH)),
                    "SLA risk" to Filter(sla = "risk"),
                    "Due this week" to Filter(deadline = "week"),
                    "All" to Filter(),
                )
                presets.forEach { (label, pf) ->
                    FilterChip(selected = f == pf, onClick = { nav.filter = pf; query = "" }, label = { Text(label) }, colors = tealChip())
                }
            }
        }
        item { Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showFilters = !showFilters }) { Text(if (showFilters) "Hide filters" else "Filters") }
                Box {
                    TextButton(onClick = { sortOpen = true }) { Text("Sort: ${nav.sort.label}") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        Sort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { nav.sort = s; sortOpen = false })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!f.isEmpty) TextButton(onClick = { nav.filter = Filter(); query = "" }) { Text("Clear") }
            }
            if (!f.isEmpty) Text("Filter: " + f.describe(), fontSize = 12.sp, color = Teal, fontWeight = FontWeight.SemiBold)
        } }
        if (showFilters) item { FilterPanel(f) { nav.filter = it } }
        if (list.isEmpty()) item {
            Text("No conversations match.", color = Muted, modifier = Modifier.padding(24.dp))
        }
        items(list, key = { it.conv.id }) { i -> ConversationRow(i, now) { nav.open(i.conv.id) } }
    }
}

@Composable
private fun FilterPanel(f: Filter, onChange: (Filter) -> Unit) {
    fun <T> toggle(set: Set<T>, v: T) = if (v in set) set - v else set + v
    Panel {
        SectionLabel("Status")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Status.all.forEach { s -> FilterChip(s in f.statuses, { onChange(f.copy(statuses = toggle(f.statuses, s))) }, { Text(Status.label(s)) }, colors = tealChip()) }
        }
        SectionLabel("Priority")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Level.all.forEach { l -> FilterChip(l in f.priorities, { onChange(f.copy(priorities = toggle(f.priorities, l))) }, { Text(Level.label(l)) }, colors = tealChip()) }
        }
        SectionLabel("Channel")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("sms" to "SMS", "mail" to "Email", "chat" to "Chat apps").forEach { (k, l) ->
                FilterChip(k in f.channels, { onChange(f.copy(channels = toggle(f.channels, k))) }, { Text(l) }, colors = tealChip())
            }
        }
        SectionLabel("Deadline · SLA · Age")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("today" to "Due today", "week" to "Due this week", "overdue" to "Overdue").forEach { (k, l) ->
                FilterChip(f.deadline == k, { onChange(f.copy(deadline = if (f.deadline == k) "" else k)) }, { Text(l) }, colors = tealChip())
            }
            listOf("risk" to "SLA risk", "breached" to "SLA breached").forEach { (k, l) ->
                FilterChip(f.sla == k, { onChange(f.copy(sla = if (f.sla == k) "" else k)) }, { Text(l) }, colors = tealChip())
            }
            listOf(4, 24, 48, 168).forEach { h ->
                FilterChip(f.minWaitHours == h, { onChange(f.copy(minWaitHours = if (f.minWaitHours == h) 0 else h)) }, { Text("Waiting > ${if (h < 168) "${h}h" else "1w"}") }, colors = tealChip())
            }
            FilterChip(f.minImportance == 70, { onChange(f.copy(minImportance = if (f.minImportance == 70) 0 else 70)) }, { Text("Importance ≥ 70") }, colors = tealChip())
            FilterChip(f.minUrgency == 70, { onChange(f.copy(minUrgency = if (f.minUrgency == 70) 0 else 70)) }, { Text("Urgency ≥ 70") }, colors = tealChip())
            FilterChip(f.showArchived, { onChange(f.copy(showArchived = !f.showArchived)) }, { Text("Include archived") }, colors = tealChip())
        }
        SectionLabel("Category")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CATEGORIES.forEach { c -> FilterChip(c in f.categories, { onChange(f.copy(categories = toggle(f.categories, c))) }, { Text(c) }, colors = tealChip()) }
        }
    }
}

@Composable
fun ConversationRow(i: Intel, now: Long, onClick: () -> Unit) {
    val c = i.conv
    val a = c.analysis
    val strong = i.waitingOnMe
    val breached = i.slaState == SlaState.BREACHED
    Row(
        Modifier.fillMaxWidth()
            .background(if (strong) Color.White else Paper, RoundedCornerShape(18.dp))
            .border(if (breached) 2.dp else 1.dp, if (breached) Critical else Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChannelBadge(c.channel, c.appName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.contact.ifBlank { c.appName }, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.demo) { Pill("DEMO", LowBg, Muted); Spacer(Modifier.width(6.dp)) }
                Text(Clock.duration(now - c.lastTime), fontSize = 12.sp, color = Muted)
            }
            Text(c.appName + (if (c.subject.isNotBlank()) " · ${c.subject}" else "") + " · ${i.category}",
                fontSize = 12.sp, color = Teal, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                c.analysisState == AnalysisState.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp)); Text("Claude is analysing…", fontSize = 13.sp, color = Muted)
                }
                a != null -> Text("✦ " + a.summary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else -> Text(c.lastMessage?.text ?: "", fontSize = 14.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (a != null && a.nextAction.isNotBlank() && i.waitingOnMe)
                Text("→ " + a.nextAction, fontSize = 12.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PriorityPill(i.priority, i.prioritySource)
                StatusPill(i.status)
                SlaPill(i.slaState)
                if (i.deadline != null && i.deadlineState != DeadlineState.LATER)
                    Pill(when (i.deadlineState) { DeadlineState.OVERDUE -> "OVERDUE"; DeadlineState.TODAY -> "DUE TODAY"; else -> "DUE THIS WEEK" },
                        if (i.deadlineState == DeadlineState.OVERDUE) Critical else Amber,
                        if (i.deadlineState == DeadlineState.OVERDUE) Color.White else AmberText)
                if (a?.escalation == true) Pill("ESCALATION", Critical, Color.White)
                if (i.needsReview) Pill("REVIEW", BlueSoft, Blue)
                if (c.analysisState == AnalysisState.FAILED) Pill("AI PENDING", LowBg, UrgentText)
            }
            if (i.waitingOnMe) {
                val biz = if (Settings.businessHoursOn) " (${Clock.duration(i.waitedBusinessMs)} business)" else ""
                Text("Unanswered for ${Clock.duration(i.waitedMs)}$biz", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (i.slaState == SlaState.BREACHED || i.slaState == SlaState.AT_RISK) Critical else Muted)
            } else if (i.waitingOnOthers) {
                Text("Waiting for reply · ${Clock.duration(now - c.lastTime)}", fontSize = 12.sp, color = Blue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
