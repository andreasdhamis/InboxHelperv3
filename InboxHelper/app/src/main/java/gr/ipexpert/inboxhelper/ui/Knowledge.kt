@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.ai.AiService
import gr.ipexpert.inboxhelper.ai.DraftResult
import gr.ipexpert.inboxhelper.ai.SearchAnswer
import gr.ipexpert.inboxhelper.data.Evidence
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.data.Source
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.engine.Intel
import gr.ipexpert.inboxhelper.index.Index
import gr.ipexpert.inboxhelper.index.Item
import gr.ipexpert.inboxhelper.index.Retriever
import gr.ipexpert.inboxhelper.voice.Segment
import gr.ipexpert.inboxhelper.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun BackHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column { SectionLabel(subtitle); Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** "Outlook · 18 Sep, 10:32" source badge line used everywhere. */
@Composable
fun SourceLine(source: String, time: Long, extra: String = "") {
    val color = when (source) { Source.OUTLOOK -> Color(0xFF1F5FA8); Source.TEAMS_CHAT, Source.TEAMS_CHANNEL -> Color(0xFF4B4FA8); Source.CALENDAR -> Color(0xFF8A3B12); else -> Muted }
    Text(Source.label(source) + " · " + Clock.short(time) + (if (extra.isNotBlank()) " · $extra" else ""), fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
}

@Composable
fun ItemRow(i: Item, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SourceLine(i.source, i.time, if (i.fromMe) "You" else i.senderName.ifBlank { i.senderAddr })
        if (i.subject.isNotBlank()) Text(i.subject, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(i.body, fontSize = 13.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** "Why AI suggested this" — each claim with a link to the original message. */
@Composable
fun EvidenceList(title: String, evidence: List<Evidence>, missing: String, nav: Nav) {
    if (evidence.isEmpty() && missing.isBlank()) return
    val items by produceState(emptyMap<String, Item>(), evidence) {
        value = withContext(Dispatchers.IO) { Index.byIds(evidence.map { it.ref }).associateBy { it.id } }
    }
    Panel(bg = BlueSoft) {
        Text(title, fontWeight = FontWeight.ExtraBold, color = Blue, fontSize = 13.sp)
        evidence.forEach { e ->
            val src = items[e.ref]
            Column(Modifier.fillMaxWidth().clickable { nav.item = e.ref }.padding(vertical = 3.dp)) {
                Text("• " + e.claim, fontSize = 14.sp)
                if (src != null) Text("   ${Source.label(src.source)} · ${Clock.short(src.time)} · ${src.senderName.ifBlank { src.subject }.take(40)} — View source",
                    fontSize = 12.sp, color = Blue, fontWeight = FontWeight.SemiBold)
            }
        }
        if (missing.isNotBlank()) Text("Couldn't confirm: $missing", fontSize = 13.sp, color = UrgentText)
    }
}

// ------------------------------------------------------------------ source viewer

@Composable
fun ItemScreen(id: String, nav: Nav) {
    val ctx = LocalContext.current
    val item by produceState<Item?>(null, id) { value = withContext(Dispatchers.IO) { Index.get(id) } }
    val i = item
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(i?.let { it.subject.ifBlank { Source.label(it.source) } } ?: "Source", "Original message") { nav.item = null }
        if (i == null) { Text("This item is no longer in the index (it may have been deleted in Microsoft 365).", color = Muted); return@Column }
        Panel {
            SourceLine(i.source, i.time)
            KeyValue("From", if (i.fromMe) "You" else listOf(i.senderName, i.senderAddr).filter { it.isNotBlank() }.joinToString(" · "))
            if (i.recipients.isNotBlank()) KeyValue("To / Cc", i.recipients.replace(",", ", "))
            KeyValue("Received", Clock.stamp(i.time))
            if (i.attachmentNames.isNotBlank() || i.hasAttachments) KeyValue("Attachments", i.attachmentNames.ifBlank { "yes" })
        }
        Panel { Text(i.body, fontSize = 15.sp, lineHeight = 22.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Speaker.play(listOf(Segment(i.subject.ifBlank { i.senderName }, "${i.senderName} wrote: " + AiService.clean(i.body)))) }) { Text("🔊 Read aloud") }
            if (i.webLink.isNotBlank()) OutlinedButton(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(i.webLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }) { Text("Open in ${Source.label(i.source)}") }
            val convId = "m365|" + i.threadKey
            if (Repo.get(convId) != null) OutlinedButton(onClick = { nav.item = null; nav.open(convId) }) { Text("Open conversation") }
        }
    }
}

// ------------------------------------------------------------------ cross-channel search

@Composable
fun SearchScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SearchAnswer?>(null) }
    var error by remember { mutableStateOf("") }
    fun run() {
        if (query.isBlank() || busy) return
        if (!Settings.hasKey) { error = "Add your Claude API key in Settings."; return }
        busy = true; error = ""
        scope.launch {
            try { result = AiService.crossSearch(query.trim()) } catch (e: Exception) { error = e.message ?: "Search failed" }
            busy = false
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackHeader("Search everything", "Outlook · Teams · Calendar · phone") { nav.more = "" } }
        item {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("e.g. everything about the Blue Bay firewall migration") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { run() }, enabled = query.isNotBlank() && !busy) { Text("✦ Search & summarise") }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
                if (error.isNotBlank()) Text(error, color = UrgentText, fontSize = 13.sp)
            }
        }
        val r = result
        if (r != null) {
            item {
                Panel(bg = TealSoft) {
                    val counts = r.items.groupBy { it.source }.map { "${it.value.size} ${Source.label(it.key)}" }
                    Text("Found: " + counts.joinToString(" · ").ifBlank { "nothing" }, fontWeight = FontWeight.Bold, color = Teal, fontSize = 13.sp)
                    Text(r.summary, fontSize = 15.sp, lineHeight = 21.sp)
                    TextButton(onClick = { Speaker.play(listOf(Segment("Search summary", r.summary.replace(Regex("\\[S\\d+]"), "")))) }) { Text("🔊 Read summary") }
                }
            }
            item { EvidenceList("Sources used", r.evidence, "", nav) }
            items(r.items, key = { it.id }) { i -> ItemRow(i) { nav.item = i.id } }
        }
    }
}

