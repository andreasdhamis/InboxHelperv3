package gr.ipexpert.inboxhelper.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Single source of truth for conversations, tasks, audit log and corrections. */
object Repo {
    private const val MAX_CONVERSATIONS = 500
    private const val MAX_MESSAGES_PER_CONV = 60
    private const val MAX_AUDIT = 2000

    private lateinit var dir: File
    private val _convs = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _convs
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks
    private val _audit = MutableStateFlow<List<AuditEntry>>(emptyList())
    val audit: StateFlow<List<AuditEntry>> = _audit
    private val _corrections = MutableStateFlow<List<Correction>>(emptyList())
    val corrections: StateFlow<List<Correction>> = _corrections

    /** Reply buttons / open intents can't be persisted; they live while the process lives. */
    val replyActions = ConcurrentHashMap<String, Notification.Action>()
    val openIntents = ConcurrentHashMap<String, PendingIntent>()
    private val _replyVersion = MutableStateFlow(0)
    val replyVersion: StateFlow<Int> = _replyVersion

    fun init(ctx: Context) {
        dir = ctx.filesDir
        _convs.value = readList("conversations.json") { Conversation.fromJson(it) }
        _tasks.value = readList("tasks.json") { Task.fromJson(it) }
        _audit.value = readList("audit.json") { AuditEntry.fromJson(it) }
        _corrections.value = readList("corrections.json") { Correction.fromJson(it) }
    }

    fun get(id: String): Conversation? = _convs.value.firstOrNull { it.id == id }

    fun setReplyAction(id: String, a: Notification.Action?) {
        if (a == null) replyActions.remove(id) else replyActions[id] = a
        _replyVersion.value = _replyVersion.value + 1
    }

    // ---------------- ingestion ----------------

    /**
     * Merges messages seen in a notification into a conversation.
     * Returns true when a NEW incoming message arrived (so analysis should run).
     */
    @Synchronized
    fun ingest(
        id: String, pkg: String, appName: String, channel: String,
        contact: String, subject: String, incoming: List<ChatMessage>,
    ): Boolean {
        if (incoming.isEmpty()) return false
        val list = _convs.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val old = if (idx >= 0) list[idx] else null
        val known = old?.messages?.map { it.id }?.toHashSet() ?: hashSetOf()
        // Our own replies may already be stored (sent from this app) with a slightly different time: match by text.
        val myTexts = old?.messages?.filter { it.fromMe }?.map { it.text.trim() }?.toHashSet() ?: hashSetOf()
        val fresh = incoming.filter { m -> m.id !in known && !(m.fromMe && m.text.trim() in myTexts) }
        if (fresh.isEmpty() && old != null) return false
        val merged = ((old?.messages ?: emptyList()) + fresh).sortedBy { it.time }.takeLast(MAX_MESSAGES_PER_CONV)
        val conv = (old ?: Conversation(id, pkg, appName, channel, contact, subject, emptyList(), System.currentTimeMillis()))
            .copy(
                contact = contact.ifBlank { old?.contact ?: contact },
                subject = subject.ifBlank { old?.subject ?: "" },
                messages = merged,
                archived = false,
                // A new incoming message re-opens a conversation you had manually marked answered/completed.
                statusOverride = if (fresh.any { !it.fromMe }) "" else (old?.statusOverride ?: ""),
                analysisState = if (Settings.autoAnalyze) AnalysisState.PENDING else (old?.analysisState ?: AnalysisState.OFF),
                attempts = 0, nextRetryAt = 0L,
            )
        if (idx >= 0) list.removeAt(idx)
        list.add(0, conv)
        save(list)
        return fresh.any { !it.fromMe }
    }

    @Synchronized
    fun addMyReply(id: String, text: String, via: String) {
        val now = System.currentTimeMillis()
        update(id) { c ->
            c.copy(messages = c.messages + ChatMessage(ChatMessage.makeId("Me", text, now), "Me", true, text, now))
        }
        log(id, "user", "reply_sent", "via $via: ${text.take(120)}")
    }

    // ---------------- updates ----------------

