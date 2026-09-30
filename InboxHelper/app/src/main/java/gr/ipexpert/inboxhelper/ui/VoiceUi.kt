@file:OptIn(ExperimentalLayoutApi::class)

package gr.ipexpert.inboxhelper.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.ipexpert.inboxhelper.ai.AiService
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.data.Source
import gr.ipexpert.inboxhelper.data.Status
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.engine.Filter
import gr.ipexpert.inboxhelper.engine.Intel
import gr.ipexpert.inboxhelper.engine.Sort
import gr.ipexpert.inboxhelper.engine.Stats
import gr.ipexpert.inboxhelper.voice.Drafts
import gr.ipexpert.inboxhelper.voice.Segment
import gr.ipexpert.inboxhelper.voice.Speaker

/** State of the spoken conversation with the app. */
object VoiceSession {
    var open by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var heard by mutableStateOf("")
    var reply by mutableStateOf("")
    var pending by mutableStateOf<PendingSend?>(null)
    /** Conversation the voice dialogue is about ("read it", "draft a reply", "send it"). */
    var focus by mutableStateOf<String?>(null)
}

object ReadAloud {
    fun summarySegments(i: Intel): List<Segment> {
        val c = i.conv; val a = c.analysis
        val src = Source.label(c.source)
        val text = buildString {
            append("${c.contact.ifBlank { c.appName }}, via $src. ")
            if (a != null) {
                append(a.summary).append(' ')
                append("Priority ${Level.label(i.priority)}. ")
                if (i.waitingOnMe) append("Unanswered for ${spokenDuration(i.waitedMs)}. ")
                if (a.deadlineText.isNotBlank()) append("Deadline: ${a.deadlineText}. ")
                if (a.nextAction.isNotBlank()) append("Suggested action: ${a.nextAction}.")
            } else append(c.lastMessage?.text?.take(400) ?: "")
        }
        return listOf(Segment(c.contact, text))
    }

    fun fullSegments(i: Intel): List<Segment> = i.conv.messages.sortedBy { it.time }.takeLast(12).map { m ->
        Segment((if (m.fromMe) "You" else m.sender) + " · " + Clock.short(m.time),
            (if (m.fromMe) "You wrote: " else "${m.sender} wrote: ") + gr.ipexpert.inboxhelper.ai.AiService.clean(m.text))
    }

    fun spokenDuration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 60 -> "$min minutes"
            min < 1440 -> "${min / 60} hours ${min % 60} minutes"
            else -> "${min / 1440} days ${(min % 1440) / 60} hours"
        }
    }
}

/** Executes spoken commands. Anything that sends a message only prepares it and asks for confirmation. */
object VoiceAgent {
    private fun say(text: String) { VoiceSession.reply = text; if (text.isNotBlank()) Speaker.say(text) }

    private fun resolve(target: String, intel: List<Intel>, nav: Nav): Intel? {
        val attention = Sort.PRIORITY.apply(intel.filter { it.requiresAttention })
        val t = target.trim().lowercase()
        val current = (VoiceSession.focus ?: nav.openConv)?.let { id -> intel.firstOrNull { it.conv.id == id } }
        return when {
            t.isBlank() || t == "current" || t == "it" -> current ?: attention.firstOrNull()
            t == "top" || t == "first" -> attention.firstOrNull()
            t == "next" -> { val idx = attention.indexOfFirst { it.conv.id == current?.conv?.id }; attention.getOrNull(idx + 1) ?: attention.firstOrNull() }
            else -> intel.filter { it.conv.contact.lowercase().contains(t) || it.conv.subject.lowercase().contains(t) }.maxByOrNull { it.conv.lastTime }
        }
    }

