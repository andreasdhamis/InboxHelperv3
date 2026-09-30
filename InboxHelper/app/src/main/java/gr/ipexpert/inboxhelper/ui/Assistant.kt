@file:OptIn(ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.ai.AiService
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.engine.Intel
import gr.ipexpert.inboxhelper.engine.Stats
import kotlinx.coroutines.launch

private data class Turn(val fromUser: Boolean, val text: String)

/** Kept for the app session so switching tabs doesn't lose the conversation. */
private val history = mutableStateListOf<Turn>()

@Composable
fun AssistantScreen(intel: List<Intel>, nav: Nav) {
    val scope = rememberCoroutineScope()
    val tasks by Repo.tasks.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.size + 1) }

    fun ask(q: String) {
        if (q.isBlank() || busy) return
        history.add(Turn(true, q)); input = ""
        if (!Settings.hasKey) { history.add(Turn(false, "Add your Claude API key in Settings to ask questions about your inbox.")); return }
        busy = true
        scope.launch {
            val answer = try { AiService.askInbox(q, intel) } catch (e: Exception) { "Couldn't reach Claude: ${e.message}" }
            history.add(Turn(false, answer)); busy = false
        }
    }

    fun briefing(endOfDay: Boolean) {
        val now = System.currentTimeMillis()
        val facts = Stats.briefing(intel, tasks, endOfDay, now)
        history.add(Turn(true, if (endOfDay) "End-of-day summary" else "Morning briefing"))
        if (!Settings.hasKey) { history.add(Turn(false, facts)); return }
        busy = true
        scope.launch {
            val text = try { AiService.briefing(endOfDay, facts, intel) } catch (e: Exception) { facts + "\n\n(AI unavailable: ${e.message})" }
            history.add(Turn(false, text)); busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.padding(4.dp, 10.dp, 4.dp, 2.dp)) {
                    SectionLabel("Βοηθός · Assistant")
                    Text("Ask about your inbox", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Answers use only your analysed conversations (summaries and status, not full message text).", fontSize = 13.sp, color = Muted)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { briefing(false) }, enabled = !busy) { Text("Morning briefing") }
                        OutlinedButton(onClick = { briefing(true) }, enabled = !busy) { Text("End of day") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "What are the five most urgent things I need to deal with?",
                            "Who is waiting for me?",
                            "Which customers have been waiting the longest?",
                            "Which requests are approaching their deadline?",
                            "What am I waiting for from others?",
                            "Ποιες υποσχέσεις έχω δώσει και δεν έχω τηρήσει;",
                        ).forEach { q ->
                            Text(q, fontSize = 13.sp, modifier = Modifier
                                .background(Paper, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy) { ask(q) }.padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            items(history) { t ->
                Text(
                    t.text, fontSize = 15.sp, lineHeight = 21.sp,
                    color = if (t.fromUser) Color.White else Ink,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (t.fromUser) Teal else Paper, RoundedCornerShape(16.dp))
                        .border(1.dp, if (t.fromUser) Teal else Line, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                )
            }
            if (busy) item { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        }
        Row(Modifier.fillMaxWidth().background(Paper).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), maxLines = 4,
                placeholder = { Text("Ρώτα… / Ask anything about your messages") })
            Button(onClick = { ask(input.trim()) }, enabled = input.isNotBlank() && !busy) { Text("Ask") }
        }
    }
}
