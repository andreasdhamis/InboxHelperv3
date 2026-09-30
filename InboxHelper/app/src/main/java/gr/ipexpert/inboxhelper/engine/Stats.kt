package gr.ipexpert.inboxhelper.engine

import gr.ipexpert.inboxhelper.data.Conversation
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Status
import gr.ipexpert.inboxhelper.data.Task
import java.time.Instant

data class DashboardCounts(
    val open: Int, val attention: Int, val unanswered: Int, val slaApproaching: Int, val slaAtRisk: Int,
    val slaBreached: Int, val critical: Int, val high: Int, val waitingOthers: Int, val dueToday: Int,
    val dueWeek: Int, val overdue: Int, val escalations: Int, val pendingAnalysis: Int, val over48h: Int,
)

data class ResponseStats(
    val count: Int, val avgMs: Long, val medianMs: Long, val avgFirstMs: Long,
    val waitingOnMeMs: Long, val waitingOnOthersMs: Long, val withinSlaPct: Int,
)

data class ContactStats(
    val name: String, val conversations: List<Intel>, val open: Int, val unanswered: Int,
    val avgResponseMs: Long, val slaBreaches: Int, val commitments: List<String>,
)

object Stats {

    fun counts(all: List<Intel>): DashboardCounts {
        val live = all.filter { !it.conv.archived }
        val openList = live.filter { it.status != Status.COMPLETED && it.status != Status.NO_ACTION && it.status != Status.ANSWERED }
        return DashboardCounts(
            open = openList.size,
            attention = live.count { it.requiresAttention },
            unanswered = live.count { it.waitingOnMe },
            slaApproaching = live.count { it.slaState == SlaState.APPROACHING },
            slaAtRisk = live.count { it.slaState == SlaState.AT_RISK || it.slaState == SlaState.APPROACHING },
            slaBreached = live.count { it.slaState == SlaState.BREACHED },
            critical = openList.count { it.priority == Level.CRITICAL },
            high = openList.count { it.priority == Level.HIGH },
            waitingOthers = live.count { it.waitingOnOthers },
            dueToday = openList.count { it.deadlineState == DeadlineState.TODAY },
            dueWeek = openList.count { it.deadlineState == DeadlineState.TODAY || it.deadlineState == DeadlineState.WEEK },
            overdue = openList.count { it.deadlineState == DeadlineState.OVERDUE },
            escalations = live.count { it.conv.analysis?.escalation == true && it.status != Status.COMPLETED },
            pendingAnalysis = live.count { it.conv.analysis == null || it.conv.stale },
            over48h = live.count { it.waitingOnMe && it.waitedMs > 48 * 3_600_000L },
        )
    }

    /** The "don't make me read everything" sentence, built only from real data. */
    fun headline(all: List<Intel>): String {
        val c = counts(all)
        if (c.open == 0 && c.attention == 0) return "Nothing needs you right now. Όλα υπό έλεγχο."
        val parts = mutableListOf<String>()
        if (c.critical > 0) parts += "${c.critical} critical"
        if (c.slaAtRisk > 0) parts += "${c.slaAtRisk} approaching SLA"
        if (c.slaBreached > 0) parts += "${c.slaBreached} past SLA"
        if (c.dueToday > 0) parts += "${c.dueToday} due today"
        val oldest = all.filter { it.waitingOnMe }.maxByOrNull { it.waitedMs }
        val tail = if (oldest != null) " The longest wait is ${oldest.conv.contact} — ${Clock.duration(oldest.waitedMs)}." else ""
        val mid = if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
        return "You have ${c.open} open conversations, but only ${c.attention} need your attention$mid.$tail"
    }

    /** Response time pairs: first incoming message after my last reply → my next reply. */
    private fun responseTimes(c: Conversation): List<Long> {
        val out = mutableListOf<Long>()
        var pendingSince = -1L
        c.messages.sortedBy { it.time }.forEach { m ->
            if (!m.fromMe && pendingSince < 0) pendingSince = m.time
            if (m.fromMe && pendingSince >= 0) { out += (m.time - pendingSince); pendingSince = -1 }
        }
        return out
    }

    /** Time the conversation waited on me vs on the other side (from message order). */
    private fun waitSplit(c: Conversation, now: Long): Pair<Long, Long> {
        var onMe = 0L; var onThem = 0L
        val ms = c.messages.sortedBy { it.time }
        for (i in ms.indices) {
            val end = if (i + 1 < ms.size) ms[i + 1].time else now
            val span = (end - ms[i].time).coerceAtLeast(0)
            if (ms[i].fromMe) onThem += span else onMe += span
        }
        return onMe to onThem
    }