    @Synchronized
    fun update(id: String, transform: (Conversation) -> Conversation) {
        save(_convs.value.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun upsert(conv: Conversation) {
        val list = _convs.value.filterNot { it.id == conv.id }.toMutableList()
        list.add(0, conv)
        save(list)
    }

    /** In-memory upsert used by bulk sync; call [flush] once afterwards to persist. */
    @Synchronized
    fun upsertQuiet(conv: Conversation) {
        val list = _convs.value.toMutableList()
        val idx = list.indexOfFirst { it.id == conv.id }
        if (idx >= 0) list[idx] = conv else list.add(0, conv)
        _convs.value = list.sortedByDescending { it.lastTime }
    }

    @Synchronized
    fun flush() = save(_convs.value)

    /** Removes conversations created from a source (e.g. after disconnecting Microsoft 365). */
    @Synchronized
    fun deleteBySource(prefix: String) = save(_convs.value.filterNot { it.id.startsWith(prefix) })

    fun overridePriority(id: String, level: String) {
        val c = get(id) ?: return
        val ai = c.analysis?.aiPriority ?: ""
        update(id) { it.copy(priorityOverride = level) }
        log(id, "user", "priority_override", "AI=$ai → user=${level.ifBlank { "(cleared)" }}")
        if (level.isNotBlank() && ai.isNotBlank() && ai != level) addCorrection(Correction(System.currentTimeMillis(), c.contact, "priority", ai, level))
    }

    fun overrideStatus(id: String, status: String) {
        val c = get(id) ?: return
        update(id) { it.copy(statusOverride = status) }
        log(id, "user", "status_override", "AI=${c.analysis?.status ?: ""} → user=${status.ifBlank { "(cleared)" }}")
    }

    fun overrideCategory(id: String, cat: String) {
        val c = get(id) ?: return
        update(id) { it.copy(categoryOverride = cat) }
        log(id, "user", "category_override", "AI=${c.analysis?.category ?: ""} → user=${cat.ifBlank { "(cleared)" }}")
        if (cat.isNotBlank() && c.analysis != null && c.analysis.category != cat) addCorrection(Correction(System.currentTimeMillis(), c.contact, "category", c.analysis.category, cat))
    }

    fun archive(id: String, on: Boolean) {
        update(id) { it.copy(archived = on) }
        log(id, "user", if (on) "archived" else "unarchived", "")
    }

    @Synchronized
    fun delete(id: String) {
        replyActions.remove(id); openIntents.remove(id)
        save(_convs.value.filterNot { it.id == id })
        log(id, "user", "deleted", "")
    }

    @Synchronized
    fun clearAll() {
        replyActions.clear(); openIntents.clear()
        save(emptyList())
        log("", "user", "cleared_all", "")
    }

    /** Deletes conversations with no activity for longer than the retention period. */
    @Synchronized
    fun applyRetention(now: Long) {
        val cutoff = now - Settings.retentionDays * 86_400_000L
        val keep = _convs.value.filter { it.lastTime >= cutoff }
        if (keep.size != _convs.value.size) {
            log("", "system", "retention_cleanup", "${_convs.value.size - keep.size} conversations removed")
            save(keep)
        }
    }

    // ---------------- tasks ----------------

    @Synchronized
    fun addTask(t: Task) {
        saveTasks(listOf(t) + _tasks.value)
        log(t.convId, "user", "task_created", t.text)
    }

    @Synchronized
    fun setTaskDone(id: String, done: Boolean) = saveTasks(_tasks.value.map { if (it.id == id) it.copy(done = done) else it })

    @Synchronized
    fun deleteTask(id: String) = saveTasks(_tasks.value.filterNot { it.id == id })

    // ---------------- audit & learning ----------------

    @Synchronized
    fun log(convId: String, actor: String, action: String, detail: String) {
        val list = (listOf(AuditEntry(System.currentTimeMillis(), convId, actor, action, detail)) + _audit.value).take(MAX_AUDIT)
        _audit.value = list
        writeList("audit.json", list) { it.toJson() }
    }

    @Synchronized
    private fun addCorrection(c: Correction) {
        val list = (listOf(c) + _corrections.value).take(500)
        _corrections.value = list
        writeList("corrections.json", list) { it.toJson() }
    }

    @Synchronized
    fun forgetCorrections(contact: String) {
        val list = _corrections.value.filterNot { it.contact == contact }
        _corrections.value = list
        writeList("corrections.json", list) { it.toJson() }
        log("", "user", "learning_reset", contact)
    }

    // ---------------- storage ----------------

    private fun save(list: List<Conversation>) {
        val trimmed = list.take(MAX_CONVERSATIONS)
        _convs.value = trimmed
        writeList("conversations.json", trimmed) { it.toJson() }
    }

    private fun saveTasks(list: List<Task>) {
        _tasks.value = list
        writeList("tasks.json", list) { it.toJson() }
    }

    private fun <T> writeList(name: String, list: List<T>, toJson: (T) -> org.json.JSONObject) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(dir, name))   // atomic replace
        } catch (_: Exception) { }
    }

    private fun <T> readList(name: String, fromJson: (org.json.JSONObject) -> T): List<T> {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> try { fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null } }
        } catch (_: Exception) { emptyList() }
    }
}
