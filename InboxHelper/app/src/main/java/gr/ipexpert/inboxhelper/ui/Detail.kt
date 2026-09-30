@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.produceState
import gr.ipexpert.inboxhelper.data.Source
import gr.ipexpert.inboxhelper.index.Item
import gr.ipexpert.inboxhelper.index.Retriever
import gr.ipexpert.inboxhelper.m365.Attachment
import gr.ipexpert.inboxhelper.m365.MailMode
import gr.ipexpert.inboxhelper.m365.Sender
import gr.ipexpert.inboxhelper.voice.Drafts
import gr.ipexpert.inboxhelper.voice.Segment
import gr.ipexpert.inboxhelper.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.ai.AiService
import gr.ipexpert.inboxhelper.data.AnalysisState
import gr.ipexpert.inboxhelper.data.CATEGORIES
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.data.Status
import gr.ipexpert.inboxhelper.data.Task
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.engine.DeadlineState
import gr.ipexpert.inboxhelper.engine.Intel
import gr.ipexpert.inboxhelper.engine.SlaState
import gr.ipexpert.inboxhelper.work.Analyzer
import gr.ipexpert.inboxhelper.work.Replier
import gr.ipexpert.inboxhelper.work.ReminderWorker
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun responderLabel(r: String) = when (r) {
    "me" -> "You"; "them" -> "The contact"; "third_party" -> "A third party"; else -> "Unclear"
}

