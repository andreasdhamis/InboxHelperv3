package gr.ipexpert.inboxhelper

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import gr.ipexpert.inboxhelper.data.ChatMessage
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.work.Analyzer
import gr.ipexpert.inboxhelper.m365.M365Config
import gr.ipexpert.inboxhelper.m365.SyncEngine

/**
 * Ingestion: turns notifications from watched apps into conversation messages.
 * Threads are identified by app + contact (+ normalised subject for email), so a new notification
 * for the same chat or "RE:" email joins the existing conversation.
 */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { handle(it) } } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (_: Exception) { }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap, reason: Int) {
        val id = keyToConv.remove(sbn.key) ?: return
        Repo.setReplyAction(id, null)
        Repo.openIntents.remove(id)
    }

    private val keyToConv = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun handle(sbn: StatusBarNotification) {
        // With Microsoft 365 connected, Outlook and Teams come from Microsoft Graph (full threads, history, sending).
        // Their phone notifications just trigger an immediate sync instead of being imported twice.
        if (sbn.packageName in M365_APPS && M365Config.connected) { SyncEngine.requestSync("notification ${sbn.packageName}"); return }
        val app = Settings.appFor(sbn.packageName) ?: return
        if (!Settings.isAppEnabled(sbn.packageName)) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (sbn.isOngoing) return
        val p = parse(n, app.channel, sbn.postTime) ?: return
        if (p.contact.isBlank()) return

        val convId = conversationId(sbn.packageName, app.channel, p.contact, p.subject)
        keyToConv[sbn.key] = convId
        Repo.setReplyAction(convId, findReplyAction(n))
        n.contentIntent?.let { Repo.openIntents[convId] = it }

        val newIncoming = Repo.ingest(convId, sbn.packageName, app.name, app.channel, p.contact, p.subject, p.messages)
        if (newIncoming) Repo.log(convId, "system", "message_received", "${app.name}: ${p.contact}")
        if (Settings.autoAnalyze && Settings.hasKey && (newIncoming || Repo.get(convId)?.analysis == null)) {
            Analyzer.enqueue(convId)
        }
    }

    private data class Parsed(val contact: String, val subject: String, val messages: List<ChatMessage>)

    private fun parse(n: Notification, channel: String, postTime: Long): Parsed? {
        val ex: Bundle = n.extras ?: return null
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        // Chat apps (MessagingStyle) include the recent history with timestamps; sender == null means the owner.
        @Suppress("DEPRECATION")
        val raw: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (raw != null && raw.isNotEmpty()) {
            val msgs = raw.mapNotNull { item ->
                val b = item as? Bundle ?: return@mapNotNull null
                val t = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (t.isEmpty()) return@mapNotNull null
                val sender = b.getCharSequence("sender")?.toString()
                val time = b.getLong("time", postTime).let { if (it <= 0) postTime else it }
                val fromMe = sender == null
                val name = sender ?: "Me"
                ChatMessage(ChatMessage.makeId(name, t, time), name, fromMe, t, time)
            }
            if (msgs.isEmpty()) return null
            val contact = conv.ifEmpty { msgs.lastOrNull { !it.fromMe }?.sender ?: title }
            return Parsed(contact, "", msgs)
        }

        if (text.isEmpty() && big.isEmpty()) return null
        return if (channel == "mail") {
            // Gmail/Outlook: title = sender, text = subject, big text = subject + body preview.
            val body = big.ifEmpty { text }
            Parsed(title, text, listOf(ChatMessage(ChatMessage.makeId(title, body, postTime), title, false, body, postTime)))
        } else {
            val body = big.ifEmpty { text }
            Parsed(title, "", listOf(ChatMessage(ChatMessage.makeId(title, body, postTime), title, false, body, postTime)))
        }
    }

    companion object {
        private val M365_APPS = setOf("com.microsoft.office.outlook", "com.microsoft.teams")
        private val subjectPrefix = Regex("^((re|fw|fwd|aw|sv|απ|σχετ|προώθ)\\s*:\\s*)+", RegexOption.IGNORE_CASE)

        fun normaliseSubject(s: String): String = s.trim().replace(subjectPrefix, "").trim().lowercase()

        fun conversationId(pkg: String, channel: String, contact: String, subject: String): String =
            if (channel == "mail") "$pkg|${contact.lowercase()}|${normaliseSubject(subject)}"
            else "$pkg|${contact.lowercase()}"

        fun findReplyAction(n: Notification): Notification.Action? {
            n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }?.let { return it }
            @Suppress("DEPRECATION")
            return Notification.WearableExtender(n).actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        }
    }
}