    suspend fun handle(ctx: Context, transcript: String, intel: List<Intel>, nav: Nav) {
        VoiceSession.heard = transcript; VoiceSession.busy = true; VoiceSession.open = true
        try {
            if (!Settings.hasKey) { say("Add your Claude API key in Settings to use voice commands."); return }
            val focusConv = (VoiceSession.focus ?: nav.openConv)?.let { Repo.get(it) }
            val screen = buildString {
                append(if (focusConv != null) "Open conversation: ${focusConv.contact} via ${Source.label(focusConv.source)}. " else "No conversation open. ")
                if (focusConv != null && !Drafts.text[focusConv.id].isNullOrBlank()) append("A draft reply exists. ")
                if (VoiceSession.pending != null) append("A send is waiting for confirmation. ")
            }
            val cmd = AiService.voiceCommand(transcript, screen)
            val ack = cmd.say
            when (cmd.action) {
                "show_unanswered" -> {
                    nav.showInbox(Filter(statuses = Status.waitingOnMe), Sort.LONGEST_WAIT)
                    val list = intel.filter { it.waitingOnMe }.sortedByDescending { it.waitedMs }
                    say(if (list.isEmpty()) "Nothing is waiting for your answer." else "${list.size} unanswered. The oldest is ${list.first().conv.contact}, waiting ${ReadAloud.spokenDuration(list.first().waitedMs)}.")
                }
                "show_waiting_on_others" -> {
                    nav.showInbox(Filter(statuses = Status.waitingOnOthers), Sort.OLDEST)
                    say("You are waiting on ${intel.count { it.waitingOnOthers }} conversations.")
                }
                "show_urgent" -> {
                    nav.showInbox(Filter(priorities = setOf(Level.CRITICAL, Level.HIGH), attentionOnly = true))
                    val top = Sort.PRIORITY.apply(intel.filter { it.requiresAttention }).take(3)
                    say(if (top.isEmpty()) "Nothing urgent right now." else "You have ${top.size} top items. The most urgent: " + ReadAloud.summarySegments(top.first()).first().text)
                    top.firstOrNull()?.let { VoiceSession.focus = it.conv.id }
                }
                "show_deadlines" -> { nav.showInbox(Filter(deadline = "week"), Sort.DEADLINE); say(ack.ifBlank { "Here are this week's deadlines." }) }
                "search" -> {
                    val f = AiService.parseSearch(cmd.text.ifBlank { transcript })
                    nav.showInbox(f)
                    say("${intel.count { f.matches(it) }} conversations match.")
                }
                "briefing" -> {
                    val facts = Stats.briefing(intel, Repo.tasks.value, false, System.currentTimeMillis())
                    say(AiService.briefing(false, facts, intel))
                }
                "read_summary", "open", "summarize_contact" -> {
                    val i = resolve(cmd.target, intel, nav)
                    if (i == null) say("I couldn't find that conversation.") else {
                        VoiceSession.focus = i.conv.id
                        if (cmd.action == "open" || cmd.action == "read_summary") nav.open(i.conv.id)
                        VoiceSession.reply = ReadAloud.summarySegments(i).first().text
                        Speaker.play(ReadAloud.summarySegments(i))
                    }
                }
                "read_full" -> {
                    val i = resolve(cmd.target, intel, nav)
                    if (i == null) say("I couldn't find that conversation.") else {
                        VoiceSession.focus = i.conv.id; nav.open(i.conv.id)
                        VoiceSession.reply = "Reading the conversation with ${i.conv.contact}."
                        Speaker.play(ReadAloud.fullSegments(i))
                    }
                }
                "draft_reply" -> {
                    val i = resolve("current", intel, nav)
                    if (i == null) say("Open a conversation first.") else {
                        VoiceSession.focus = i.conv.id; nav.open(i.conv.id)
                        val open = i.conv.analysis?.questions?.filter { it.status != "answered" }?.map { it.text } ?: emptyList()
                        val d = AiService.draftReply(i.conv, cmd.tone.ifBlank { "Professional" }, open, cmd.text)
                        Drafts.set(i.conv.id, d.text, d.evidence, d.missing)
                        say(if (d.missing.isNotBlank()) "Draft ready. Note: ${d.missing}" else "Draft ready. Say read it, change it, or send it.")
                    }
                }
                "revise_draft" -> {
                    val id = VoiceSession.focus ?: nav.openConv
                    val c = id?.let { Repo.get(it) }
                    val draft = id?.let { Drafts.text[it] }
                    if (c == null || draft.isNullOrBlank()) say("There is no draft to change yet.") else {
                        Drafts.set(c.id, AiService.revise(c, draft, cmd.text.ifBlank { transcript }))
                        say("Updated. Say read it to hear it.")
                    }
                }
                "read_draft" -> {
                    val d = (VoiceSession.focus ?: nav.openConv)?.let { Drafts.text[it] }
                    if (d.isNullOrBlank()) say("There is no draft yet.") else { VoiceSession.reply = d; Speaker.play(listOf(Segment("Draft", d))) }
                }
                "send" -> {
                    val id = VoiceSession.focus ?: nav.openConv
                    val c = id?.let { Repo.get(it) }
                    val draft = id?.let { Drafts.text[it] }
                    val p = if (c != null && !draft.isNullOrBlank()) SendActions.prepare(c, draft) else null
                    if (p == null) say(if (draft.isNullOrBlank()) "There is no draft to send." else "This conversation can't be answered from the app.")
                    else {
                        VoiceSession.pending = p
                        val who = when (p) { is PendingSend.Mail -> p.mail.to.joinToString(); is PendingSend.Teams -> p.where; is PendingSend.Phone -> c?.contact ?: "" }
                        val via = when (p) { is PendingSend.Mail -> "your Microsoft 365 account"; is PendingSend.Teams -> "Teams"; is PendingSend.Phone -> p.app }
                        say("Send this reply to $who via $via? Say yes to send, or tap Send.")
                    }
                }
                "confirm" -> {
                    val p = VoiceSession.pending
                    if (p == null) say("There is nothing waiting to be sent.") else {
                        SendActions.execute(ctx, p)
                        Drafts.set(p.convId, "")
                        VoiceSession.pending = null
                        say("Sent.")
                    }
                }
                "cancel" -> { VoiceSession.pending = null; Speaker.stop(); say("Cancelled.") }
                "mark_priority" -> {
                    val id = VoiceSession.focus ?: nav.openConv
                    if (id == null || cmd.level !in Level.all) say("Open a conversation first.") else { Repo.overridePriority(id, cmd.level); say("Marked ${Level.label(cmd.level)}.") }
                }
                "mark_answered" -> { (VoiceSession.focus ?: nav.openConv)?.let { Repo.overrideStatus(it, Status.ANSWERED); say("Marked as answered.") } ?: say("Open a conversation first.") }
                "archive" -> { (VoiceSession.focus ?: nav.openConv)?.let { Repo.archive(it, true); nav.openConv = null; say("Archived.") } ?: say("Open a conversation first.") }
                "remind" -> {
                    val id = VoiceSession.focus ?: nav.openConv ?: ""
                    val due = SendActions.parseWhen(cmd.whenText)
                    val what = cmd.text.ifBlank { Repo.get(id)?.let { "Follow up with ${it.contact}" } ?: "Follow up" }
                    SendActions.remind(ctx, id, what, due)
                    say("Reminder set for ${Clock.short(due)}.")
                }
                "stop_reading" -> { Speaker.stop(); VoiceSession.reply = "Stopped." }
                "help" -> say("Try: what's urgent, read it, draft a reply saying we can do Friday, make it more professional, send it, remind me tomorrow, what am I waiting for.")
                else -> say(AiService.askInbox(cmd.text.ifBlank { transcript }, intel))
            }
        } catch (e: Exception) {
            say("Sorry, that didn't work: ${e.message}")
        } finally { VoiceSession.busy = false }
    }
}

