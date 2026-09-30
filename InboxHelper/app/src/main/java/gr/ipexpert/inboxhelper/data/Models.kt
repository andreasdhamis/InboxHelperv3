package gr.ipexpert.inboxhelper.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Domain model. Stored as JSON files in the app's private storage (see Repo).
 * Decision: a JSON store instead of SQLite/Room keeps the app dependency-light; a phone inbox holds
 * hundreds of conversations, well within what an in-memory list handles. Repo is the only class that
 * touches storage, so moving to Room later is local to that file.
 */

object Status {
    const val UNANSWERED = "unanswered"
    const val PARTIAL = "partially_answered"
    const val ANSWERED = "answered"
    const val WAITING_CUSTOMER = "waiting_customer"
    const val WAITING_THIRD = "waiting_third_party"
    const val NO_ACTION = "no_action"
    const val COMPLETED = "completed"
    val all = listOf(UNANSWERED, PARTIAL, ANSWERED, WAITING_CUSTOMER, WAITING_THIRD, NO_ACTION, COMPLETED)
    val waitingOnMe = setOf(UNANSWERED, PARTIAL)
    val waitingOnOthers = setOf(WAITING_CUSTOMER, WAITING_THIRD)
    fun label(s: String) = when (s) {
        UNANSWERED -> "Unanswered"
        PARTIAL -> "Partially answered"
        ANSWERED -> "Answered"
        WAITING_CUSTOMER -> "Waiting for them"
        WAITING_THIRD -> "Waiting for third party"
        NO_ACTION -> "No action"
        COMPLETED -> "Completed"
        else -> "Pending analysis"
    }
}

object Level {
    const val CRITICAL = "critical"
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"
    const val INFO = "informational"
    val all = listOf(CRITICAL, HIGH, MEDIUM, LOW, INFO)
    fun rank(l: String) = when (l) { CRITICAL -> 0; HIGH -> 1; MEDIUM -> 2; LOW -> 3; INFO -> 4; else -> 2 }
    fun label(l: String) = when (l) {
        CRITICAL -> "Critical"; HIGH -> "High"; MEDIUM -> "Medium"; LOW -> "Low"; INFO -> "Info"; else -> "—"
    }
}

val CATEGORIES = listOf(
    "customer", "sales", "support", "technical", "finance", "billing", "vendor", "procurement",
    "management", "hr", "security", "infrastructure", "project", "complaint", "request",
    "notification", "marketing", "personal", "spam", "other",
)

/** Where a communication item came from. Shown on every item ("Outlook · 10:32"). */
object Source {
    const val OUTLOOK = "outlook"
    const val TEAMS_CHAT = "teams_chat"
    const val TEAMS_CHANNEL = "teams_channel"
    const val CALENDAR = "calendar"
    const val PHONE = "phone"
    const val NOTE = "note"
    fun label(s: String) = when (s) {
        OUTLOOK -> "Outlook"; TEAMS_CHAT -> "Teams"; TEAMS_CHANNEL -> "Teams channel"; CALENDAR -> "Calendar"; NOTE -> "Note"; else -> "Phone"
    }
}

data class ChatMessage(
    val id: String,
    val sender: String,
    val fromMe: Boolean,
    val text: String,
    val time: Long,
    val source: String = Source.PHONE,   // outlook | teams_chat | teams_channel | calendar | phone | note
    val ref: String = "",                // index item id (for "View source")
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("sender", sender).put("fromMe", fromMe)
        .put("text", text).put("time", time).put("source", source).put("ref", ref)

    companion object {
        fun fromJson(o: JSONObject) = ChatMessage(
            o.getString("id"), o.optString("sender"), o.optBoolean("fromMe"), o.optString("text"), o.optLong("time"),
            o.optString("source", Source.PHONE), o.optString("ref"),
        )
        fun makeId(sender: String, text: String, time: Long): String =
            Integer.toHexString("$time|$sender|$text".hashCode()) + "-" + (time % 100000)
    }
}

data class Question(val text: String, val status: String) // unanswered | partial | answered

data class Entity(val type: String, val name: String)

/** A claim the AI made, backed by an indexed source item. */
data class Evidence(val ref: String, val claim: String)

data class Commitment(val text: String, val owner: String, val due: String) // owner: me | them | third_party