@Composable
fun DetailScreen(id: String, intel: List<Intel>, now: Long, nav: Nav) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val templates by Settings.templates.collectAsState()
    val replyVersion by Repo.replyVersion.collectAsState()
    val i = intel.firstOrNull { it.conv.id == id }
    if (i == null) {
        LaunchedEffect(id) { nav.openConv = null }
        return
    }
    val c = i.conv
    val a = c.analysis

    val draft = Drafts.text[id] ?: ""
    fun setDraft(t: String) = Drafts.set(id, t)
    var mode by remember(id) { mutableStateOf(MailMode.REPLY) }
    var toField by remember(id) { mutableStateOf("") }
    val attachments = remember(id) { mutableStateListOf<Attachment>() }
    var pending by remember(id) { mutableStateOf<PendingSend?>(null) }
    var sending by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { uris.forEach { u -> try { attachments += Sender.readAttachment(ctx, u) } catch (_: Exception) { } } }
    }
    val related by produceState(emptyList<Item>(), id, c.contentHash, a?.analyzedAt) {
        value = if (c.source == Source.PHONE && c.ext.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            try { Retriever.forConversation(c, 6).map { it.item } } catch (_: Exception) { emptyList() }
        }
    }
    // Reading replyVersion makes the label refresh when a phone app's reply button appears/disappears.
    val sendLabel = if (replyVersion >= 0) SendActions.channelLabel(c) else ""
    var busy by remember { mutableStateOf("") }            // "", draft, improve, detail
    var menu by remember { mutableStateOf(false) }
    var tone by rememberSaveable { mutableStateOf("Professional") }
    var lang by rememberSaveable(id) { mutableStateOf(if (a?.language == "el" || c.messages.any { m -> m.text.any { it in 'Α'..'ω' } }) "gr" else "en") }
    val expanded = remember { mutableStateListOf<String>() }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---------------- top bar
        Row(Modifier.padding(4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.openConv = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text(c.contact.ifBlank { c.appName }, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.appName + (if (c.subject.isNotBlank()) " · ${c.subject}" else ""), fontSize = 12.sp, color = Teal,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Re-analyse with AI") }, onClick = { menu = false; Analyzer.enqueue(id, force = true) })
                    DropdownMenuItem(text = { Text(if (c.archived) "Unarchive" else "Archive") }, onClick = { menu = false; Repo.archive(id, !c.archived); if (!c.archived) nav.openConv = null })
                    DropdownMenuItem(text = { Text("Open contact") }, onClick = { menu = false; nav.openConv = null; nav.tab = 3; nav.more = "contact:" + c.contact })
                    DropdownMenuItem(text = { Text("Delete from app") }, onClick = { menu = false; Repo.delete(id); nav.openConv = null })
                }
            }
        }
        HorizontalDivider(color = Line)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- header chips
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PriorityPill(i.priority, i.prioritySource)
                StatusPill(i.status)
                SlaPill(i.slaState)
                if (a?.escalation == true) Pill("POTENTIAL ESCALATION", Critical, Color.White)
                if (c.demo) Pill("DEMO DATA", LowBg, Muted)
            }
            if (a != null && a.escalation && a.escalationReason.isNotBlank())
                Panel(bg = Color(0xFFF7E1D3)) {
                    Text("Potential escalation", fontWeight = FontWeight.Bold, color = Critical)
                    Text(a.escalationReason, fontSize = 14.sp)
                }
            if (i.slaState == SlaState.BREACHED)
                Panel(bg = Critical) {
                    Text("SLA BREACHED", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    Text("Target ${Clock.duration(i.slaMinutes * 60_000L)} for ${Level.label(i.priority)} · waiting ${Clock.duration(i.waitedBusinessMs)}" +
                        " · over by ${Clock.duration(-i.slaRemainingMs)}", color = Color.White, fontSize = 14.sp)
                }

            // ---------------- AI summary
            Panel(bg = TealSoft) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✦ AI SUMMARY", color = Teal, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { VoiceSession.focus = id; Speaker.play(ReadAloud.summarySegments(i)) }) { Text("🔊 Summary") }
                    TextButton(onClick = { VoiceSession.focus = id; Speaker.play(ReadAloud.fullSegments(i)) }) { Text("🔊 Full") }
                    if (c.analysisState == AnalysisState.RUNNING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                when {
                    a == null && c.analysisState == AnalysisState.FAILED -> {
                        Text("AI analysis pending — ${c.analysisError}", color = UrgentText, fontSize = 14.sp)
                        OutlinedButton(onClick = { Analyzer.enqueue(id, force = true) }) { Text("Retry now") }
                    }
                    a == null -> {
                        Text(if (Settings.hasKey) "Waiting for analysis…" else "Add your Claude API key in Settings to analyse.", color = Muted, fontSize = 14.sp)
                        if (Settings.hasKey && c.analysisState != AnalysisState.RUNNING) OutlinedButton(onClick = { Analyzer.enqueue(id, force = true) }) { Text("Analyse now") }
                    }
                    else -> {
                        Text(a.summary, fontSize = 15.sp, lineHeight = 21.sp)
                        if (c.stale) Text("New messages since this analysis — updating…", fontSize = 12.sp, color = UrgentText)
                        if (c.analysisState == AnalysisState.FAILED) Text("Last update failed: ${c.analysisError}", fontSize = 12.sp, color = UrgentText)
                        if (c.detailedSummary.isNotBlank()) {
                            HorizontalDivider(color = Line)
                            Text(c.detailedSummary, fontSize = 14.sp, lineHeight = 20.sp)
                        } else {
                            TextButton(enabled = busy.isEmpty(), onClick = {
                                busy = "detail"
                                scope.launch {
                                    try {
                                        val d = AiService.detailedSummary(c)
                                        Repo.update(id) { it.copy(detailedSummary = d) }
                                    } catch (e: Exception) { toast(e.message ?: "Failed") }
                                    busy = ""
                                }
                            }) { Text(if (busy == "detail") "Writing detailed summary…" else "Detailed summary") }
                        }
                    }
                }
            }

            if (a != null) EvidenceList("Context the AI used", a.evidence, a.missingInfo, nav)

            // ---------------- action panel
            Panel {
                SectionLabel("Action")
                if (a != null) {
                    Text(a.nextAction.ifBlank { "No action required" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    KeyValue("Who responds", responderLabel(a.expectedResponder))
                    if (a.suggestedAssignee.isNotBlank()) KeyValue("Suggested owner", a.suggestedAssignee)
                    KeyValue("Type", a.messageType.replace('_', ' '))
                }
                if (i.waitingOnMe) {
                    val biz = if (Settings.businessHoursOn) " (${Clock.duration(i.waitedBusinessMs)} business)" else ""
                    KeyValue("Unanswered for", Clock.duration(i.waitedMs) + biz, Critical)
                    KeyValue("Received", Clock.stamp(i.unansweredSince))
                }
                if (i.slaState != SlaState.NONE) {
                    val rem = if (i.slaRemainingMs >= 0) "${Clock.duration(i.slaRemainingMs)} remaining" else "breached by ${Clock.duration(-i.slaRemainingMs)}"
                    KeyValue("Response SLA", "${Clock.duration(i.slaMinutes * 60_000L)} · $rem",
                        if (i.slaState == SlaState.BREACHED || i.slaState == SlaState.AT_RISK) Critical else Ink)
                }
                if (i.deadline != null && a != null) {
                    val fmt = DateTimeFormatter.ofPattern("EEE d MMM" + if (i.deadline.toLocalTime() != LocalTime.of(23, 59)) ", HH:mm" else "")
                    val tag = when (i.deadlineState) { DeadlineState.OVERDUE -> " · OVERDUE"; DeadlineState.TODAY -> " · today"; else -> "" }
                    KeyValue("Deadline", i.deadline.format(fmt) + tag + (if (a.deadlineText.isNotBlank()) " (“${a.deadlineText}”)" else "") +
                        " · ${a.deadlineConfidence}% sure", if (i.deadlineState == DeadlineState.OVERDUE || i.deadlineState == DeadlineState.TODAY) Critical else Ink)
                }
                KeyValue("Last activity", Clock.stamp(c.lastTime))
            }

            // ---------------- explanation
            if (a != null) Panel {
                SectionLabel("Why ${Level.label(i.priority)}?")
                a.reasons.forEach { Text("• $it", fontSize = 14.sp) }
                i.factors.forEach { Text("· $it", fontSize = 12.sp, color = Muted) }
                if (i.prioritySource == "manual") Text("You set this priority manually; AI won't overwrite it.", fontSize = 12.sp, color = Blue)
                if (i.prioritySource == "learned") Text("Learned: ${i.learnedNote}.", fontSize = 12.sp, color = Blue)
                if (i.needsReview) Text("⚠ Low confidence (${a.confidence}%) — please review.", fontSize = 13.sp, color = UrgentText, fontWeight = FontWeight.SemiBold)
                if (a.uncertainty.isNotBlank()) Text("AI is unsure: ${a.uncertainty}", fontSize = 13.sp, color = Muted)
            }

            // ---------------- insights
            if (a != null) Panel {
                SectionLabel("AI insights")
                ScoreBar("Importance", a.importance, Urgent)
                ScoreBar("Urgency", a.urgency, Critical)
                ScoreBar("Confidence", a.confidence, Teal)
                KeyValue("Sentiment", a.sentiment, if (a.sentiment in setOf("angry", "escalating", "frustrated")) Critical else Ink)
                KeyValue("Category", i.category)
                KeyValue("Analysed", Clock.stamp(a.analyzedAt) + " · " + a.model)
            }

            // ---------------- open questions
            if (a != null && a.questions.isNotEmpty()) Panel {
                SectionLabel("Requests & questions")
                a.questions.forEach { q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        when (q.status) {
                            "answered" -> Pill("Answered", TealSoft, Teal)
                            "partial" -> Pill("Partial", Amber, AmberText)
                            else -> Pill("Unanswered", Color(0xFFF7E1D3), UrgentText)
                        }
                    }
                }
            }

            // ---------------- commitments
            if (a != null && a.commitments.isNotEmpty()) Panel {
                SectionLabel("Commitments")
                a.commitments.forEach { cm ->
                    Column {
                        Text("${when (cm.owner) { "me" -> "You"; "them" -> c.contact; else -> "Third party" }}: ${cm.text}" +
                            (if (cm.due.isNotBlank()) " — ${cm.due}" else ""), fontSize = 14.sp)
                        Row {
                            TextButton(onClick = { remind(ctx, id, cm.text, 2) }) { Text("Remind in 2h") }
                            TextButton(onClick = { remind(ctx, id, cm.text, -1) }) { Text("Tomorrow 9:00") }
                        }
                    }
                }
            }

            // ---------------- human override
            Panel {
                SectionLabel("Your decision (overrides AI)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(c.priorityOverride.isBlank(), { Repo.overridePriority(id, "") }, { Text("Auto") }, colors = tealChip())
                    Level.all.forEach { l ->
                        FilterChip(c.priorityOverride == l, { Repo.overridePriority(id, l) }, { Text(Level.label(l)) }, colors = tealChip())
                    }
                }
                var statusMenu by remember { mutableStateOf(false) }
                var catMenu by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { statusMenu = true }) { Text("Status: " + if (c.statusOverride.isBlank()) "Auto" else Status.label(c.statusOverride)) }
                        DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                            DropdownMenuItem(text = { Text("Auto (AI)") }, onClick = { statusMenu = false; Repo.overrideStatus(id, "") })
                            Status.all.forEach { s -> DropdownMenuItem(text = { Text(Status.label(s)) }, onClick = { statusMenu = false; Repo.overrideStatus(id, s) }) }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { catMenu = true }) { Text("Category: " + c.categoryOverride.ifBlank { "Auto" }) }
                        DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                            DropdownMenuItem(text = { Text("Auto (AI)") }, onClick = { catMenu = false; Repo.overrideCategory(id, "") })
                            CATEGORIES.forEach { cat -> DropdownMenuItem(text = { Text(cat) }, onClick = { catMenu = false; Repo.overrideCategory(id, cat) }) }
                        }
                    }
                }
            }

            // ---------------- timeline
            Panel {
                SectionLabel("Timeline · ${c.messages.size} messages")
                c.messages.sortedBy { it.time }.forEach { m ->
                    val open = m.id in expanded
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (m.fromMe) TealSoft else Color.White, RoundedCornerShape(12.dp))
                            .border(1.dp, Line, RoundedCornerShape(12.dp))
                            .clickable { if (open) expanded.remove(m.id) else expanded.add(m.id) }
                            .padding(10.dp),
                    ) {
                        Row {
                            Text(if (m.fromMe) "You" else m.sender, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(Source.label(m.source) + " · " + Clock.short(m.time), fontSize = 12.sp, color = Muted)
                        }
                        Text(m.text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        if (open && m.ref.isNotBlank()) Text("View original", fontSize = 12.sp, color = Blue, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { nav.item = m.ref }.padding(top = 4.dp))
                    }
                }
                val lastIncoming = c.messages.lastOrNull()
                if (lastIncoming != null && !lastIncoming.fromMe) Text("${Clock.short(now)} — no response yet", fontSize = 12.sp, color = UrgentText, fontWeight = FontWeight.SemiBold)
            }

            // ---------------- cross-channel context
            if (related.isNotEmpty()) Panel {
                SectionLabel("Related across Outlook, Teams & Calendar")
                related.forEach { r -> ItemRow(r) { nav.item = r.id } }
            }

            // ---------------- reply helpers
            Panel {
                SectionLabel("Reply")
                if (a != null && a.suggestedReply.isNotBlank()) {
                    Text("AI suggestion", fontSize = 12.sp, color = Muted)
                    Text(a.suggestedReply, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp))
                            .border(1.dp, Teal, RoundedCornerShape(12.dp)).clickable { setDraft(a.suggestedReply) }.padding(10.dp))
                }
                Text("Draft with AI — tone", fontSize = 12.sp, color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiService.tones.forEach { t -> FilterChip(tone == t, { tone = t }, { Text(t) }, colors = tealChip()) }
                }
                OutlinedButton(enabled = busy.isEmpty() && Settings.hasKey, onClick = {
                    busy = "draft"
                    scope.launch {
                        try {
                            val d = AiService.draftReply(c, tone, a?.questions?.filter { it.status != "answered" }?.map { it.text } ?: emptyList())
                            Drafts.set(id, d.text, d.evidence, d.missing)
                        } catch (e: Exception) { toast(e.message ?: "Failed") }
                        busy = ""
                    }
                }) { Text(if (busy == "draft") "Drafting (checking history)…" else if (draft.isBlank()) "✦ Draft reply" else "✦ Regenerate") }
                Text("Ready answers", fontSize = 12.sp, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("gr" to "Ελληνικά", "en" to "English").forEach { (k, l) -> FilterChip(lang == k, { lang = k }, { Text(l) }, colors = tealChip()) }
                }
                templates.filter { it.lang == lang }.forEach { t ->
                    Text(t.text, fontSize = 14.sp, modifier = Modifier.fillMaxWidth()
                        .background(if (draft == t.text) TealSoft else Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, Line, RoundedCornerShape(12.dp)).clickable { setDraft(t.text) }.padding(10.dp))
                }
                if (i.waitingOnMe) OutlinedButton(onClick = { Repo.overrideStatus(id, Status.ANSWERED) }) { Text("Mark as answered") }
            }
            if (draft.isNotBlank()) EvidenceList("Why AI suggested this", Drafts.evidence[id] ?: emptyList(), Drafts.missing[id] ?: "", nav)
        }

        // ---------------- composer
        Column(Modifier.fillMaxWidth().background(Paper).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sendLabel.isBlank()) Text(SendActions.whyCantSend(c), fontSize = 12.sp, color = Muted)
            if (c.source == Source.OUTLOOK && sendLabel.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MailMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, { Text(m.label, fontSize = 12.sp) }, colors = tealChip()) }
                }
                if (mode == MailMode.FORWARD || mode == MailMode.NEW)
                    OutlinedTextField(toField, { toField = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("To (comma separated)") })
                if (attachments.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.toList().forEach { at -> Pill("📎 ${at.name} ✕", BlueSoft, Blue, Modifier.clickable { attachments.remove(at) }) }
                }
            }
            OutlinedTextField(value = draft, onValueChange = { setDraft(it) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                placeholder = { Text("Γράψε ή διάλεξε απάντηση… / Type or pick a reply…") })
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = draft.isNotBlank() && busy.isEmpty() && Settings.hasKey, onClick = {
                    busy = "improve"
                    scope.launch {
                        try { setDraft(AiService.improve(c, draft)) } catch (e: Exception) { toast(e.message ?: "Failed") }
                        busy = ""
                    }
                }) { Text(if (busy == "improve") "…" else "✦ Improve") }
                TextButton(enabled = draft.isNotBlank(), onClick = { Speaker.play(listOf(Segment("Draft", draft))) }) { Text("🔊") }
                TextButton(enabled = draft.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(draft.trim())); toast("Copied") }) { Text("Copy") }
                if (c.source == Source.OUTLOOK && sendLabel.isNotBlank()) TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📎") }
                Spacer(Modifier.weight(1f))
                if (sendLabel.isNotBlank()) {
                    Button(enabled = draft.isNotBlank() && !sending, onClick = {
                        val p = SendActions.prepare(c, draft.trim(), mode, toField, attachments.toList())
                        if (p == null) toast("This conversation can't be answered from the app.") else pending = p
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(sendLabel)
                    }
                } else if (!c.demo) {
                    Button(enabled = draft.isNotBlank(), onClick = {
                        clipboard.setText(AnnotatedString(draft.trim()))
                        val link = c.ext["webLink"] ?: ""
                        val opened = if (link.isNotBlank()) try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
                            else Replier.open(ctx, id)
                        Repo.log(id, "user", "reply_copied", draft.trim().take(120))
                        Toast.makeText(ctx, if (opened) "Copied — paste it in ${c.appName}" else "Copied. Open ${c.appName} to paste.", Toast.LENGTH_LONG).show()
                    }) { Text("Copy & open") }
                }
            }
        }
    }

    // ---------------- confirmation before anything is sent
    val p = pending
    if (p != null) AlertDialog(
        onDismissRequest = { if (!sending) pending = null },
        title = { Text(when (p) { is PendingSend.Mail -> "Send via Outlook?"; is PendingSend.Teams -> "Send in Teams?"; is PendingSend.Phone -> "Send via ${p.app}?" }) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (p) {
                    is PendingSend.Mail -> {
                        KeyValue("Action", p.mail.mode.label)
                        KeyValue("To", p.mail.to.joinToString(", ").ifBlank { "— (add a recipient)" }, if (p.mail.to.isEmpty()) UrgentText else Ink)
                        if (p.mail.cc.isNotEmpty()) KeyValue("Cc", p.mail.cc.joinToString(", "))
                        KeyValue("Subject", p.mail.subject)
                        KeyValue("Attachments", if (p.mail.attachments.isEmpty()) "None" else p.mail.attachments.joinToString { it.name })
                        KeyValue("Account", p.mail.account)
                    }
                    is PendingSend.Teams -> KeyValue("Chat", p.where)
                    is PendingSend.Phone -> KeyValue("To", c.contact)
                }
                HorizontalDivider(color = Line)
                Text(p.text, fontSize = 14.sp)
            }
        },
        confirmButton = {
            Button(enabled = !sending && !(p is PendingSend.Mail && p.mail.to.isEmpty()), onClick = {
                sending = true
                scope.launch {
                    try {
                        SendActions.execute(ctx, p)
                        Drafts.set(id, "", emptyList(), ""); attachments.clear(); toField = ""; mode = MailMode.REPLY
                        Toast.makeText(ctx, "Στάλθηκε · Sent", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) { toast("Not sent: ${e.message}") }
                    sending = false; pending = null
                }
            }) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = { pending = null }) { Text("Cancel") } },
    )
}

/** Creates a follow-up task and schedules its reminder. hours = -1 means tomorrow 09:00. */
private fun remind(ctx: android.content.Context, convId: String, text: String, hours: Int) {
    val due = if (hours > 0) System.currentTimeMillis() + hours * 3_600_000L
    else Clock.today().plusDays(1).atTime(9, 0).atZone(Clock.zone).toInstant().toEpochMilli()
    val t = Task("task" + System.currentTimeMillis() + "-" + (0..9999).random(), convId, text, due, false)
    Repo.addTask(t)
    ReminderWorker.schedule(ctx, t.id, due)
    Toast.makeText(ctx, "Reminder set for ${Clock.short(due)}", Toast.LENGTH_SHORT).show()
}
