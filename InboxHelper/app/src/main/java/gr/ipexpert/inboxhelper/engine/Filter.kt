package gr.ipexpert.inboxhelper.engine

import gr.ipexpert.inboxhelper.data.Level

/** Combinable inbox filter. Produced by the filter UI or by AI from a natural-language query. */
data class Filter(
    val statuses: Set<String> = emptySet(),
    val priorities: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val channels: Set<String> = emptySet(),
    val deadline: String = "",           // "" | today | week | overdue | any
    val sla: String = "",                // "" | risk | breached
    val minWaitHours: Int = 0,
    val minImportance: Int = 0,
    val minUrgency: Int = 0,
    val contact: String = "",
    val text: String = "",
    val attentionOnly: Boolean = false,
    val escalationOnly: Boolean = false,
    val showArchived: Boolean = false,
) {
    val isEmpty: Boolean get() = this == Filter()

    fun describe(): String {
        val p = mutableListOf<String>()
        if (attentionOnly) p += "needs attention"
        if (escalationOnly) p += "escalations"
        if (statuses.isNotEmpty()) p += statuses.joinToString("/") { gr.ipexpert.inboxhelper.data.Status.label(it) }
        if (priorities.isNotEmpty()) p += priorities.joinToString("/") { Level.label(it) }
        if (categories.isNotEmpty()) p += categories.joinToString("/")
        if (channels.isNotEmpty()) p += channels.joinToString("/")
        if (deadline.isNotBlank()) p += "deadline $deadline"
        if (sla.isNotBlank()) p += "SLA $sla"
        if (minWaitHours > 0) p += "waiting > ${minWaitHours}h"
        if (minImportance > 0) p += "importance ≥ $minImportance"
        if (minUrgency > 0) p += "urgency ≥ $minUrgency"
        if (contact.isNotBlank()) p += "from \"$contact\""
        if (text.isNotBlank()) p += "\"$text\""
        return p.joinToString(" · ")
    }

    fun matches(i: Intel): Boolean {
        val c = i.conv
        if (!showArchived && c.archived) return false
        if (attentionOnly && !i.requiresAttention) return false
        if (escalationOnly && c.analysis?.escalation != true) return false
        if (statuses.isNotEmpty() && i.status !in statuses) return false
        if (priorities.isNotEmpty() && i.priority !in priorities) return false
        if (categories.isNotEmpty() && i.category !in categories) return false
        if (channels.isNotEmpty() && c.channel !in channels) return false
        when (deadline) {
            "today" -> if (i.deadlineState != DeadlineState.TODAY) return false
            "week" -> if (i.deadlineState != DeadlineState.TODAY && i.deadlineState != DeadlineState.WEEK) return false
            "overdue" -> if (i.deadlineState != DeadlineState.OVERDUE) return false
            "any" -> if (i.deadline == null) return false
        }
        when (sla) {
            "risk" -> if (i.slaState != SlaState.AT_RISK && i.slaState != SlaState.APPROACHING) return false
            "breached" -> if (i.slaState != SlaState.BREACHED) return false
        }
        if (minWaitHours > 0 && i.waitedMs < minWaitHours * 3_600_000L) return false
        val a = c.analysis
        if (minImportance > 0 && (a?.importance ?: 0) < minImportance) return false
        if (minUrgency > 0 && (a?.urgency ?: 0) < minUrgency) return false
        if (contact.isNotBlank() && !c.contact.contains(contact, ignoreCase = true)) return false
        if (text.isNotBlank()) {
            val t = text.lowercase()
            val hay = buildString {
                append(c.contact).append(' ').append(c.subject).append(' ')
                append(a?.summary ?: "").append(' ').append(a?.nextAction ?: "").append(' ')
                c.messages.takeLast(10).forEach { append(it.text).append(' ') }
            }.lowercase()
            if (!hay.contains(t)) return false
        }
        return true
    }
}

enum class Sort(val label: String) {
    PRIORITY("AI priority"), IMPORTANCE("Importance"), URGENCY("Urgency"), LONGEST_WAIT("Longest unanswered"),
    SLA("SLA risk"), DEADLINE("Nearest deadline"), NEWEST("Newest"), OLDEST("Oldest"), CONTACT("Sender"), CATEGORY("Category");

    fun apply(list: List<Intel>): List<Intel> = when (this) {
        PRIORITY -> list.sortedWith(
            compareBy<Intel> { if (it.requiresAttention) 0 else 1 }.thenBy { Level.rank(it.priority) }
                .thenByDescending { it.score }.thenByDescending { it.waitedMs }
        )
        IMPORTANCE -> list.sortedByDescending { it.conv.analysis?.importance ?: -1 }
        URGENCY -> list.sortedByDescending { it.conv.analysis?.urgency ?: -1 }
        LONGEST_WAIT -> list.sortedByDescending { it.waitedMs }
        SLA -> list.sortedWith(compareByDescending<Intel> { it.slaState.ordinal }.thenBy { it.slaRemainingMs })
        DEADLINE -> list.sortedWith(compareBy<Intel> { it.deadline == null }.thenBy { it.deadline })
        NEWEST -> list.sortedByDescending { it.conv.lastTime }
        OLDEST -> list.sortedBy { it.conv.lastTime }
        CONTACT -> list.sortedBy { it.conv.contact.lowercase() }
        CATEGORY -> list.sortedBy { it.category }
    }
}
