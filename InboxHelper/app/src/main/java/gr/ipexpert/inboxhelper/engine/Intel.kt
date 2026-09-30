package gr.ipexpert.inboxhelper.engine

import gr.ipexpert.inboxhelper.data.Conversation
import gr.ipexpert.inboxhelper.data.Correction
import gr.ipexpert.inboxhelper.data.Level
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.data.Status
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Time helpers, including optional business-hours arithmetic. */
object Clock {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val stampFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

    fun stamp(ms: Long): String = stampFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun short(ms: Long): String = shortFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun duration(ms: Long): String {
        val min = max(0L, ms) / 60_000
        return when {
            min < 1 -> "<1m"
            min < 60 -> "${min}m"
            min < 1440 -> "${min / 60}h ${min % 60}m"
            min < 7 * 1440 -> "${min / 1440}d ${(min % 1440) / 60}h"
            else -> "${min / (7 * 1440)}w ${(min % (7 * 1440)) / 1440}d"
        }
    }

    private fun isWorkDay(d: LocalDate): Boolean =
        d.dayOfWeek.value in Settings.workDays && d.toString() !in Settings.holidays

    /** Milliseconds between two instants, counting only working hours when business hours are enabled. */
    fun elapsed(from: Long, to: Long): Long {
        if (to <= from) return 0
        if (!Settings.businessHoursOn) return to - from
        val startMin = Settings.workStartMin
        val endMin = Settings.workEndMin
        if (endMin <= startMin) return to - from
        var total = 0L
        var day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        var guard = 0
        while (!day.isAfter(lastDay) && guard < 400) {
            if (isWorkDay(day)) {
                val ws = day.atTime(LocalTime.of(startMin / 60, startMin % 60)).atZone(zone).toInstant().toEpochMilli()
                val we = if (endMin >= 24 * 60) day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                else day.atTime(LocalTime.of(endMin / 60, endMin % 60)).atZone(zone).toInstant().toEpochMilli()
                val s = max(ws, from)
                val e = min(we, to)
                if (e > s) total += e - s
            }
            day = day.plusDays(1); guard++
        }
        return total
    }

    fun today(): LocalDate = LocalDate.now(zone)
}

enum class SlaState(val label: String) {
    NONE("No SLA"), WITHIN("Within SLA"), APPROACHING("Approaching SLA"), AT_RISK("SLA at risk"), BREACHED("SLA breached")
}

enum class DeadlineState { NONE, OVERDUE, TODAY, WEEK, LATER }

/** Everything the UI needs about one conversation, derived from stored data + AI analysis + user overrides. */
data class Intel(
    val conv: Conversation,
    val status: String,
    val statusIsManual: Boolean,
    val waitingOnMe: Boolean,
    val waitingOnOthers: Boolean,
    val unansweredSince: Long,         // 0 when not waiting on me
    val waitedMs: Long,                // wall-clock
    val waitedBusinessMs: Long,        // business time (== waitedMs when business hours are off)
    val priority: String,
    val prioritySource: String,        // manual | learned | ai | pending
    val score: Int,
    val factors: List<String>,
    val slaMinutes: Int,
    val slaState: SlaState,
    val slaRemainingMs: Long,
    val deadline: LocalDateTime?,
    val deadlineState: DeadlineState,
    val category: String,
    val requiresAttention: Boolean,
    val needsReview: Boolean,
    val learnedNote: String,
)

object IntelEngine {

    private fun parseDeadline(iso: String): LocalDateTime? {
        if (iso.isBlank()) return null
        return try {
            if (iso.length <= 10) LocalDate.parse(iso).atTime(23, 59) else LocalDateTime.parse(iso.take(16))
        } catch (_: Exception) { null }
    }

    /** Learned priority for a contact: at least 2 identical corrections and none contradicting the latest. */
    fun learnedPriority(contact: String, corrections: List<Correction>): Pair<String, Int>? {
        if (!Settings.learningEnabled || contact.isBlank()) return null
        val mine = corrections.filter { it.contact == contact && it.field == "priority" }
        if (mine.size < 2) return null
        val latest = mine.first().userValue
        val same = mine.takeWhile { it.userValue == latest }.size
        return if (same >= 2) latest to same else null
    }

