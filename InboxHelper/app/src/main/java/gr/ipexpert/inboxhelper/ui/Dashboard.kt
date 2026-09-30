package gr.ipexpert.inboxhelper.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.data.Demo
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.data.Status
import gr.ipexpert.inboxhelper.engine.Filter
import gr.ipexpert.inboxhelper.engine.Intel
import gr.ipexpert.inboxhelper.engine.Sort
import gr.ipexpert.inboxhelper.engine.Stats
import gr.ipexpert.inboxhelper.work.Analyzer

private data class Tile(val label: String, val value: Int, val alert: Boolean, val filter: Filter, val sort: Sort = Sort.PRIORITY)

@Composable
fun DashboardScreen(intel: List<Intel>, now: Long, hasAccess: Boolean, nav: Nav) {
    val ctx = LocalContext.current
    val c = Stats.counts(intel)
    val top = Sort.PRIORITY.apply(intel.filter { it.requiresAttention }).take(6)

    val tiles = listOf(
        Tile("Requires attention", c.attention, false, Filter(attentionOnly = true)),
        Tile("Unanswered", c.unanswered, false, Filter(statuses = Status.waitingOnMe), Sort.LONGEST_WAIT),
        Tile("SLA at risk", c.slaAtRisk, c.slaAtRisk > 0, Filter(sla = "risk"), Sort.SLA),
        Tile("SLA breached", c.slaBreached, c.slaBreached > 0, Filter(sla = "breached"), Sort.SLA),
        Tile("Critical", c.critical, c.critical > 0, Filter(priorities = setOf(Level.CRITICAL))),
        Tile("High priority", c.high, false, Filter(priorities = setOf(Level.HIGH))),
        Tile("Waiting for others", c.waitingOthers, false, Filter(statuses = Status.waitingOnOthers), Sort.OLDEST),
        Tile("Due today", c.dueToday, c.dueToday > 0, Filter(deadline = "today"), Sort.DEADLINE),
        Tile("Due this week", c.dueWeek, false, Filter(deadline = "week"), Sort.DEADLINE),
        Tile("Overdue", c.overdue, c.overdue > 0, Filter(deadline = "overdue"), Sort.DEADLINE),
        Tile("Escalations", c.escalations, c.escalations > 0, Filter(escalationOnly = true), Sort.PRIORITY),
        Tile("Waiting > 48h", c.over48h, c.over48h > 0, Filter(minWaitHours = 48), Sort.LONGEST_WAIT),
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(4.dp, 10.dp, 4.dp, 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val first = gr.ipexpert.inboxhelper.m365.M365Config.account?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                val hour = java.time.LocalTime.now().hour
                SectionLabel((if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening") + (first?.let { ", $it" } ?: ""))
                Text("${c.attention} need you", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        item {
            Panel(bg = TealSoft) {
                Text("✦ WHAT MATTERS", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text(Stats.headline(intel), fontSize = 15.sp, lineHeight = 21.sp)
                if (c.pendingAnalysis > 0) Text("${c.pendingAnalysis} conversations waiting for AI analysis", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.tab = 2 }) { Text("Briefing & questions") }
                    if (c.pendingAnalysis > 0 && Settings.hasKey) OutlinedButton(onClick = { Analyzer.sweep(); intel.filter { it.conv.analysis == null || it.conv.stale }.forEach { Analyzer.enqueue(it.conv.id) } }) { Text("Analyze now") }
                }
            }
        }
        item { M365Banner(nav) }
        if (!hasAccess) item {
            Panel(bg = Color(0xFFF7E1D3)) {
                Text("Allow notification access", fontWeight = FontWeight.Bold, color = UrgentText)
                Text("New messages from SMS, email and chat apps reach the app through your notifications.", fontSize = 14.sp)
                Button(
                    onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    colors = ButtonDefaults.buttonColors(containerColor = UrgentText),
                ) { Text("Open settings") }
            }
        }
        if (!Settings.hasKey) item {
            Panel(bg = TealSoft, modifier = Modifier.clickable { nav.tab = 3; nav.more = "settings" }) {
                Text("Add your Claude API key", fontWeight = FontWeight.Bold, color = Teal)
                Text("Settings → Claude. Without it, messages are collected but not analysed.", fontSize = 14.sp)
            }
        }
        item {
            // 2-column tile grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            Column(
                                Modifier.weight(1f)
                                    .background(if (t.alert) Color(0xFFF7E1D3) else Paper, RoundedCornerShape(16.dp))
                                    .border(1.dp, if (t.alert) Urgent else Line, RoundedCornerShape(16.dp))
                                    .clickable { nav.showInbox(t.filter, t.sort) }
                                    .padding(14.dp),
                            ) {
                                Text(t.value.toString(), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = if (t.alert) Critical else Ink)
                                Text(t.label, fontSize = 13.sp, color = Muted)
                            }
                        }
                        if (row.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SectionLabel("Needs you now", Modifier.weight(1f))
                Text("See all", color = Teal, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clickable { nav.showInbox(Filter(attentionOnly = true)) })
            }
        }
        if (top.isEmpty()) item {
            Panel {
                Text("Nothing is waiting on you.", fontWeight = FontWeight.SemiBold)
                if (intel.isEmpty()) {
                    Text("No conversations yet. New messages will appear here, or try the demo data.", fontSize = 14.sp, color = Muted)
                    OutlinedButton(onClick = { Demo.load(System.currentTimeMillis()); Analyzer.sweep() }) { Text("Load demo conversations") }
                }
            }
        }
        items(top, key = { it.conv.id }) { i -> ConversationRow(i, now) { nav.open(i.conv.id) } }
    }
}


@Composable
private fun M365Banner(nav: Nav) {
    @Suppress("UNUSED_VARIABLE") val v = gr.ipexpert.inboxhelper.m365.M365Config.version.collectAsState().value
    @Suppress("UNUSED_VARIABLE") val t = gr.ipexpert.inboxhelper.m365.SyncEngine.tick.collectAsState().value
    val progress by gr.ipexpert.inboxhelper.m365.SyncEngine.progress.collectAsState()
    val cfg = gr.ipexpert.inboxhelper.m365.M365Config
    when {
        !cfg.connected -> Panel(modifier = Modifier.clickable { nav.go("m365") }) {
            Text("Connect Microsoft 365", fontWeight = FontWeight.Bold, color = Teal)
            Text("Outlook + Teams with full history, context-aware replies and Send via Outlook.", fontSize = 13.sp, color = Muted)
        }
        progress.running -> Panel(bg = TealSoft, modifier = Modifier.clickable { nav.go("m365") }) {
            Text("Syncing Microsoft 365 — ${progress.phase}", fontWeight = FontWeight.SemiBold, color = Teal, fontSize = 13.sp)
            if (progress.discovered > 0) Text("Indexed ${progress.indexed} of ${progress.discovered}", fontSize = 12.sp, color = Muted)
        }
        else -> {
            val bad = gr.ipexpert.inboxhelper.m365.M365Session.health().filter { it.connected && it.status !in setOf("healthy", "idle", "syncing") }
            if (bad.isNotEmpty()) Panel(bg = Color(0xFFF7E1D3), modifier = Modifier.clickable { nav.go("m365") }) {
                Text("Microsoft 365 sync needs attention", fontWeight = FontWeight.Bold, color = UrgentText)
                Text(bad.joinToString(" · ") { "${it.title}: ${it.status}" }, fontSize = 12.sp)
            }
        }
    }
}