data class Analysis(
    val summary: String,
    val status: String,
    val expectedResponder: String,       // me | them | third_party | unknown
    val messageType: String,             // new_request | follow_up | reminder | escalation | fyi | notification | automated | conversation
    val importance: Int,
    val urgency: Int,
    val aiPriority: String,
    val confidence: Int,
    val deadlineIso: String,             // "" or yyyy-MM-dd or yyyy-MM-ddTHH:mm (local time)
    val deadlineText: String,
    val deadlineConfidence: Int,
    val category: String,
    val sentiment: String,
    val escalation: Boolean,
    val escalationReason: String,
    val questions: List<Question>,
    val nextAction: String,
    val suggestedAssignee: String,
    val commitments: List<Commitment>,
    val reasons: List<String>,
    val uncertainty: String,
    val suggestedReply: String,
    val language: String,
    val analyzedAt: Long,
    val model: String,
    val topic: String = "",
    val entities: List<Entity> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val missingInfo: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("summary", summary).put("status", status).put("expectedResponder", expectedResponder)
        .put("messageType", messageType).put("importance", importance).put("urgency", urgency)
        .put("aiPriority", aiPriority).put("confidence", confidence).put("deadlineIso", deadlineIso)
        .put("deadlineText", deadlineText).put("deadlineConfidence", deadlineConfidence)
        .put("category", category).put("sentiment", sentiment).put("escalation", escalation)
        .put("escalationReason", escalationReason)
        .put("questions", JSONArray().also { a -> questions.forEach { a.put(JSONObject().put("text", it.text).put("status", it.status)) } })
        .put("nextAction", nextAction).put("suggestedAssignee", suggestedAssignee)
        .put("commitments", JSONArray().also { a -> commitments.forEach { a.put(JSONObject().put("text", it.text).put("owner", it.owner).put("due", it.due)) } })
        .put("reasons", JSONArray(reasons)).put("uncertainty", uncertainty)
        .put("suggestedReply", suggestedReply).put("language", language)
        .put("analyzedAt", analyzedAt).put("model", model)
        .put("topic", topic).put("missingInfo", missingInfo)
        .put("entities", JSONArray().also { a -> entities.forEach { a.put(JSONObject().put("type", it.type).put("name", it.name)) } })
        .put("evidence", JSONArray().also { a -> evidence.forEach { a.put(JSONObject().put("ref", it.ref).put("claim", it.claim)) } })

    companion object {
        fun fromJson(o: JSONObject): Analysis {
            val es = o.optJSONArray("entities") ?: JSONArray()
            val ev = o.optJSONArray("evidence") ?: JSONArray()
            val qs = o.optJSONArray("questions") ?: JSONArray()
            val cs = o.optJSONArray("commitments") ?: JSONArray()
            val rs = o.optJSONArray("reasons") ?: JSONArray()
            return Analysis(
                summary = o.optString("summary"),
                status = o.optString("status", Status.UNANSWERED),
                expectedResponder = o.optString("expectedResponder", "unknown"),
                messageType = o.optString("messageType", "conversation"),
                importance = o.optInt("importance", 50),
                urgency = o.optInt("urgency", 50),
                aiPriority = o.optString("aiPriority", Level.MEDIUM),
                confidence = o.optInt("confidence", 70),
                deadlineIso = o.optString("deadlineIso"),
                deadlineText = o.optString("deadlineText"),
                deadlineConfidence = o.optInt("deadlineConfidence"),
                category = o.optString("category", "other"),
                sentiment = o.optString("sentiment", "neutral"),
                escalation = o.optBoolean("escalation"),
                escalationReason = o.optString("escalationReason"),
                questions = (0 until qs.length()).map { qs.getJSONObject(it).let { q -> Question(q.optString("text"), q.optString("status")) } },
                nextAction = o.optString("nextAction"),
                suggestedAssignee = o.optString("suggestedAssignee"),
                commitments = (0 until cs.length()).map { cs.getJSONObject(it).let { c -> Commitment(c.optString("text"), c.optString("owner"), c.optString("due")) } },
                reasons = (0 until rs.length()).map { rs.optString(it) },
                uncertainty = o.optString("uncertainty"),
                suggestedReply = o.optString("suggestedReply"),
                language = o.optString("language", "en"),
                analyzedAt = o.optLong("analyzedAt"),
                model = o.optString("model"),
                topic = o.optString("topic"),
                entities = (0 until es.length()).map { es.getJSONObject(it).let { e -> Entity(e.optString("type"), e.optString("name")) } },
                evidence = (0 until ev.length()).map { ev.getJSONObject(it).let { e -> Evidence(e.optString("ref"), e.optString("claim")) } },
                missingInfo = o.optString("missingInfo"),
            )
        }
    }
}

object AnalysisState {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val OFF = "off"          // auto-analysis disabled and never requested
}

