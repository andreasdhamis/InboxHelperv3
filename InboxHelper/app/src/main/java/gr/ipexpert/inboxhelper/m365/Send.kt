package gr.ipexpert.inboxhelper.m365

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import gr.ipexpert.inboxhelper.data.Conversation
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Source
import gr.ipexpert.inboxhelper.index.Index
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class MailMode(val label: String) { REPLY("Reply"), REPLY_ALL("Reply all"), FORWARD("Forward"), NEW("New email") }

data class Attachment(val name: String, val mime: String, val bytes: ByteArray)

/** Everything shown on the confirmation screen before anything is sent. */
data class OutgoingMail(
    val convId: String,
    val mode: MailMode,
    val messageId: String,          // Graph id of the message being replied to / forwarded
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val body: String,               // includes signature when enabled
    val attachments: List<Attachment>,
    val account: String,
)

object Sender {
    private const val MAX_ATTACHMENT = 3 * 1024 * 1024   // simple upload limit for fileAttachment

    fun withSignature(body: String): String {
        val sig = M365Config.signature.trim()
        if (sig.isBlank()) return body
        val firstLine = sig.lineSequence().first().trim()
        return if (firstLine.isNotBlank() && body.contains(firstLine)) body else body.trimEnd() + "\n\n" + sig
    }

    private fun split(s: String) = s.split(',', ';').map { it.trim().lowercase(Locale.ROOT) }.filter { it.contains('@') }

    /** Builds the outgoing mail preview from the conversation's latest message (reply target). */
    fun prepare(c: Conversation, mode: MailMode, body: String, forwardTo: String, attachments: List<Attachment>): OutgoingMail {
        val target = c.ext["messageId"]?.let { Index.get("outlook:$it") }
        val me = M365Config.account?.email?.lowercase(Locale.ROOT) ?: ""
        val x = target?.extraJson()
        val origTo = split(x?.optString("to") ?: ""); val origCc = split(x?.optString("cc") ?: "")
        val subjectBase = target?.subject ?: c.subject
        val (to, cc, subject) = when (mode) {
            MailMode.REPLY -> Triple(listOfNotNull(target?.senderAddr?.takeIf { it.isNotBlank() && it != me } ?: origTo.firstOrNull { it != me }), emptyList<String>(), prefix("RE: ", subjectBase))
            MailMode.REPLY_ALL -> {
                val all = (listOfNotNull(target?.senderAddr) + origTo).filter { it.isNotBlank() && it != me }.distinct()
                Triple(all, origCc.filter { it != me && it !in all }, prefix("RE: ", subjectBase))
            }
            MailMode.FORWARD -> Triple(split(forwardTo), emptyList<String>(), prefix("FW: ", subjectBase))
            MailMode.NEW -> Triple(split(forwardTo), emptyList<String>(), c.subject)
        }
        return OutgoingMail(c.id, mode, target?.externalId ?: "", to, cc, subject, withSignature(body), attachments, M365Config.account?.email ?: "")
    }

    private fun prefix(p: String, s: String) = if (s.startsWith(p.trim(), ignoreCase = true)) s else p + s

    private fun recipientsJson(list: List<String>) = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("emailAddress", JSONObject().put("address", it))) } }

    /** Sends through Microsoft Graph: create draft (reply/reply-all/forward/new) → add attachments → send. */
    suspend fun sendMail(m: OutgoingMail) {
        if (!M365Config.enabled(Feature.MAIL_SEND)) throw AuthException("Turn on “Send via Outlook” in Microsoft 365 settings first")
        if (m.to.isEmpty()) throw IllegalArgumentException("No recipient")
        m.attachments.firstOrNull { it.bytes.size > MAX_ATTACHMENT }?.let { throw IllegalArgumentException("${it.name} is larger than 3 MB") }
        val draft: JSONObject = when (m.mode) {
            MailMode.REPLY -> Graph.postJson("/me/messages/${m.messageId}/createReply", JSONObject().put("comment", m.body))
            MailMode.REPLY_ALL -> Graph.postJson("/me/messages/${m.messageId}/createReplyAll", JSONObject().put("comment", m.body))
            MailMode.FORWARD -> Graph.postJson("/me/messages/${m.messageId}/createForward",
                JSONObject().put("comment", m.body).put("toRecipients", recipientsJson(m.to)))
            MailMode.NEW -> Graph.postJson("/me/messages", JSONObject().put("subject", m.subject)
                .put("body", JSONObject().put("contentType", "Text").put("content", m.body))
                .put("toRecipients", recipientsJson(m.to)).put("ccRecipients", recipientsJson(m.cc)))
        }
        val draftId = draft.optString("id")
        if (draftId.isBlank()) throw IllegalStateException("Outlook did not create the draft")
        for (a in m.attachments) {
            Graph.postJson("/me/messages/$draftId/attachments", JSONObject()
                .put("@odata.type", "#microsoft.graph.fileAttachment").put("name", a.name).put("contentType", a.mime)
                .put("contentBytes", Base64.encodeToString(a.bytes, Base64.NO_WRAP)))
        }
        Graph.request("POST", "/me/messages/$draftId/send")
        Repo.addMyReply(m.convId, m.body, "Outlook ${m.mode.label}")
        Repo.log(m.convId, "user", "outlook_sent", "${m.mode.label} to ${m.to.joinToString()} — “${m.subject}”" +
            if (m.attachments.isNotEmpty()) " + ${m.attachments.size} attachment(s)" else "")
        SyncEngine.requestSync("sent", 4_000)       // bring the real message back from Sent Items
    }

    /** Posts a reply in the Teams chat or channel thread of the conversation. */
    suspend fun sendTeams(c: Conversation, text: String) {
        val body = JSONObject().put("body", JSONObject().put("contentType", "text").put("content", text))
        when (c.source) {
            Source.TEAMS_CHAT -> {
                if (!M365Config.enabled(Feature.TEAMS_CHAT_SEND)) throw AuthException("Turn on “Reply in Teams chats” in Microsoft 365 settings first")
                Graph.postJson("/chats/${c.ext["chatId"]}/messages", body)
            }
            Source.TEAMS_CHANNEL -> {
                if (!M365Config.enabled(Feature.TEAMS_CHANNEL_SEND)) throw AuthException("Turn on “Reply in Teams channels” in Microsoft 365 settings first")
                Graph.postJson("/teams/${c.ext["teamId"]}/channels/${c.ext["channelId"]}/messages/${c.ext["rootId"]}/replies", body)
            }
            else -> throw IllegalStateException("Not a Teams conversation")
        }
        Repo.addMyReply(c.id, text, "Teams")
        Repo.log(c.id, "user", "teams_sent", text.take(120))
        SyncEngine.requestSync("sent", 4_000)
    }

    /** Reads a picked file (Storage Access Framework) into an attachment. */
    suspend fun readAttachment(ctx: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        var name = "attachment"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        Attachment(name, cr.getType(uri) ?: "application/octet-stream", bytes)
    }
}