    fun compute(c: Conversation, now: Long, corrections: List<Correction>): Intel {
        val a = c.analysis
        val last = c.lastMessage
        val lastMine = c.messages.lastOrNull { it.fromMe }
        val firstIncomingAfterMine = c.messages.firstOrNull { !it.fromMe && (lastMine == null || it.time > lastMine.time) }

        // ---- status: manual > (new incoming after analysis => unanswered) > AI > local heuristic
        val localStatus = if (last != null && !last.fromMe) Status.UNANSWERED else Status.WAITING_CUSTOMER
        val status = when {
            c.statusOverride.isNotBlank() -> c.statusOverride
            a == null -> localStatus
            c.stale && last != null && !last.fromMe -> Status.UNANSWERED
            c.stale && last != null && last.fromMe -> Status.WAITING_CUSTOMER
            else -> a.status
        }
        val waitingOnMe = status in Status.waitingOnMe && !c.archived
        val waitingOnOthers = status in Status.waitingOnOthers && !c.archived

        val since = if (waitingOnMe) (firstIncomingAfterMine?.time ?: c.messages.firstOrNull { !it.fromMe }?.time ?: c.createdAt) else 0L
        val waited = if (waitingOnMe) now - since else 0L
        val waitedBiz = if (waitingOnMe) Clock.elapsed(since, now) else 0L

        // ---- deadline
        val deadline = a?.let { parseDeadline(it.deadlineIso) }
        val today = Clock.today()
        val deadlineState = when {
            deadline == null -> DeadlineState.NONE
            deadline.isBefore(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), Clock.zone)) -> DeadlineState.OVERDUE
            deadline.toLocalDate() == today -> DeadlineState.TODAY
            !deadline.toLocalDate().isAfter(today.with(DayOfWeek.SUNDAY)) -> DeadlineState.WEEK
            else -> DeadlineState.LATER
        }

        // ---- priority score: importance + urgency, raised by waiting time, deadline and escalation
        val factors = mutableListOf<String>()
        var score = 0
        var computed = Level.MEDIUM
        if (a != null) {
            score = (a.importance * 0.5 + a.urgency * 0.5).toInt()
            factors += "Importance ${a.importance}/100, urgency ${a.urgency}/100 → base $score"
            if (waitingOnMe) {
                val hours = waitedBiz / 3_600_000.0
                val boost = min(15, (hours * 1.5).toInt())
                if (boost > 0) { score += boost; factors += "Waiting on you ${Clock.duration(waited)} (+$boost)" }
            }
            if (waitingOnMe && deadlineState == DeadlineState.OVERDUE) { score += 15; factors += "Deadline passed (+15)" }
            else if (waitingOnMe && deadlineState == DeadlineState.TODAY) { score += 10; factors += "Deadline today (+10)" }
            if (a.escalation) { score += 10; factors += "Escalation signs (+10)" }
            score = score.coerceIn(0, 100)
            computed = when {
                !waitingOnMe && a.messageType in setOf("notification", "automated", "fyi") -> Level.INFO
                status == Status.NO_ACTION || status == Status.COMPLETED -> if (score >= 65) Level.LOW else Level.INFO
                score >= 85 -> Level.CRITICAL
                score >= 65 -> Level.HIGH
                score >= 40 -> Level.MEDIUM
                else -> Level.LOW
            }
            // Never rank below the AI's own judgement when the conversation still needs me.
            if (waitingOnMe && Level.rank(a.aiPriority) < Level.rank(computed)) {
                factors += "AI rated it ${Level.label(a.aiPriority)}"
                computed = a.aiPriority
            }
        }

        val learned = learnedPriority(c.contact, corrections)
        val (priority, source) = when {
            c.priorityOverride.isNotBlank() -> c.priorityOverride to "manual"
            learned != null && a != null -> learned.first to "learned"
            a == null -> Level.MEDIUM to "pending"
            else -> computed to "ai"
        }
        val learnedNote = if (learned != null) "You changed ${learned.second} conversations from ${c.contact} to ${Level.label(learned.first)}" else ""

        // ---- SLA (only while the conversation is waiting on me)
        val slaMin = Settings.slaMinutes(priority)
        val (slaState, remaining) = if (!waitingOnMe || slaMin <= 0) SlaState.NONE to 0L else {
            val limit = slaMin * 60_000L
            val used = waitedBiz
            val ratio = used.toDouble() / limit
            val st = when {
                ratio >= 1.0 -> SlaState.BREACHED
                ratio >= 0.8 -> SlaState.AT_RISK
                ratio >= 0.5 -> SlaState.APPROACHING
                else -> SlaState.WITHIN
            }
            st to (limit - used)
        }

        val category = c.categoryOverride.ifBlank { a?.category ?: "other" }
        val attention = !c.archived && (waitingOnMe || a?.escalation == true ||
            (deadline != null && (deadlineState == DeadlineState.TODAY || deadlineState == DeadlineState.OVERDUE) && status != Status.COMPLETED))
        val review = a != null && a.confidence < Settings.confidenceThreshold

        return Intel(
            c, status, c.statusOverride.isNotBlank(), waitingOnMe, waitingOnOthers, since, waited, waitedBiz,
            priority, source, score, factors, slaMin, slaState, remaining, deadline, deadlineState,
            category, attention, review, learnedNote,
        )
    }

    fun computeAll(list: List<Conversation>, now: Long, corrections: List<Correction>): List<Intel> =
        list.map { compute(it, now, corrections) }
}