@Composable
fun VoicePanel(onMic: () -> Unit, onConfirm: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎤 ASSISTANT", color = Color(0xFF7FD3C9), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { VoiceSession.open = false; VoiceSession.pending = null }) { Text("Close", color = Color.White) }
        }
        if (VoiceSession.heard.isNotBlank()) Text("“${VoiceSession.heard}”", color = Color(0xFFBDB8AE), fontSize = 14.sp)
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            if (VoiceSession.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.size(8.dp)); Text("Working…", color = Color.White)
            }
            if (VoiceSession.reply.isNotBlank()) Text(VoiceSession.reply, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
        }
        val p = VoiceSession.pending
        if (p != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm) { Text("Send") }
            OutlinedButton(onClick = { VoiceSession.pending = null; VoiceSession.reply = "Cancelled." }) { Text("Cancel", color = Color.White) }
        }
        Button(onClick = onMic, enabled = !VoiceSession.busy, modifier = Modifier.fillMaxWidth()) { Text("🎤  Speak") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("What's urgent?", "Read it", "What am I waiting for?").forEach {
                Text(it, color = Color(0xFFBDB8AE), fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun PlayerBar() {
    val s by Speaker.state.collectAsState()
    if (!s.active) return
    Column(Modifier.fillMaxWidth().background(TealSoft).border(1.dp, Line).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("🔊 ${s.title}  (${s.index + 1}/${s.total})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Teal, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { Speaker.previous() }) { Text("⏪") }
            if (s.playing) TextButton(onClick = { Speaker.pause() }) { Text("⏸") } else TextButton(onClick = { Speaker.resume() }) { Text("▶") }
            TextButton(onClick = { Speaker.stop() }) { Text("⏹") }
            TextButton(onClick = { Speaker.next() }) { Text("⏩") }
            Spacer(Modifier.weight(1f))
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                Text(if (r == 1f) "1×" else "${r}×", fontSize = 12.sp, fontWeight = if (s.rate == r) FontWeight.ExtraBold else FontWeight.Normal,
                    color = if (s.rate == r) Teal else Muted,
                    modifier = Modifier.clickable { Speaker.setRate(r) }.padding(horizontal = 4.dp, vertical = 8.dp))
            }
        }
    }
}
