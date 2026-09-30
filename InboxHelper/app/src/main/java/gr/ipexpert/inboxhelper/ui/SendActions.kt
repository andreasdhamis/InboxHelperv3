package gr.ipexpert.inboxhelper.ui

import android.content.Context
import gr.ipexpert.inboxhelper.data.Conversation
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Source
import gr.ipexpert.inboxhelper.data.Task
import gr.ipexpert.inboxhelper.engine.Clock
import gr.ipexpert.inboxhelper.m365.Attachment
import gr.ipexpert.inboxhelper.m365.Feature
import gr.ipexpert.inboxhelper.m365.M365Config
import gr.ipexpert.inboxhelper.m365.MailMode
import gr.ipexpert.inboxhelper.m365.OutgoingMail
import gr.ipexpert.inboxhelper.m365.Sender
import gr.ipexpert.inboxhelper.work.Replier
import gr.ipexpert.inboxhelper.work.ReminderWorker
import java.time.LocalDateTime
import java.time.LocalTime

/** What the confirmation dialog shows and what gets executed after the user confirms. */
sealed class PendingSend {
    abstract val convId: String
    abstract val text: String
    data class Mail(val mail: OutgoingMail) : PendingSend() { override val convId get() = mail.convId; override val text get() = mail.body }
    data class Teams(override val convId: String, override val text: String, val where: String) : PendingSend()
    data class Phone(override val convId: String, override val text: String, val app: String) : PendingSend()
}

object SendActions {
    /** How this conversation can be answered from inside the app. */
    fun channelLabel(c: Conversation): String = when {
        c.source == Source.OUTLOOK && M365Config.enabled(Feature.MAIL_SEND) -> "Send via Outlook"
        c.source == Source.TEAMS_CHAT && M365Config.enabled(Feature.TEAMS_CHAT_SEND) -> "Send via Teams"
        c.source == Source.TEAMS_CHANNEL && M365Config.enabled(Feature.TEAMS_CHANNEL_SEND) -> "Reply in channel"
        c.source == Source.PHONE && Replier.canReply(c.id) -> "Send via ${c.appName}"
        else -> ""
    }

    fun whyCantSend(c: Conversation): String = when (c.source) {
        Source.OUTLOOK -> "Turn on “Send via Outlook” in Microsoft 365 settings to send from here."
        Source.TEAMS_CHAT -> "Turn on “Reply in Teams chats” in Microsoft 365 settings to reply from here."
        Source.TEAMS_CHANNEL -> "Turn on “Reply in Teams channels” in Microsoft 365 settings to reply from here."
        else -> if (c.demo) "Demo conversation — sending is disabled." else "No reply button available (notification cleared). Use “Copy & open”."
    }

    fun prepare(c: Conversation, text: String, mode: MailMode = MailMode.REPLY, to: String = "", attachments: List<Attachment> = emptyList()): PendingSend? = when {
        c.demo -> null
        c.source == Source.OUTLOOK -> PendingSend.Mail(Sender.prepare(c, mode, text, to, attachments))
        c.source == Source.TEAMS_CHAT || c.source == Source.TEAMS_CHANNEL -> PendingSend.Teams(c.id, text.trim(), c.contact)
        Replier.canReply(c.id) -> PendingSend.Phone(c.id, text.trim(), c.appName)
        else -> null
    }

    /** Executes a confirmed send. Throws with a readable message on failure. */
    suspend fun execute(ctx: Context, p: PendingSend) {
        when (p) {
            is PendingSend.Mail -> Sender.sendMail(p.mail)
            is PendingSend.Teams -> Sender.sendTeams(Repo.get(p.convId) ?: throw IllegalStateException("Conversation not found"), p.text)
            is PendingSend.Phone -> if (!Replier.send(ctx, p.convId, p.text)) throw IllegalStateException("The app's reply button is no longer available")
        }
    }

    /** Parses "in 2h", "in 30m", "tomorrow 09:00", "tomorrow", ISO date-time; default tomorrow 09:00. */
    fun parseWhen(s: String): Long {
        try { return LocalDateTime.parse(s.trim().take(16)).atZone(Clock.zone).toInstant().toEpochMilli() } catch (_: Exception) { }
        val t = s.trim().lowercase()
        Regex("in\\s*(\\d+)\\s*(h|hour|hours|ώρ|ωρ)").find(t)?.let { return System.currentTimeMillis() + it.groupValues[1].toLong() * 3_600_000L }
        Regex("in\\s*(\\d+)\\s*(m|min|minutes|λεπ)").find(t)?.let { return System.currentTimeMillis() + it.groupValues[1].toLong() * 60_000L }
        try { return LocalDateTime.parse(t.take(16)).atZone(Clock.zone).toInstant().toEpochMilli() } catch (_: Exception) { }
        val time = Regex("(\\d{1,2}):(\\d{2})").find(t)?.let { LocalTime.of(it.groupValues[1].toInt().coerceIn(0, 23), it.groupValues[2].toInt().coerceIn(0, 59)) } ?: LocalTime.of(9, 0)
        val day = if (t.contains("today") || t.contains("σήμερα") || t.contains("σημερα")) Clock.today() else Clock.today().plusDays(1)
        return day.atTime(time).atZone(Clock.zone).toInstant().toEpochMilli()
    }

    fun remind(ctx: Context, convId: String, text: String, dueAt: Long) {
        val t = Task("task" + System.currentTimeMillis() + "-" + (0..9999).random(), convId, text, dueAt, false)
        Repo.addTask(t)
        ReminderWorker.schedule(ctx, t.id, dueAt)
    }
}