    fun responses(all: List<Intel>, now: Long, sinceMs: Long): ResponseStats {
        val convs = all.map { it.conv }.filter { it.lastTime >= sinceMs }
        val times = convs.flatMap { responseTimes(it) }.sorted()
        val firsts = convs.mapNotNull { responseTimes(it).firstOrNull() }
        var onMe = 0L; var onThem = 0L
        convs.forEach { val (a, b) = waitSplit(it, now); onMe += a; onThem += b }
        val within = all.filter { it.conv.lastTime >= sinceMs }.sumOf { i ->
            val limit = i.slaMinutes * 60_000L
            if (limit <= 0) 0 else responseTimes(i.conv).count { it <= limit }
        }
        return ResponseStats(
            count = times.size,
            avgMs = if (times.isEmpty()) 0 else times.sum() / times.size,
            medianMs = if (times.isEmpty()) 0 else times[times.size / 2],
            avgFirstMs = if (firsts.isEmpty()) 0 else firsts.sum() / firsts.size,
            waitingOnMeMs = onMe, waitingOnOthersMs = onThem,
            withinSlaPct = if (times.isEmpty()) 0 else (within * 100 / times.size),
        )
    }

    fun byKey(all: List<Intel>, key: (Intel) -> String): List<Pair<String, Int>> =
        all.groupBy(key).map { it.key to it.value.size }.sortedByDescending { it.second }

    /** Incoming messages per day for the last [days] days (oldest first). */
    fun perDay(all: List<Intel>, days: Int): List<Pair<String, Int>> {
        val today = Clock.today()
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val n = all.sumOf { i -> i.conv.messages.count { !it.fromMe && Instant.ofEpochMilli(it.time).atZone(Clock.zone).toLocalDate() == d } }
            d.dayOfWeek.name.take(3) to n
        }
    }

    fun contacts(all: List<Intel>): List<ContactStats> = all.groupBy { it.conv.contact.ifBlank { it.conv.appName } }.map { (name, list) ->
        val times = list.flatMap { responseTimes(it.conv) }
        ContactStats(
            name = name,
            conversations = list.sortedByDescending { it.conv.lastTime },
            open = list.count { it.status !in setOf(Status.COMPLETED, Status.NO_ACTION, Status.ANSWERED) },
            unanswered = list.count { it.waitingOnMe },
            avgResponseMs = if (times.isEmpty()) 0 else times.sum() / times.size,
            slaBreaches = list.count { it.slaState == SlaState.BREACHED },
            commitments = list.flatMap { i -> i.conv.analysis?.commitments?.map { "${it.text}${if (it.due.isNotBlank()) " — ${it.due}" else ""}" } ?: emptyList() },
        )
    }.sortedWith(compareByDescending<ContactStats> { it.unanswered }.thenByDescending { it.open })

    /** Local (no AI) briefing text, used for notifications and as the base of the AI briefing. */
    fun briefing(all: List<Intel>, tasks: List<Task>, endOfDay: Boolean, now: Long): String {
        val c = counts(all)
        val sb = StringBuilder()
        val crit = all.filter { it.priority == Level.CRITICAL && it.requiresAttention }
        val oldest = all.filter { it.waitingOnMe }.maxByOrNull { it.waitedMs }
        if (!endOfDay) {
            sb.append("Good morning. ${c.attention} conversations need your attention.\n")
            if (crit.isNotEmpty()) sb.append("Critical: ").append(crit.joinToString("; ") { "${it.conv.contact} — ${it.conv.analysis?.summary ?: ""} (waiting ${Clock.duration(it.waitedMs)})" }).append('\n')
            sb.append("High priority: ${c.high} · SLA at risk: ${c.slaAtRisk} · Breached: ${c.slaBreached}\n")
            if (oldest != null) sb.append("Longest unanswered: ${oldest.conv.contact} — ${Clock.duration(oldest.waitedMs)}\n")
            sb.append("Deadlines today: ${c.dueToday} · Waiting for others: ${c.waitingOthers}")
        } else {
            val startOfDay = Clock.today().atStartOfDay(Clock.zone).toInstant().toEpochMilli()
            val repliedToday = all.count { i -> i.conv.messages.any { it.fromMe && it.time >= startOfDay } }
            val tomorrow = Clock.today().plusDays(1)
            val dueTomorrow = all.count { it.deadline?.toLocalDate() == tomorrow && it.status != Status.COMPLETED }
            sb.append("You replied in $repliedToday conversations today. ${c.unanswered} requests remain unanswered")
            sb.append(" and $dueTomorrow deadlines are due tomorrow.\n")
            if (c.slaBreached > 0) sb.append("SLA breaches open: ${c.slaBreached}\n")
            sb.append("Waiting for others: ${c.waitingOthers}\n")
            val openTasks = tasks.filter { !it.done && it.dueAt < now + 86_400_000L }
            if (openTasks.isNotEmpty()) sb.append("Follow-ups for tomorrow: ").append(openTasks.joinToString("; ") { it.text })
        }
        return sb.toString().trim()
    }
}