// ------------------------------------------------------------------ topics & entities

private data class TopicGroup(val label: String, val convs: List<Intel>)

@Composable
fun TopicsScreen(intel: List<Intel>, nav: Nav) {
    val groups = intel.filter { !it.conv.analysis?.topic.isNullOrBlank() }
        .groupBy { it.conv.analysis!!.topic.lowercase().trim() }
        .map { (_, list) -> TopicGroup(list.maxByOrNull { it.conv.lastTime }!!.conv.analysis!!.topic, list) }
        .sortedWith(compareByDescending<TopicGroup> { g -> g.convs.count { it.requiresAttention } }.thenByDescending { it.convs.size })
    val entities by produceState(emptyList<Triple<String, String, Int>>()) { value = withContext(Dispatchers.IO) { Index.topEntities(40) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackHeader("Topics & projects", "Knowledge") { nav.more = "" } }
        if (groups.isEmpty()) item { Text("Topics appear after conversations are analysed.", color = Muted, fontSize = 14.sp) }
        items(groups, key = { it.label }) { g ->
            Panel(modifier = Modifier.clickable { nav.more = "topic:" + g.label }) {
                Text(g.label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val srcs = g.convs.groupBy { it.conv.source }.map { "${it.value.size} ${Source.label(it.key)}" }.joinToString(" · ")
                Text("${g.convs.size} conversations · $srcs · ${g.convs.count { it.waitingOnMe }} waiting on you", fontSize = 13.sp, color = Muted)
            }
        }
        if (entities.isNotEmpty()) item {
            Panel {
                SectionLabel("Most mentioned")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entities.filter { it.first != "email" && it.first != "date" }.take(30).forEach { (_, name, n) ->
                        Text("$name · $n", fontSize = 12.sp, modifier = Modifier.background(Paper, RoundedCornerShape(10.dp))
                            .border(1.dp, Line, RoundedCornerShape(10.dp)).clickable { nav.more = "topic:$name" }.padding(horizontal = 8.dp, vertical = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TopicDetail(label: String, intel: List<Intel>, now: Long, nav: Nav) {
    val scope = rememberCoroutineScope()
    val convs = intel.filter { it.conv.analysis?.topic.equals(label, ignoreCase = true) }
    val entities = convs.flatMap { it.conv.analysis?.entities?.map { e -> e.name } ?: emptyList() }.distinct().take(10)
    val participants = convs.flatMap { it.conv.ext["participants"]?.split(',') ?: emptyList() }.filter { it.contains('@') }.toSet()
    val items by produceState(emptyList<Item>(), label, convs.size) {
        value = withContext(Dispatchers.IO) {
            try { Retriever.retrieve(listOf(label) + entities, participants, label, entities, emptySet(), 30).map { it.item } } catch (_: Exception) { emptyList() }
        }
    }
    var summary by remember(label) { mutableStateOf<DraftResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackHeader(label, "Topic") { nav.more = "topics" } }
        item {
            Panel {
                val counts = items.groupBy { it.source }.map { "${it.value.size} ${Source.label(it.key)}" }
                Text("Related: " + counts.joinToString(" · ").ifBlank { "—" }, fontWeight = FontWeight.SemiBold)
                if (entities.isNotEmpty()) Text("Entities: " + entities.joinToString(", "), fontSize = 13.sp, color = Muted)
                if (participants.isNotEmpty()) Text("People: ${participants.size}", fontSize = 13.sp, color = Muted)
                val s = summary
                if (s != null) {
                    Text(s.text, fontSize = 15.sp, lineHeight = 21.sp)
                } else Button(enabled = !busy && Settings.hasKey && items.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch { try { summary = AiService.topicSummary(label, items) } catch (_: Exception) { }; busy = false }
                }) { Text(if (busy) "Summarising…" else "✦ AI summary") }
            }
        }
        summary?.let { s -> item { EvidenceList("Sources used", s.evidence, s.missing, nav) } }
        if (convs.isNotEmpty()) {
            item { SectionLabel("Conversations") }
            items(convs, key = { it.conv.id }) { i -> ConversationRow(i, now) { nav.open(i.conv.id) } }
        }
        item { SectionLabel("Timeline across channels") }
        items(items.sortedByDescending { it.time }, key = { "it-" + it.id }) { i -> ItemRow(i) { nav.item = i.id } }
    }
}