data class Conversation(
    val id: String,
    val pkg: String,
    val appName: String,
    val channel: String,           // sms | mail | chat
    val contact: String,
    val subject: String,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val analysis: Analysis? = null,
    val analysisState: String = AnalysisState.PENDING,
    val analysisError: String = "",
    val attempts: Int = 0,
    val nextRetryAt: Long = 0L,
    val analyzedHash: String = "",
    val detailedSummary: String = "",
    val priorityOverride: String = "",
    val statusOverride: String = "",
    val categoryOverride: String = "",
    val archived: Boolean = false,
    val demo: Boolean = false,
    val source: String = Source.PHONE,
    /** Source-specific ids: messageId (latest incoming mail), chatId, teamId, channelId, rootId, webLink, participants. */
    val ext: Map<String, String> = emptyMap(),
) {
    val lastTime: Long get() = messages.maxOfOrNull { it.time } ?: createdAt
    val lastMessage: ChatMessage? get() = messages.maxByOrNull { it.time }
    val contentHash: String get() = Integer.toHexString(messages.joinToString("|") { it.id }.hashCode())
    val stale: Boolean get() = analysis != null && analyzedHash != contentHash

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("pkg", pkg).put("appName", appName).put("channel", channel)
        .put("contact", contact).put("subject", subject)
        .put("messages", JSONArray().also { a -> messages.forEach { a.put(it.toJson()) } })
        .put("createdAt", createdAt)
        .put("analysis", analysis?.toJson() ?: JSONObject.NULL)
        .put("analysisState", analysisState).put("analysisError", analysisError)
        .put("attempts", attempts).put("nextRetryAt", nextRetryAt).put("analyzedHash", analyzedHash)
        .put("detailedSummary", detailedSummary).put("priorityOverride", priorityOverride)
        .put("statusOverride", statusOverride).put("categoryOverride", categoryOverride)
        .put("archived", archived).put("demo", demo)
        .put("source", source).put("ext", JSONObject(ext as Map<*, *>))

    companion object {
        fun fromJson(o: JSONObject): Conversation {
            val ex = o.optJSONObject("ext")
            val extMap = mutableMapOf<String, String>()
            ex?.keys()?.forEach { k -> extMap[k] = ex.optString(k) }
            val ms = o.optJSONArray("messages") ?: JSONArray()
            val a = o.optJSONObject("analysis")
            return Conversation(
                id = o.getString("id"),
                pkg = o.optString("pkg"),
                appName = o.optString("appName"),
                channel = o.optString("channel", "chat"),
                contact = o.optString("contact"),
                subject = o.optString("subject"),
                messages = (0 until ms.length()).map { ChatMessage.fromJson(ms.getJSONObject(it)) },
                createdAt = o.optLong("createdAt"),
                analysis = a?.let { Analysis.fromJson(it) },
                analysisState = o.optString("analysisState", AnalysisState.PENDING).let {
                    if (it == AnalysisState.RUNNING) AnalysisState.PENDING else it   // interrupted run
                },
                analysisError = o.optString("analysisError"),
                attempts = o.optInt("attempts"),
                nextRetryAt = o.optLong("nextRetryAt"),
                analyzedHash = o.optString("analyzedHash"),
                detailedSummary = o.optString("detailedSummary"),
                priorityOverride = o.optString("priorityOverride"),
                statusOverride = o.optString("statusOverride"),
                categoryOverride = o.optString("categoryOverride"),
                archived = o.optBoolean("archived"),
                demo = o.optBoolean("demo"),
                source = o.optString("source", Source.PHONE),
                ext = extMap,
            )
        }
    }
}

data class AuditEntry(val time: Long, val convId: String, val actor: String, val action: String, val detail: String) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("convId", convId).put("actor", actor)
        .put("action", action).put("detail", detail)
    companion object {
        fun fromJson(o: JSONObject) = AuditEntry(o.optLong("time"), o.optString("convId"), o.optString("actor"), o.optString("action"), o.optString("detail"))
    }
}

data class Task(val id: String, val convId: String, val text: String, val dueAt: Long, val done: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("convId", convId).put("text", text).put("dueAt", dueAt).put("done", done)
    companion object {
        fun fromJson(o: JSONObject) = Task(o.getString("id"), o.optString("convId"), o.optString("text"), o.optLong("dueAt"), o.optBoolean("done"))
    }
}

/** A user correction of an AI decision, used for transparent learning. */
data class Correction(val time: Long, val contact: String, val field: String, val aiValue: String, val userValue: String) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("contact", contact).put("field", field)
        .put("aiValue", aiValue).put("userValue", userValue)
    companion object {
        fun fromJson(o: JSONObject) = Correction(o.optLong("time"), o.optString("contact"), o.optString("field"), o.optString("aiValue"), o.optString("userValue"))
    }
}

data class Template(val id: String, val lang: String, val text: String)

data class WatchedApp(val pkg: String, val name: String, val channel: String)
