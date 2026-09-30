package gr.ipexpert.inboxhelper.m365

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import gr.ipexpert.inboxhelper.data.AnalysisState
import gr.ipexpert.inboxhelper.data.ChatMessage
import gr.ipexpert.inboxhelper.data.Conversation
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.data.Source
import gr.ipexpert.inboxhelper.index.Index
import gr.ipexpert.inboxhelper.index.Item
import gr.ipexpert.inboxhelper.index.SyncState
import gr.ipexpert.inboxhelper.index.Text
import gr.ipexpert.inboxhelper.work.Analyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Live sync status for the health screen. */
data class SourceHealth(
    val key: String, val title: String, val connected: Boolean, val status: String, val lastSync: Long,
    val discovered: Int, val indexed: Int, val failed: Int, val error: String, val running: Boolean,
)

data class SyncProgress(val running: Boolean = false, val phase: String = "", val discovered: Int = 0, val indexed: Int = 0)

/**
 * Microsoft Graph synchronisation.
 * Mail: delta queries on Inbox and Sent Items (history window + incremental), checkpoint saved after EVERY page,
 * so a restart or network failure resumes where it stopped. @removed items are deleted locally.
 * Teams chats: per-chat checkpoints on lastModifiedDateTime. Channels: per-channel delta links. Calendar: rolling window.
 */
object SyncEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var debounce: Job? = null
    lateinit var appContext: Context

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress
    private val _tick = MutableStateFlow(0)
    /** Bumped whenever sync state changes, so the UI re-reads health. */
    val tick: StateFlow<Int> = _tick
    private fun bump() { _tick.value = _tick.value + 1 }

    const val MAIL_INBOX = "mail:inbox"
    const val MAIL_SENT = "mail:sent"
    const val TEAMS_CHATS = "teams:chats"
    const val TEAMS_CHANNELS = "teams:channels"
    const val CALENDAR = "calendar"

    private const val MAIL_SELECT = "id,conversationId,subject,from,toRecipients,ccRecipients,receivedDateTime,sentDateTime," +
        "body,hasAttachments,webLink,internetMessageId,parentFolderId,isDraft"

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun parseTime(s: String?): Long = try { if (s.isNullOrBlank()) 0L else OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val windowStart: Long get() = System.currentTimeMillis() - M365Config.historyMonths * 30L * 86_400_000L
    private val me: Account? get() = M365Config.account

    /** Debounced sync request (from a notification, the app opening, or after sending). */
    fun requestSync(reason: String, delayMs: Long = 2_000) {
        if (!M365Config.connected) return
        debounce?.cancel()
        debounce = scope.launch { delay(delayMs); syncAll(reason) }
    }

    /** Schedules a background (WorkManager) sync that survives the app closing; used for the initial import. */
    fun scheduleBackground(ctx: Context, full: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("full" to full))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("m365-sync", ExistingWorkPolicy.KEEP, req)
    }

    /** Forgets checkpoints so the next sync re-reads everything in the history window (fills any gaps). */
    fun resetCheckpoints() {
        Index.clearStates("mail:"); Index.clearStates("teams:"); Index.clearStates(CALENDAR)
        bump()
    }

    suspend fun syncAll(reason: String): Boolean {
        if (!M365Config.connected) return false
        if (!mutex.tryLock()) return false      // already running
        try {
            Repo.log("", "system", "m365_sync_start", reason)
            _progress.value = SyncProgress(true, "Starting")
            var ok = true
            var authLost = false
            suspend fun step(f: Feature, block: suspend () -> Unit) {
                if (authLost || !M365Config.enabled(f)) return
                try { block() } catch (e: Exception) {
                    ok = false
                    if (e is AuthException && e.needsSignIn) { authLost = true; Repo.log("", "system", "m365_auth_needed", e.message ?: "") }
                }
            }
            step(Feature.MAIL_READ) { syncMailFolder(MAIL_INBOX, "inbox") }
            step(Feature.MAIL_READ) { syncMailFolder(MAIL_SENT, "sentitems") }
            step(Feature.TEAMS_CHAT) { syncChats() }
            step(Feature.TEAMS_CHANNELS) { syncChannels() }
            step(Feature.CALENDAR) { syncCalendar() }
            buildConversations()
            Analyzer.sweep()
            Repo.log("", "system", "m365_sync_done", if (ok) "ok" else "with errors")
            return ok
        } finally {
            _progress.value = SyncProgress(false, "")
            bump()
            mutex.unlock()
        }
    }

    // ------------------------------------------------------------------ mail

    private fun addrOf(o: JSONObject?): Pair<String, String> {
        val e = o?.optJSONObject("emailAddress") ?: return "" to ""
        return e.optString("name") to e.optString("address").lowercase(Locale.ROOT)
    }

    private fun recipients(arr: JSONArray?): List<Pair<String, String>> =
        (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }.map { addrOf(it) }.filter { it.second.isNotBlank() }

    private fun mailItem(m: JSONObject, folder: String): Item? {
        if (m.optBoolean("isDraft")) return null
        val id = m.getString("id")
        val (fromName, fromAddr) = addrOf(m.optJSONObject("from"))
        val to = recipients(m.optJSONArray("toRecipients"))
        val cc = recipients(m.optJSONArray("ccRecipients"))
        val myMail = me?.email?.lowercase(Locale.ROOT) ?: ""
        val fromMe = folder == "sentitems" || (myMail.isNotBlank() && fromAddr == myMail)
        val time = parseTime(if (fromMe) m.optString("sentDateTime") else m.optString("receivedDateTime")).takeIf { it > 0 }
            ?: parseTime(m.optString("receivedDateTime"))
        val body = m.optJSONObject("body")?.let { b ->
            val c = b.optString("content")
            if (b.optString("contentType").equals("html", true)) Text.stripHtml(c) else c
        } ?: ""
        val extra = JSONObject()
            .put("to", to.joinToString(",") { it.second }).put("cc", cc.joinToString(",") { it.second })
            .put("toNames", to.joinToString(", ") { it.first.ifBlank { it.second } })
            .put("fromName", fromName).put("internetMessageId", m.optString("internetMessageId"))
        return Item(
            id = "outlook:$id", source = Source.OUTLOOK, externalId = id,
            threadKey = "outlook:" + m.optString("conversationId", id),
            subject = m.optString("subject"), senderName = fromName, senderAddr = fromAddr,
            recipients = (to + cc).joinToString(",") { it.second }, fromMe = fromMe, time = time,
            body = body.take(12_000), hasAttachments = m.optBoolean("hasAttachments"), webLink = m.optString("webLink"),
            folder = folder, extra = extra.toString(),
        )
    }

    private suspend fun syncMailFolder(key: String, folder: String) {
        var st = Index.state(key)
        val prefer = mapOf("Prefer" to "odata.maxpagesize=50, outlook.body-content-type=\"text\"")
        // "Discovered" = messages in Microsoft 365 inside the history window (so missing items can be detected).
        val discovered = try {
            Graph.getText("/me/mailFolders/$folder/messages/\$count?\$filter=${enc("receivedDateTime ge " + iso(windowStart))}",
                mapOf("ConsistencyLevel" to "eventual")).trim().toIntOrNull() ?: st.discovered
        } catch (e: GraphException) { st.discovered }
        var url = st.delta.ifBlank {
            "/me/mailFolders/$folder/messages/delta?\$select=$MAIL_SELECT&\$filter=${enc("receivedDateTime ge " + iso(windowStart))}"
        }
        st = st.copy(status = "syncing", error = "", discovered = discovered)
        Index.saveState(st); bump()
        var failed = 0
        try {
            while (true) {
                val page = try { Graph.getJson(url, prefer) } catch (e: GraphException) {
                    if (e.gone) {       // delta token expired → restart this folder from scratch
                        url = "/me/mailFolders/$folder/messages/delta?\$select=$MAIL_SELECT&\$filter=${enc("receivedDateTime ge " + iso(windowStart))}"
                        continue
                    } else throw e
                }
                val values = page.optJSONArray("value") ?: JSONArray()
                for (i in 0 until values.length()) {
                    val m = values.optJSONObject(i) ?: continue
                    val id = m.optString("id")
                    try {
                        if (m.has("@removed")) Index.delete("outlook:$id")
                        else mailItem(m, folder)?.let { Index.upsert(it) }
                        Index.clearFailure(key, id)
                    } catch (e: Exception) { failed++; Index.addFailure(key, id, e.message ?: "parse error") }
                }
                val next = page.optString("@odata.nextLink")
                val deltaLink = page.optString("@odata.deltaLink")
                val indexed = Index.countSince(Source.OUTLOOK, folder, windowStart)
                _progress.value = SyncProgress(true, if (folder == "inbox") "Outlook Inbox" else "Outlook Sent Items",
                    discovered, indexed)
                if (next.isNotBlank()) {
                    url = next
                    st = st.copy(delta = next, indexed = indexed, failed = failed)   // checkpoint after each page
                    Index.saveState(st); bump()
                } else {
                    st = st.copy(delta = deltaLink.ifBlank { st.delta }, indexed = indexed, failed = failed,
                        status = "healthy", lastSync = System.currentTimeMillis(), error = "")
                    Index.saveState(st); bump()
                    break
                }
            }
        } catch (e: Exception) {
            Index.saveState(Index.state(key).copy(status = if (e is GraphException && e.retryable) "throttled" else "error", error = e.message ?: "error"))
            bump(); throw e
        }
    }

    // ------------------------------------------------------------------ Teams chats

    private suspend fun syncChats() {
        val key = TEAMS_CHATS
        var st = Index.state(key)
        val checkpoints = st.extraJson()
        Index.saveState(st.copy(status = "syncing", error = "")); bump()
        var failed = 0; var discovered = 0
        try {
            val chats = mutableListOf<JSONObject>()
            var url: String? = "/me/chats?\$expand=members&\$top=50"
            while (url != null && chats.size < 600) {
                val page = Graph.getJson(url)
                val v = page.optJSONArray("value") ?: JSONArray()
                for (i in 0 until v.length()) v.optJSONObject(i)?.let { chats += it }
                url = page.optString("@odata.nextLink").ifBlank { null }
            }
            val myId = me?.id ?: ""
            for ((n, chat) in chats.withIndex()) {
                val chatId = chat.getString("id")
                val updated = parseTime(chat.optString("lastUpdatedDateTime"))
                val since = checkpoints.optLong(chatId, 0L)
                if (updated in 1..since) continue
                if (updated in 1 until windowStart) continue
                val members = chat.optJSONArray("members") ?: JSONArray()
                val others = (0 until members.length()).mapNotNull { members.optJSONObject(it) }
                    .filter { it.optString("userId") != myId }
                val title = chat.optString("topic").ifBlank { others.joinToString(", ") { it.optString("displayName") }.ifBlank { "Teams chat" } }
                val otherMails = others.mapNotNull { it.optString("email").takeIf { e -> e.isNotBlank() }?.lowercase(Locale.ROOT) }
                val from = if (since > 0) since else windowStart
                var mUrl: String? = "/me/chats/$chatId/messages?\$top=50&\$orderby=lastModifiedDateTime desc&\$filter=${enc("lastModifiedDateTime gt " + iso(from))}"
                var newest = since
                var pages = 0
                while (mUrl != null && pages < 40) {
                    pages++
                    val page = Graph.getJson(mUrl)
                    val v = page.optJSONArray("value") ?: JSONArray()
                    for (i in 0 until v.length()) {
                        val m = v.optJSONObject(i) ?: continue
                        val mid = m.optString("id")
                        try {
                            newest = maxOf(newest, parseTime(m.optString("lastModifiedDateTime")))
                            if (m.optString("deletedDateTime").let { it.isNotBlank() && it != "null" }) { Index.delete("teams_chat:$mid"); continue }
                            if (m.optString("messageType") != "message") continue
                            discovered++
                            val fromUser = m.optJSONObject("from")?.optJSONObject("user")
                            val fromId = fromUser?.optString("id") ?: ""
                            val fromName = fromUser?.optString("displayName") ?: "Teams"
                            val body = Text.stripHtml(m.optJSONObject("body")?.optString("content") ?: "")
                            val atts = m.optJSONArray("attachments")
                            val attNames = (0 until (atts?.length() ?: 0)).mapNotNull { atts?.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotBlank() } }
                            if (body.isBlank() && attNames.isEmpty()) continue
                            val fromAddr = others.firstOrNull { it.optString("userId") == fromId }?.optString("email")?.lowercase(Locale.ROOT) ?: ""
                            Index.upsert(Item(
                                id = "teams_chat:$mid", source = Source.TEAMS_CHAT, externalId = mid, threadKey = "teams_chat:$chatId",
                                subject = title, senderName = fromName, senderAddr = if (fromId == myId) (me?.email ?: "") else fromAddr,
                                recipients = otherMails.joinToString(","), fromMe = fromId == myId,
                                time = parseTime(m.optString("createdDateTime")), body = body.take(8000),
                                hasAttachments = attNames.isNotEmpty(), attachmentNames = attNames.joinToString(", "),
                                webLink = chat.optString("webUrl"), folder = "chat",
                                extra = JSONObject().put("chatId", chatId).put("title", title).toString(),
                            ))
                            Index.clearFailure(key, mid)
                        } catch (e: Exception) { failed++; Index.addFailure(key, mid, e.message ?: "parse error") }
                    }
                    mUrl = page.optString("@odata.nextLink").ifBlank { null }
                }
                checkpoints.put(chatId, maxOf(newest, updated))
                _progress.value = SyncProgress(true, "Teams chats (${n + 1}/${chats.size})", chats.size, n + 1)
                if (n % 10 == 9) Index.saveState(Index.state(key).copy(extra = checkpoints.toString()))   // checkpoint
            }
            Index.saveState(Index.state(key).copy(status = "healthy", lastSync = System.currentTimeMillis(), error = "",
                extra = checkpoints.toString(), discovered = chats.size, indexed = Index.count(Source.TEAMS_CHAT), failed = failed))
            bump()
        } catch (e: Exception) {
            Index.saveState(Index.state(key).copy(status = if (e is GraphException && e.permission) "no permission" else "error",
                error = e.message ?: "error", extra = checkpoints.toString()))
            bump(); throw e
        }
    }

    // ------------------------------------------------------------------ Teams channels

    private suspend fun syncChannels() {
        val key = TEAMS_CHANNELS
        val st = Index.state(key)
        val links = st.extraJson()
        Index.saveState(st.copy(status = "syncing", error = "")); bump()
        var failed = 0; var channelsSeen = 0
        try {
            val teams = Graph.getJson("/me/joinedTeams?\$select=id,displayName").optJSONArray("value") ?: JSONArray()
            for (t in 0 until teams.length()) {
                val team = teams.optJSONObject(t) ?: continue
                val teamId = team.getString("id"); val teamName = team.optString("displayName")
                val chans = Graph.getJson("/teams/$teamId/channels?\$select=id,displayName,webUrl").optJSONArray("value") ?: JSONArray()
                for (c in 0 until chans.length()) {
                    val ch = chans.optJSONObject(c) ?: continue
                    val chId = ch.getString("id"); val chName = ch.optString("displayName")
                    channelsSeen++
                    val lk = "$teamId|$chId"
                    var url: String? = links.optString(lk).ifBlank {
                        "/teams/$teamId/channels/$chId/messages/delta?\$top=50&\$filter=${enc("lastModifiedDateTime gt " + iso(maxOf(windowStart, System.currentTimeMillis() - 90L * 86_400_000L)))}"
                    }
                    var pages = 0
                    while (url != null && pages < 30) {
                        pages++
                        val page = try { Graph.getJson(url) } catch (e: GraphException) {
                            if (e.gone) { links.remove(lk); break } else throw e
                        }
                        val v = page.optJSONArray("value") ?: JSONArray()
                        for (i in 0 until v.length()) {
                            val m = v.optJSONObject(i) ?: continue
                            val mid = m.optString("id")
                            try {
                                val thread = "teams_channel:$teamId:$chId:$mid"
                                indexChannelMessage(m, teamId, chId, mid, thread, "$teamName › $chName", ch.optString("webUrl"))
                                // Replies of changed root messages.
                                val replies = try { Graph.getJson("/teams/$teamId/channels/$chId/messages/$mid/replies?\$top=50").optJSONArray("value") } catch (_: GraphException) { null }
                                for (r in 0 until (replies?.length() ?: 0)) replies?.optJSONObject(r)?.let {
                                    indexChannelMessage(it, teamId, chId, mid, thread, "$teamName › $chName", ch.optString("webUrl"))
                                }
                            } catch (e: Exception) { failed++; Index.addFailure(key, mid, e.message ?: "error") }
                        }
                        val next = page.optString("@odata.nextLink")
                        val delta = page.optString("@odata.deltaLink")
                        if (next.isNotBlank()) { url = next; links.put(lk, next) } else { if (delta.isNotBlank()) links.put(lk, delta); url = null }
                    }
                    Index.saveState(Index.state(key).copy(extra = links.toString()))
                    _progress.value = SyncProgress(true, "Teams channels: $teamName › $chName", 0, channelsSeen)
                }
            }
            Index.saveState(Index.state(key).copy(status = "healthy", lastSync = System.currentTimeMillis(), error = "",
                extra = links.toString(), discovered = channelsSeen, indexed = Index.count(Source.TEAMS_CHANNEL), failed = failed))
            bump()
        } catch (e: Exception) {
            Index.saveState(Index.state(key).copy(status = if (e is GraphException && e.permission) "no permission" else "error", error = e.message ?: "error", extra = links.toString()))
            bump(); throw e
        }
    }

    private fun indexChannelMessage(m: JSONObject, teamId: String, chId: String, rootId: String, thread: String, title: String, web: String) {
        val mid = m.optString("id")
        if (m.optString("deletedDateTime").let { it.isNotBlank() && it != "null" }) { Index.delete("teams_channel:$mid"); return }
        if (m.optString("messageType") != "message") return
        val fromUser = m.optJSONObject("from")?.optJSONObject("user")
        val fromId = fromUser?.optString("id") ?: ""
        val body = Text.stripHtml(m.optJSONObject("body")?.optString("content") ?: "")
        if (body.isBlank()) return
        Index.upsert(Item(
            id = "teams_channel:$mid", source = Source.TEAMS_CHANNEL, externalId = mid, threadKey = thread,
            subject = m.optString("subject").ifBlank { title }, senderName = fromUser?.optString("displayName") ?: "Teams",
            senderAddr = if (fromId == me?.id) (me?.email ?: "") else "", recipients = "", fromMe = fromId == me?.id,
            time = parseTime(m.optString("createdDateTime")), body = body.take(8000),
            webLink = m.optString("webUrl").ifBlank { web }, folder = "channel",
            extra = JSONObject().put("teamId", teamId).put("channelId", chId).put("rootId", rootId).put("title", title).toString(),
        ))
    }

    // ------------------------------------------------------------------ calendar

    private suspend fun syncCalendar() {
        val key = CALENDAR
        Index.saveState(Index.state(key).copy(status = "syncing", error = "")); bump()
        try {
            val now = System.currentTimeMillis()
            var url: String? = "/me/calendarView?startDateTime=${enc(iso(now - 14L * 86_400_000L))}&endDateTime=${enc(iso(now + 30L * 86_400_000L))}" +
                "&\$select=id,subject,organizer,attendees,start,end,location,bodyPreview,webLink&\$top=100"
            var n = 0
            while (url != null) {
                val page = Graph.getJson(url, mapOf("Prefer" to "outlook.timezone=\"UTC\""))
                val v = page.optJSONArray("value") ?: JSONArray()
                for (i in 0 until v.length()) {
                    val e = v.optJSONObject(i) ?: continue
                    val (orgName, orgAddr) = addrOf(e.optJSONObject("organizer"))
                    val att = e.optJSONArray("attendees")
                    val attendees = (0 until (att?.length() ?: 0)).mapNotNull { att?.optJSONObject(it) }.map { addrOf(it) }
                    val start = parseTime(e.optJSONObject("start")?.optString("dateTime")?.let { if (it.endsWith("Z")) it else it.take(19) + "Z" })
                    val loc = e.optJSONObject("location")?.optString("displayName") ?: ""
                    Index.upsert(Item(
                        id = "calendar:" + e.getString("id"), source = Source.CALENDAR, externalId = e.getString("id"),
                        threadKey = "calendar:" + e.getString("id"), subject = e.optString("subject"),
                        senderName = orgName, senderAddr = orgAddr, recipients = attendees.joinToString(",") { it.second },
                        fromMe = orgAddr == me?.email?.lowercase(Locale.ROOT), time = start,
                        body = listOf("Meeting: " + e.optString("subject"), if (loc.isNotBlank()) "Location: $loc" else "",
                            "Attendees: " + attendees.joinToString(", ") { it.first.ifBlank { it.second } }, e.optString("bodyPreview")).filter { it.isNotBlank() }.joinToString("\n"),
                        webLink = e.optString("webLink"), folder = "calendar",
                    ))
                    n++
                }
                url = page.optString("@odata.nextLink").ifBlank { null }
            }
            Index.saveState(Index.state(key).copy(status = "healthy", lastSync = System.currentTimeMillis(), discovered = n, indexed = n, error = ""))
            bump()
        } catch (e: Exception) {
            Index.saveState(Index.state(key).copy(status = "error", error = e.message ?: "error")); bump(); throw e
        }
    }

    // ------------------------------------------------------------------ index → live conversations

    /**
     * Turns recently active threads (Outlook conversation, Teams chat, channel thread) into conversations
     * for the intelligence engine. Keeps AI analysis and user overrides; marks for re-analysis only if content changed.
     */
    fun buildConversations() {
        val since = System.currentTimeMillis() - M365Config.activeDays * 86_400_000L
        val sources = listOf(Source.OUTLOOK, Source.TEAMS_CHAT, Source.TEAMS_CHANNEL)
        val myMail = me?.email?.lowercase(Locale.ROOT) ?: ""
        for ((threadKey, _) in Index.activeThreads(since, sources)) {
            val items = Index.thread(threadKey, 60)
            if (items.isEmpty()) continue
            val first = items.first()
            val lastIncoming = items.lastOrNull { !it.fromMe }
            val latest = items.last()
            val contact = when (first.source) {
                Source.OUTLOOK -> (lastIncoming?.senderName?.ifBlank { lastIncoming.senderAddr })
                    ?: latest.extraJson().optString("toNames").ifBlank { latest.recipients.substringBefore(',') }
                else -> first.extraJson().optString("title").ifBlank { lastIncoming?.senderName ?: "Teams" }
            }
            val participants = items.flatMap { listOf(it.senderAddr) + it.recipients.split(',') }
                .map { it.trim().lowercase(Locale.ROOT) }.filter { it.contains('@') && it != myMail }.distinct()
            val x = latest.extraJson()
            val ext = mutableMapOf(
                "threadKey" to threadKey, "me" to myMail, "participants" to participants.joinToString(","),
                "webLink" to (lastIncoming ?: latest).webLink,
                "messageId" to ((lastIncoming ?: latest).externalId),
            )
            x.optString("chatId").takeIf { it.isNotBlank() }?.let { ext["chatId"] = it }
            x.optString("teamId").takeIf { it.isNotBlank() }?.let { ext["teamId"] = it }
            x.optString("channelId").takeIf { it.isNotBlank() }?.let { ext["channelId"] = it }
            x.optString("rootId").takeIf { it.isNotBlank() }?.let { ext["rootId"] = it }
            val messages = items.map { i ->
                ChatMessage(i.id, if (i.fromMe) "Me" else i.senderName.ifBlank { i.senderAddr }, i.fromMe,
                    (if (i.hasAttachments && i.attachmentNames.isNotBlank()) i.body + "\n[Attachments: ${i.attachmentNames}]" else i.body).take(6000),
                    i.time, i.source, i.id)
            }
            val id = "m365|$threadKey"
            val old = Repo.get(id)
            // Keep an optimistic "sent from this app" message until the real one arrives from Sent Items.
            val pendingMine = old?.messages?.filter { it.fromMe && it.ref.isBlank() && it.time > latest.time && System.currentTimeMillis() - it.time < 30 * 60_000 } ?: emptyList()
            val conv = (old ?: Conversation(id, "", Source.label(first.source), if (first.source == Source.OUTLOOK) "mail" else "chat",
                contact, first.subject, emptyList(), first.time, source = first.source))
                .copy(contact = contact, subject = latest.subject.ifBlank { first.subject }, messages = messages + pendingMine, ext = ext)
            val changed = old == null || old.contentHash != conv.contentHash
            val newIncoming = old == null || (lastIncoming != null && old.messages.none { it.id == lastIncoming.id })
            Repo.upsertQuiet(conv.copy(
                analysisState = if (!changed) conv.analysisState else if (Settings.autoAnalyze) AnalysisState.PENDING else AnalysisState.OFF,
                statusOverride = if (newIncoming && old != null) "" else conv.statusOverride,
            ))
        }
        Repo.flush()
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!M365Config.connected) return Result.success()
        if (inputData.getBoolean("full", false)) SyncEngine.resetCheckpoints()
        return try {
            if (SyncEngine.syncAll("background")) Result.success() else Result.retry()
        } catch (e: Exception) { Result.retry() }
    }
}
